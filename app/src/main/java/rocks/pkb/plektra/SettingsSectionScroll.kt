package rocks.pkb.plektra

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A scrolling settings page that lets the thumb jump between its sections, in the
 * spirit of a launcher's alphabet strip.
 *
 * - At rest one quiet, compact dot row sits in a narrow gutter at the right, one dot per
 *   section heading ([SettingsSectionDivider]); the current section's dot is emphasized.
 *   Both edges respond; a pane can limit that ([LocalSectionJumpSides]).
 * - A vertical drag that starts on either track opens the section fan at once:
 *   the page dims and every section title of the page is written out on a soft
 *   curve next to the thumb. Moving up or down jumps straight to the section under
 *   the thumb; pulling inward bends the curve further, neighbours following softly.
 *   Each real section change gives one system haptic tick.
 * - A sideways movement or a tap is left alone, so Android's back gesture, rows and
 *   ordinary scrolling keep working. Release or cancel closes the fan.
 * - Pages with fewer than [MIN_SECTIONS] sections behave like a plain scrolling page.
 *   Dots and fan are decorative for TalkBack, which keeps its heading navigation.
 */
/** Which screen edges carry the section track. Two panes each use their outer edge, so none sits at the fold. */
internal enum class SectionJumpSides { Both, Start, End }

internal val LocalSectionJumpSides = androidx.compose.runtime.staticCompositionLocalOf { SectionJumpSides.Both }

