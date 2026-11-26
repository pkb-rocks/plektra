package rocks.pkb.plektra

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.Window
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.max
import kotlin.math.min

/*
 * Full-screen settings (app appearance › Full-screen settings).
 *
 * A settings window hides the status bar; the navigation bar, system gestures and the
 * IME stay untouched, and a swipe from the top edge shows the status bar transiently.
 * The page header then is the topmost row. Display cutouts are not padded away as a
 * blank strip: only header elements that actually meet a cutout move sideways.
 */

/**
 * Hides or shows the status bar of a settings [window]. Applied again on resume, as
 * other windows (or the system) may have shown it meanwhile.
 */
@Composable
internal fun SettingsSystemBars(window: Window, view: View, fullscreen: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(window, view, fullscreen, lifecycleOwner) {
        fun apply() {
            WindowCompat.getInsetsController(window, view).run {
                if (fullscreen) {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    show(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
        apply()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Display cutout rectangles in window pixels; tests and previews provide synthetic ones. */
internal val LocalDisplayCutoutRects = staticCompositionLocalOf<List<Rect>?> { null }

/**
 * Opens a search result. [SettingsScreen] provides its own handler, which highlights a
 * row on the current page in place; elsewhere the result opens through its setting link.
 */
internal val LocalSettingEntryNavigator = staticCompositionLocalOf<((SettingEntry) -> Unit)?> { null }

internal fun openSettingEntryByLink(context: android.content.Context, entry: SettingEntry) {
    context.startActivity(Intent(context, SettingsActivity::class.java).apply {
        data = android.net.Uri.parse("plektra://setting/${entry.id}")
    })
}

/** The cutouts the header must avoid: the real ones while the status bar is hidden, else none. */
@Composable
internal fun settingsHeaderCutouts(fullscreen: Boolean): List<Rect> {
    if (!fullscreen) return emptyList()
    LocalDisplayCutoutRects.current?.let { return it }
    val view = LocalView.current
    fun read() = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
    var rects by remember(view) { mutableStateOf(read()) }
    // The rectangles move with rotation, folding and window changes even when the cutout
    // insets keep their values, so they are read again on every layout of the window.
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            read().let { if (it != rects) rects = it }
        }
        observer.addOnGlobalLayoutListener(listener)
        rects = read()
        onDispose {
            val current = if (observer.isAlive) observer else view.viewTreeObserver
            if (current.isAlive) current.removeOnGlobalLayoutListener(listener)
        }
    }
    return rects
}

/** Horizontal placement of a header row's start, middle and end element. */
internal data class SettingsHeaderArrangement(
    val startX: Int,
    val middleX: Int,
    val middleWidth: Int,
    val endX: Int
)

internal object SettingsHeaderCutoutLayout {
    /**
     * Places the elements of a header row of [width]×[height] px, left to right. Without
     * a cutout this matches a Row with a weighted middle. A [cutouts] span (row-local x
     * range of a cutout that overlaps the row vertically, already widened by the gap)
     * pushes the start element right, the end element left, and gives the middle the
     * first free stretch that fits its [middlePreferred] width, else the first that
     * fits [middleMin], else the widest one.
     */
    fun arrange(
        width: Int,
        startWidth: Int,
        endWidth: Int,
        middlePreferred: Int,
        middleMin: Int,
        padStart: Int,
        padEnd: Int,
        cutouts: List<IntRange>
    ): SettingsHeaderArrangement {
        val spans = cutouts.filter { it.last > 0 && it.first < width }.sortedBy { it.first }
        fun overlaps(x: Int, w: Int): IntRange? = if (w <= 0) null else spans.firstOrNull { x < it.last && x + w > it.first }
        var startX = padStart
        while (true) startX = (overlaps(startX, startWidth) ?: break).last
        var endX = width - padEnd - endWidth
        while (true) endX = (overlaps(endX, endWidth) ?: break).first - endWidth
        val regionStart = startX + startWidth
        val regionEnd = max(regionStart, endX)
        val free = mutableListOf<IntRange>()
        var cursor = regionStart
        spans.forEach { span ->
            val blockedStart = max(span.first, regionStart)
            val blockedEnd = min(span.last, regionEnd)
            if (blockedEnd <= blockedStart) return@forEach
            if (blockedStart > cursor) free += cursor until blockedStart
            cursor = max(cursor, blockedEnd)
        }
        if (cursor < regionEnd) free += cursor until regionEnd
        val chosen = free.firstOrNull { it.count() >= middlePreferred }
            ?: free.firstOrNull { it.count() >= middleMin }
            ?: free.maxByOrNull { it.count() }
            ?: (regionStart until regionStart)
        return SettingsHeaderArrangement(startX, chosen.first, chosen.count(), endX)
    }
}

/**
 * The normal-state content of a header row while it shows something else, such as the
 * search field: measured, never placed, so the row keeps the height it has normally.
 */
internal class SettingsHeaderRowReference(
    val padStart: Dp,
    val start: @Composable () -> Unit,
    val middle: @Composable () -> Unit,
    val end: @Composable () -> Unit
)

/**
 * A header row: [start] (Back), [middle] (title or search field) and [end] (actions),
 * vertically centred in at least [minHeight] and at least as tall as [reference] would
 * be. Elements that meet one of [cutouts] (window pixels) are indented sideways; other
 * elements keep their place.
 */
@Composable
internal fun SettingsHeaderRow(
    cutouts: List<Rect>,
    padStart: Dp,
    padEnd: Dp,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    padVertical: Dp = 4.dp,
    reference: SettingsHeaderRowReference? = null,
    start: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    end: @Composable () -> Unit
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var placedHeight by remember { mutableStateOf(0) }
    val track = if (cutouts.isEmpty()) Modifier else Modifier.onPlaced {
        origin = it.positionInWindow()
        placedHeight = it.size.height
    }
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val none: @Composable () -> Unit = {}
    // The reference is only measured; it must not reach accessibility or tests either.
    fun hidden(content: (@Composable () -> Unit)?): @Composable () -> Unit =
        if (content == null) none else ({ Box(Modifier.clearAndSetSemantics {}) { content() } })
    Layout(
        contents = listOf(start, middle, end, hidden(reference?.start), hidden(reference?.middle), hidden(reference?.end)),
        modifier = modifier.then(track)
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val vertical = padVertical.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val rtl = layoutDirection == LayoutDirection.Rtl

        class Measured(val start: List<Placeable>, val middle: List<Placeable>, val end: List<Placeable>,
                       val arrangement: SettingsHeaderArrangement, val height: Int)

        fun measureRow(startM: List<Measurable>, middleM: List<Measurable>, endM: List<Measurable>, pad: Dp): Measured {
            val startPlaceables = startM.map { it.measure(loose) }
            val endPlaceables = endM.map { it.measure(loose) }
            val startWidth = startPlaceables.maxOfOrNull { it.width } ?: 0
            val endWidth = endPlaceables.maxOfOrNull { it.width } ?: 0
            val sideHeight = max(startPlaceables.maxOfOrNull { it.height } ?: 0, endPlaceables.maxOfOrNull { it.height } ?: 0)
            val rowHeight = max(minHeight.roundToPx(), sideHeight + 2 * vertical)
            val spans = cutouts.mapNotNull { rect ->
                val top = rect.top - origin.y
                val bottom = rect.bottom - origin.y
                if (bottom <= 0 || top >= max(rowHeight, placedHeight)) return@mapNotNull null
                val left = (rect.left - origin.x).toInt()
                val right = (rect.right - origin.x).toInt()
                // Arranged left to right as in LTR; RTL mirrors the cutout instead.
                if (rtl) (width - right - gap)..(width - left + gap) else (left - gap)..(right + gap)
            }
            val arrangement = SettingsHeaderCutoutLayout.arrange(
                width = width,
                startWidth = startWidth,
                endWidth = endWidth,
                middlePreferred = middleM.maxOfOrNull { it.maxIntrinsicWidth(rowHeight) } ?: 0,
                middleMin = 120.dp.roundToPx(),
                padStart = pad.roundToPx(),
                padEnd = padEnd.roundToPx(),
                cutouts = spans
            )
            val middlePlaceables = middleM.map {
                it.measure(Constraints(minWidth = arrangement.middleWidth, maxWidth = arrangement.middleWidth))
            }
            val height = max(rowHeight, (middlePlaceables.maxOfOrNull { it.height } ?: 0) + 2 * vertical)
            return Measured(startPlaceables, middlePlaceables, endPlaceables, arrangement, height)
        }

        val row = measureRow(measurables[0], measurables[1], measurables[2], padStart)
        val referenceHeight = reference?.let { measureRow(measurables[3], measurables[4], measurables[5], it.padStart).height } ?: 0
        val height = max(row.height, referenceHeight)
        val arrangement = row.arrangement
        layout(width, height) {
            // placeRelative mirrors in RTL, matching the mirrored cutout spans.
            row.start.forEach { it.placeRelative(arrangement.startX, (height - it.height) / 2) }
            row.middle.forEach { it.placeRelative(arrangement.middleX, (height - it.height) / 2) }
            row.end.forEach { it.placeRelative(arrangement.endX, (height - it.height) / 2) }
        }
    }
}

/**
 * The page header, with [panel] (search results) hanging below it over the page down to
 * the window bottom. The header keeps its size, so the page underneath is neither
 * resized nor scrolled; drawn and hit-tested above its siblings while [panel] shows.
 */
@Composable
internal fun SettingsHeaderWithPanel(
    modifier: Modifier,
    searching: Boolean,
    onClose: () -> Unit,
    panel: (@Composable () -> Unit)?,
    header: @Composable () -> Unit
) {
    val host = LocalView.current
    var headerHeight by remember { mutableStateOf(0) }
    val measure = Modifier.onSizeChanged { headerHeight = it.height }
    if (!searching) {
        Box(modifier.then(measure)) { header() }
        return
    }

    // Search owns both keyboard and accessibility focus until its window closes.
    DisposableEffect(host) {
        val previous = host.importantForAccessibility
        host.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        onDispose { host.importantForAccessibility = previous }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        Spacer(Modifier.height(with(LocalDensity.current) { headerHeight.toDp() }))
        Popup(onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(width).fillMaxHeight()) {
                Box(measure) { header() }
                panel?.invoke()
            }
        }
    }
}

/** Global settings search results, shared by the main page and the page headers. */
@Composable
internal fun SettingsSearchResultList(
    results: List<SettingEntry>,
    onOpen: (SettingEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (results.isEmpty()) {
            SettingsSupportingText(stringResource(R.string.settings_search_no_results))
        } else {
            results.forEach { entry -> SettingSearchResultRow(entry = entry, onClick = { onOpen(entry) }) }
        }
        SettingsBottomSpacer()
    }
}