@Composable
internal fun SettingsSectionScrollColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val sections = remember { mutableStateMapOf<String, Float>() }
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val index = remember {
        SettingsSectionIndex { label, level, coordinates ->
            if (level != SettingsHeadingLevel.SECTION) return@SettingsSectionIndex
            val root = contentCoordinates ?: return@SettingsSectionIndex
            if (!coordinates.isAttached || !root.isAttached) return@SettingsSectionIndex
            // Headings report on every scroll frame; only a real move may touch state,
            // otherwise the whole page would recompose while scrolling.
            val y = root.localPositionOf(coordinates, Offset.Zero).y
            if (sections[label] != y) sections[label] = y
        }
    }
    val ordered by remember {
        derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
            sections.entries.sortedBy { it.value }.map { it.key to it.value }
        }
    }
    val density = LocalDensity.current
    val topMargin = with(density) { 8.dp.toPx() }
    // The section you are reading: the last heading at or above the reading anchor.
    val current by remember {
        derivedStateOf {
            val y = readingAnchor(scrollState, topMargin)
            ordered.indexOfLast { it.second <= y }.coerceAtLeast(0)
        }
    }
    // Where the reading anchor stands between two headings, as a continuous section index.
    val progress by remember {
        derivedStateOf {
            val y = readingAnchor(scrollState, topMargin)
            val next = ordered.indexOfFirst { it.second > y }
            when {
                ordered.isEmpty() -> 0f
                next == 0 -> 0f
                next < 0 -> ordered.lastIndex.toFloat()
                else -> {
                    val from = ordered[next - 1].second
                    (next - 1) + ((y - from) / (ordered[next].second - from)).coerceIn(0f, 1f)
                }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReducedMotion()
    // While the fan is open, the section position under the thumb (fractional); null otherwise.
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    // The drop follows the scroll or the thumb freely and settles on its dot once movement stops.
    val indicator = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        snapshotFlow { Triple(progress, scrollState.isScrollInProgress, dragPosition) }.collect { (value, scrolling, thumb) ->
            when {
                thumb != null -> indicator.snapTo(if (reduceMotion) kotlin.math.round(thumb) else thumb)
                reduceMotion -> indicator.snapTo(kotlin.math.floor(value))
                scrolling -> indicator.snapTo(value)
                else -> indicator.animateTo(
                    kotlin.math.floor(value),
                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
    }
    fun sectionScroll(position: Float): Int {
        if (ordered.isEmpty()) return scrollState.value
        val from = kotlin.math.floor(position).toInt().coerceIn(0, ordered.lastIndex)
        val to = (from + 1).coerceAtMost(ordered.lastIndex)
        val y = ordered[from].second + (ordered[to].second - ordered[from].second) * (position - from)
        return (y - topMargin).roundToInt().coerceIn(0, scrollState.maxValue)
    }
    fun scrollToSection(position: Int, animate: Boolean) {
        val target = sectionScroll(position.toFloat())
        scope.launch {
            if (animate && !reduceMotion) scrollState.animateScrollTo(target, tween(220)) else scrollState.scrollTo(target)
        }
    }

    SectionJumpArea(
        modifier = modifier,
        ordered = ordered,
        current = current,
        indicator = { indicator.value },
        sides = LocalSectionJumpSides.current,
        reduceMotion = reduceMotion,
        // Dragging scrolls continuously between sections, like scrolling by hand.
        onDrag = { position ->
            dragPosition = position
            // Synchronous and allocation-free: no coroutine per pointer move.
            scrollState.dispatchRawDelta((sectionScroll(position) - scrollState.value).toFloat())
        },
        // Releasing settles page and drop on the section of the active marker.
        onRelease = { section ->
            dragPosition = section.toFloat()
            scope.launch {
                val target = sectionScroll(section.toFloat())
                if (reduceMotion) scrollState.scrollTo(target)
                else scrollState.animateScrollTo(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                dragPosition = null
            }
        }
    ) {
        CompositionLocalProvider(LocalSettingsSectionIndex provides index) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // A narrow gutter keeps the dot row clear of the rows' content.
                    .padding(end = if (ordered.size >= MIN_SECTIONS) DOT_GUTTER else 0.dp)
                    // onPlaced fires on relayout only, not on every scroll frame.
                    .onPlaced { contentCoordinates = it },
                content = content
            )
        }
    }
}

/** The content with the edge tracks and, while dragging, the section fan on top. */
@Composable
private fun SectionJumpArea(
    modifier: Modifier,
    ordered: List<Pair<String, Float>>,
    current: Int,
    indicator: () -> Float,
    sides: SectionJumpSides,
    reduceMotion: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (Int) -> Unit,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val trackPx = with(density) { TRACK_WIDTH.toPx() }
    val enabled = ordered.size >= MIN_SECTIONS
    // Per-frame drag values are read only while laying out and drawing the fan,
    // so moving the thumb neither recomposes the fan nor the page.
    var drag by remember { mutableStateOf<FanDrag?>(null) }
    val open by remember { derivedStateOf { drag != null } }
    val reveal = remember { Animatable(0f) }
    // The fan opens and closes softly; reduced motion shows and hides it at once.
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        if (reduceMotion) reveal.snapTo(target)
        else reveal.animateTo(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
    }
    // The last drag, kept for the fading fan; a plain holder, so writing it never recomposes.
    val lastDragHolder = remember { arrayOfNulls<FanDrag>(1) }
    var fanSide by remember { mutableStateOf(FanSide.End) }
    // After release the active marker settles on its section while the fan fades out.
    val settle = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) return@LaunchedEffect
        val from = lastDragHolder[0]?.position ?: return@LaunchedEffect
        settle.snapTo(from)
        if (!reduceMotion) settle.animateTo(kotlin.math.round(from), spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }
    val titles = remember(ordered) { ordered.map { it.first } }
    val fanVisible by remember { derivedStateOf { open || reveal.value > 0.001f } }

    Box(
        modifier = modifier.pointerInput(enabled, sides) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val side = when {
                    sides != SectionJumpSides.End && down.position.x <= trackPx -> FanSide.Start
                    sides != SectionJumpSides.Start && down.position.x >= size.width - trackPx -> FanSide.End
                    else -> return@awaitEachGesture
                }
                // A vertical move starts the fan; a sideways one belongs to Back or the page.
                var startY: Float
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.first()
                    if (!change.pressed) return@awaitEachGesture
                    val delta = change.position - down.position
                    if (abs(delta.y) > touchSlop && abs(delta.y) >= abs(delta.x)) {
                        change.consume()
                        startY = change.position.y.coerceIn(0f, size.height.toFloat())
                        break
                    }
                    if (abs(delta.x) > touchSlop) return@awaitEachGesture
                }
                val count = ordered.size
                fun sectionAt(y: Float): Int = railIndex(y, size.height.toFloat(), count, density)
                // The fractional section under the thumb, measured along the marker rows.
                fun positionAt(y: Float): Float {
                    val h = size.height.toFloat()
                    val first = railY(0, count, h, density)
                    val step = if (count > 1) railY(1, count, h, density) - first else 1f
                    return ((y - first) / step).coerceIn(0f, (count - 1).toFloat())
                }
                var position = sectionAt(startY)
                fanSide = side
                drag = FanDrag(side, down.position.x, startY, 0f, position, positionAt(startY))
                lastDragHolder[0] = drag
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onDrag(positionAt(startY))
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.first()
                        if (!change.pressed) break
                        change.consume()
                        val y = change.position.y.coerceIn(0f, size.height.toFloat())
                        val inward = when (side) {
                            FanSide.End -> down.position.x - change.position.x
                            FanSide.Start -> change.position.x - down.position.x
                        }.coerceAtLeast(0f)
                        val next = sectionAt(y)
                        if (next != position) {
                            position = next
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        val fraction = positionAt(y)
                        onDrag(fraction)
                        drag = drag?.copy(y = y, pull = inward, active = position, position = fraction)
                        lastDragHolder[0] = drag
                    }
                } finally {
                    onRelease(position)
                    drag = null
                }
            }
        }
    ) {
        content()
        // One calm dot row per column, in its own gutter on the right; the left edge still works.

        if (fanVisible && lastDragHolder[0] != null) {
            SectionFan(
                titles = titles,
                side = fanSide,
                drag = { drag ?: lastDragHolder[0]!! },
                reveal = { reveal.value },
                // The active marker follows the thumb freely, then settles on release.
                activePosition = { drag?.position ?: settle.value },
                modifier = Modifier.fillMaxSize()
            )
        }
        // The dot row stays visible above the fan, so the drop shows where the thumb is.
        if (enabled) DotTrack(ordered.size, indicator, FanSide.End, Modifier.fillMaxSize())
    }
}

/** Rail position of section [i]: evenly spread over the usable height. */
private fun railY(i: Int, count: Int, height: Float, density: androidx.compose.ui.unit.Density): Float {
    val margin = with(density) { RAIL_MARGIN.toPx() }
    // Like a launcher's alphabet, the sections span the whole edge, so the thumb finds each one.
    val usable = (height - 2 * margin).coerceAtLeast(1f)
    val step = usable / count
    return margin + step * (i + 0.5f)
}

private fun railIndex(y: Float, height: Float, count: Int, density: androidx.compose.ui.unit.Density): Int =
    (0 until count).minByOrNull { abs(railY(it, count, height, density) - y) } ?: 0

/**
 * The quiet resting dots: a compact, evenly spaced group centred on the edge, inside the page
 * margin so it never touches text. The accent drop marks the current section. Between two
 * sections it sticks to its dot, then stretches like an hourglass, two drops joined by a
 * pinched neck, and hops over; [indicator] is read while drawing, so scrolling only redraws.
 */
@Composable
private fun DotTrack(count: Int, indicator: () -> Float, side: FanSide, modifier: Modifier) {
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val active = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics { }) {
        val x = if (side == FanSide.Start) DOT_INSET.toPx() else size.width - DOT_INSET.toPx()
        val spacing = DOT_SPACING.toPx()
        val top = size.height / 2 - spacing * (count - 1) / 2
        val small = 2.dp.toPx()
        val big = 3.dp.toPx()
        for (i in 0 until count) drawCircle(dot, small, Offset(x, top + spacing * i))

        val value = indicator().coerceIn(0f, (count - 1).toFloat())
        val from = kotlin.math.floor(value).toInt().coerceAtMost(count - 1)
        val t = value - from
        // The head leaves first, the tail follows late: sticky at both ends, a quick hop between.
        // It hops just before the next heading reaches the reading anchor, where the section changes.
        val head = from + smoothStep(0.55f, 0.92f, t)
        val tail = from + smoothStep(0.75f, 1f, t)
        val headY = top + spacing * head
        val tailY = top + spacing * tail
        val stretch = ((head - tail)).coerceIn(0f, 1f)
        val headR = big - (big - small) * stretch * 0.4f
        val tailR = big - (big - small) * stretch * 0.7f
        if (headY - tailY > 0.5f) {
            // Hourglass neck: concave sides pinch towards the middle as the drop stretches.
            val midY = (headY + tailY) / 2
            val neck = minOf(headR, tailR) * (1f - 0.75f * stretch)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x - tailR, tailY)
                quadraticBezierTo(x - neck, midY, x - headR, headY)
                lineTo(x + headR, headY)
                quadraticBezierTo(x + neck, midY, x + tailR, tailY)
                close()
            }
            drawPath(path, active)
        }
        drawCircle(active, tailR, Offset(x, tailY))
        drawCircle(active, headR, Offset(x, headY))
    }
}

/**
 * The line the reader is at: the top of the page at the very top, growing to the upper third of
 * the view as soon as one scrolls. So the top shows the first section and the end the one being read.
 */
private fun readingAnchor(scroll: ScrollState, topMargin: Float): Float {
    val third = maxOf(topMargin * 2, scroll.viewportSize / 3f)
    return scroll.value + minOf(third, topMargin * 2 + scroll.value)
}

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/**
 * The section fan: a dimmed page and all section markers on a curve that bulges toward the
 * thumb. One shared pill carries the active marker and glides to the next one like the dot's
 * drop: it sticks, stretches into an hourglass and hops over, while the markers grow and shrink
 * with it. The quiet markers sit on calm labels; all titles stay horizontal for easy reading.
 */
@Composable
private fun SectionFan(
    titles: List<String>,
    side: FanSide,
    drag: () -> FanDrag,
    reveal: () -> Float,
    activePosition: () -> Float,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val scrim = MaterialTheme.colorScheme.scrim
    val pillColor = MaterialTheme.colorScheme.primaryContainer
    val onPill = MaterialTheme.colorScheme.onPrimaryContainer
    val text = MaterialTheme.colorScheme.onSurface
    // Quiet markers stay readable over any row below them.
    val labelColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    val count = titles.size
    val geometry = remember { FanGeometry() }
    fun closeness(i: Int) = (1f - abs(i - activePosition())).coerceIn(0f, 1f)
    // Text colour only changes when another marker becomes the nearest one.
    val nearest by remember { derivedStateOf { kotlin.math.round(activePosition()).toInt() } }
    Box(modifier.clearAndSetSemantics { }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal() }.background(scrim.copy(alpha = 0.62f)))
        Layout(
            contents = titles.mapIndexed { i, title ->
                @Composable {
                    Text(
                        text = title,
                        style = SettingsTypography.rowTitle(),
                        fontWeight = FontWeight.Medium,
                        color = if (i == nearest) onPill else text,
                        textAlign = if (side == FanSide.End) TextAlign.End else TextAlign.Start,
                        maxLines = 2,
                        modifier = Modifier
                            .drawBehind {
                                // The quiet label fades out where the shared pill arrives.
                                // A marker the pill touches has no label of its own, so nothing doubles.
                                if (closeness(i) > 0.01f) return@drawBehind
                                drawRoundRect(
                                    labelColor,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawGooeyPill(geometry, activePosition(), pillColor, reveal()) }
        ) { measurables, constraints ->
            val drag = drag()
            val reveal = reveal()
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val maxLabel = (width * 0.6f).roundToInt()
            val placeables = measurables.map { it.single().measure(Constraints(maxWidth = maxLabel)) }
            val sigma = (height / count).coerceAtMost(with(density) { RAIL_MAX_STEP.toPx() }) * 2.2f
            // The bulge grows when the thumb pulls inward; neighbours follow along the curve.
            val amplitude = (with(density) { FAN_BASE_BULGE.toPx() } + drag.pull * 0.9f)
                .coerceAtMost(width * 0.45f) * reveal
            val edge = with(density) { FAN_EDGE.toPx() }
            geometry.resize(count)
            geometry.weights = FloatArray(count)
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, p ->
                    val railY = railY(i, count, height, density)
                    val weight = exp(-((railY - drag.y) / sigma).let { it * it })
                    geometry.weights[i] = weight
                    val inset = edge + amplitude * weight
                    val x = if (drag.side == FanSide.End) width - inset - p.width else inset
                    val y = (railY - p.height / 2f).coerceIn(0f, (height - p.height).coerceAtLeast(0f))
                    // The marker's own box; the pill scales it like the marker while drawing.
                    geometry.set(i, x, y, p.width.toFloat(), p.height.toFloat())
                    geometry.endAnchored = drag.side == FanSide.End
                    p.placeWithLayer(x.roundToInt(), y.roundToInt()) {
                        val c = closeness(i)
                        alpha = (0.55f + 0.45f * maxOf(weight, c)) * reveal
                        val scale = markerScale(weight, c)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            if (drag.side == FanSide.End) 1f else 0f, 0.5f
                        )
                    }
                }
            }
        }
    }
}

/** Pill rectangles of each marker, filled during layout and read while drawing. */
private class FanGeometry {
    var rects = FloatArray(0)
    var weights = FloatArray(0)
    var endAnchored = true
    fun resize(count: Int) { if (rects.size != count * 4) rects = FloatArray(count * 4) }
    fun set(i: Int, left: Float, top: Float, width: Float, height: Float) {
        rects[i * 4] = left; rects[i * 4 + 1] = top; rects[i * 4 + 2] = width; rects[i * 4 + 3] = height
    }
    val count get() = rects.size / 4
}

/** A marker's scale: quiet markers shrink with distance, the active one grows. */
private fun markerScale(weight: Float, closeness: Float): Float =
    (0.9f + 0.1f * weight) * (1f - closeness) + ACTIVE_SCALE * closeness

/** The shared active pill: between two markers it sticks, pinches like an hourglass and hops. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGooeyPill(
    geometry: FanGeometry,
    position: Float,
    color: androidx.compose.ui.graphics.Color,
    reveal: Float
) {
    val n = geometry.count
    if (n == 0) return
    val p = position.coerceIn(0f, (n - 1).toFloat())
    val from = kotlin.math.floor(p).toInt().coerceAtMost(n - 1)
    val to = (from + 1).coerceAtMost(n - 1)
    val t = p - from
    // Each marker's box at its current scale, anchored at the fan's edge like the marker itself.
    fun box(i: Int, out: FloatArray) {
        val r = geometry.rects
        val scale = markerScale(geometry.weights.getOrElse(i) { 0f }, (1f - abs(i - p)).coerceIn(0f, 1f))
        val w = r[i * 4 + 2] * scale
        val h = r[i * 4 + 3] * scale
        out[0] = if (geometry.endAnchored) r[i * 4] + r[i * 4 + 2] - w else r[i * 4]
        out[1] = r[i * 4 + 1] + r[i * 4 + 3] / 2 - h / 2
        out[2] = w; out[3] = h
    }
    val a = FloatArray(4).also { box(from, it) }
    val b = FloatArray(4).also { box(to, it) }
    fun mix(k: Float) = FloatArray(4) { j -> a[j] + (b[j] - a[j]) * k }
    // The front reaches the next marker by half way; the back sticks to the old one until then.
    val head = mix(smoothStep(0f, 0.55f, t))
    val tail = mix(smoothStep(0.45f, 1f, t))
    val c = color.copy(alpha = color.alpha * reveal)
    fun pill(q: FloatArray) = drawRoundRect(
        c,
        topLeft = Offset(q[0], q[1]),
        size = androidx.compose.ui.geometry.Size(q[2], q[3]),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(q[3] / 2)
    )
    // One closed drop: the upper and the lower pill joined by a body whose left and right
    // edges curve inward to a clear waist, like an hourglass, while the drop is stretched.
    val upper = if (head[1] <= tail[1]) head else tail
    val lower = if (upper === head) tail else head
    val uy = upper[1] + upper[3] / 2
    val ly = lower[1] + lower[3] / 2
    val radius = minOf(upper[3], lower[3]) / 2
    val stretch = ((ly - uy) / (radius * 4f)).coerceIn(0f, 1f)
    // Stretched, both ends slim a little so the waist reads as an hourglass.
    fun slim(q: FloatArray) {
        val k = 1f - 0.18f * stretch
        val w = q[2] * k; val h = q[3] * k
        q[0] += (q[2] - w) / 2; q[1] += (q[3] - h) / 2; q[2] = w; q[3] = h
    }
    slim(upper); slim(lower)
    if (ly - uy > 1f) {
        val r = minOf(upper[3], lower[3]) / 2
        val midY = (uy + ly) / 2
        val leftMid = (upper[0] + lower[0]) / 2
        val rightMid = (upper[0] + upper[2] + lower[0] + lower[2]) / 2
        val width = rightMid - leftMid
        val waist = (width * (1f - 0.75f * stretch)).coerceAtLeast(r * 1.1f)
        val pinch = ((width - waist) / 2).coerceAtLeast(0f)
        // Smooth edges: they leave the pills vertically, pass the waist vertically and join the
        // other pill vertically, so the drop reads as one soft hourglass.
        val upperUpY = uy + (midY - uy) * 0.55f
        val waistUpY = midY - (midY - uy) * 0.45f
        val waistDownY = midY + (ly - midY) * 0.45f
        val lowerUpY = ly - (ly - midY) * 0.55f
        val leftWaist = leftMid + pinch
        val rightWaist = rightMid - pinch
        val path = androidx.compose.ui.graphics.Path().apply {
            val ul = upper[0] + r; val ur = upper[0] + upper[2] - r
            val ll = lower[0] + r; val lr = lower[0] + lower[2] - r
            moveTo(ul, uy)
            cubicTo(ul, upperUpY, leftWaist, waistUpY, leftWaist, midY)
            cubicTo(leftWaist, waistDownY, ll, lowerUpY, ll, ly)
            lineTo(lr, ly)
            cubicTo(lr, lowerUpY, rightWaist, waistDownY, rightWaist, midY)
            cubicTo(rightWaist, waistUpY, ur, upperUpY, ur, uy)
            close()
        }
        drawPath(path, c)
    }
    pill(tail)
    pill(head)
}

/** Animations are off when the system animator scale is 0. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private enum class FanSide { Start, End }

private data class FanDrag(
    val side: FanSide,
    val startX: Float,
    val y: Float,
    val pull: Float,
    val active: Int,
    /** The fractional section under the thumb. */
    val position: Float
)

private const val MIN_SECTIONS = 3
/** The touch track at each edge; Android's back gesture is horizontal and stays free. */
private val TRACK_WIDTH = 28.dp
private val DOT_INSET = 6.dp
private val DOT_GUTTER = 10.dp
private val DOT_SPACING = 14.dp
private val RAIL_MARGIN = 32.dp
private val RAIL_MAX_STEP = 56.dp
private val FAN_EDGE = 28.dp
private val FAN_BASE_BULGE = 36.dp
/** How much the active marker grows. */
private const val ACTIVE_SCALE = 1.4f
