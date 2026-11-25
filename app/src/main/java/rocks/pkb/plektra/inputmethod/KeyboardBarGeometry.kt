package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Paint
import android.graphics.PathMeasure
import rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts
import rocks.pkb.plektra.inputmethod.ui.ModifierLedLayout
import rocks.pkb.plektra.inputmethod.ui.ModifierLedSegment
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Rect
import android.graphics.RectF
import rocks.pkb.plektra.T2eCornerCalibration
import rocks.pkb.plektra.T2eCornerGeometry
import rocks.pkb.plektra.inputmethod.ui.LedStatusView
import rocks.pkb.plektra.SettingsManager
import kotlin.math.PI
import kotlin.math.hypot

/** Shared display boundary and the surfaces and rails owned by the outer row slots. */
internal class KeyboardBarGeometry(private val context: Context) {
    data class OuterSurface(val bounds: RectF, val radius: Float, val borderHalfWidth: Float = 0f)
    private var dirty = true
    private var preferencesDirty = true
    private var preparedWidth = Float.NaN
    private var preparedHeight = Float.NaN
    var radii: Pair<Int, Int>? = null
        set(value) { if (field != value) { field = value; dirty = true } }
    var ledsVisible = false
        set(value) { if (field != value) { field = value; dirty = true } }
    var hardwareTopRounding = true
        set(value) { if (field != value) { field = value; dirty = true } }
    var ledRowTop: Float? = null
        set(value) { if (field != value) { field = value; dirty = true } }
    var ledRowRadius = 0f
        set(value) { if (field != value) { field = value; dirty = true } }
    var leftSurface: OuterSurface? = null
        set(value) { if (field != value) { field = value; dirty = true } }
    var rightSurface: OuterSurface? = null
        set(value) { if (field != value) { field = value; dirty = true } }
    var ledRadii: Pair<Int, Int>? = null
        set(value) { if (field != value) { field = value; dirty = true } }
    var ledLayout: ModifierLedLayout = ModifierLedLayouts.DEFAULT
        set(value) { if (field != value) { field = value; dirty = true } }
    var ledContentHeight = 8f
        set(value) { if (field != value) { field = value; dirty = true } }
    var ledTopPadding = 1f
        set(value) { if (field != value) { field = value; dirty = true } }
    var followDisplayContour = true
        set(value) { if (field != value) { field = value; dirty = true } }
    var revision = 0L
        private set
    var surfaceBuildCount = 0
        private set
    var placementCount = 0
        private set
    var railBuildCount = 0
        private set
    var touchRegionBuildCount = 0
        private set
    var geometryBuildCount = 0
        private set
    private var calibrationRaw: String? = null
    private var calibrationSnapshot = T2eCornerCalibration.read(context)
    private var calibrationInitialized = false
    private var topMultiplier = 1
    var minimumContentScale = 1f
        private set
    private var stateKey: List<Any?>? = null
    private val displayPaths = mutableMapOf<Float, Path>()
    private val railPaths = mutableMapOf<ModifierLedSegment, Path>()
    private val railSections = mutableMapOf<ModifierLedSegment, Path>()
    private val innerContours = mutableMapOf<Boolean, List<PointF>>()
    private var bottomContour: Path? = null
    private var exclusion: Path? = null
    private var contentRegion: Region? = null
    private var ledRegion: Region? = null
    private val surfaces = mutableMapOf<List<Any>, PreparedSurface>()
    val calibration: T2eCornerCalibration get() = calibrationSnapshot

    private val preferences = SettingsManager.getPreferences(context)
    private val preferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener
    init {
        val owner = java.lang.ref.WeakReference(this)
        preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == T2eCornerCalibration.KEY || key == SettingsManager.KEY_TITAN2_ELITE_TOP_CORNER_MULTIPLIER ||
                key == SettingsManager.KEY_TITAN2_ELITE_MAX_ICON_SHRINK) {
                owner.get()?.let { it.dirty = true; it.preferencesDirty = true }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun refreshSettings(forceCalibration: Boolean = false) {
        val raw = SettingsManager.getPreferences(context).getString(T2eCornerCalibration.KEY, null)
        if (!calibrationInitialized || forceCalibration || raw != calibrationRaw) {
            calibrationSnapshot = T2eCornerCalibration.read(context)
            calibrationRaw = raw
            calibrationInitialized = true
        }
        topMultiplier = SettingsManager.getTitan2EliteTopCornerMultiplier(context)
        minimumContentScale = 1f - SettingsManager.getTitan2EliteMaxIconShrink(context) / 100f
        if (forceCalibration) dirty = true
    }

    /**
     * Re-reads the calibration after the panel identity may have changed, for example when a
     * detection of [DisplayCorners.hasKnownRoundedPanel] depends on hardware that enumerates
     * after the IME started. Only the default follows the panel; a saved calibration reads the
     * same. Returns whether the contour changed.
     */
    fun refreshPanelDefault(): Boolean {
        val current = T2eCornerCalibration.read(context)
        if (current == calibrationSnapshot) return false
        calibrationSnapshot = current
        dirty = true
        return true
    }

    fun prepare(width: Float, height: Float) {
        if (!dirty && width == preparedWidth && height == preparedHeight) return
        if (preferencesDirty) { refreshSettings(); preferencesDirty = false }
        preparedWidth = width; preparedHeight = height; dirty = false
        fun slot(value: OuterSurface?): List<Any>? = value?.let {
            listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom, it.radius, it.borderHalfWidth)
        }
        val key = listOf(width, height, radii, ledsVisible, hardwareTopRounding, ledRowTop, ledRowRadius,
            slot(leftSurface), slot(rightSurface), ledRadii, ledLayout, ledContentHeight, ledTopPadding,
            followDisplayContour, calibrationSnapshot, topMultiplier)
        if (key == stateKey) return
        stateKey = key
        revision++
        geometryBuildCount++
        displayPaths.clear(); railPaths.clear(); railSections.clear(); innerContours.clear(); surfaces.clear()
        bottomContour = null; exclusion = null; contentRegion = null; ledRegion = null
    }

    data class PreparedSurface(val path: Path, val region: Region, val center: PointF,
        val bounds: RectF, val bands: List<Rect>)
    data class Placement(val x: Float, val y: Float, val scale: Float, val fits: Boolean = true)

    fun preparedSurface(width: Float, height: Float, bounds: RectF, radius: Float,
                        left: Boolean, right: Boolean, strokeInset: Float = 0f): PreparedSurface {
        prepare(width, height)
        val key = listOf<Any>(bounds.left, bounds.top, bounds.right, bounds.bottom, radius, left, right, strokeInset)
        surfaces[key]?.let { return it }
        val path = buildSurfacePath(width, height, bounds, radius, left, right, strokeInset)
        val region = Region().apply { setPath(path, Region(kotlin.math.floor(bounds.left).toInt(),
            kotlin.math.floor(bounds.top).toInt(), kotlin.math.ceil(bounds.right).toInt(), kotlin.math.ceil(bounds.bottom).toInt())) }
        val bands = ArrayList<Rect>()
        val iterator = RegionIterator(region)
        val rectangle = Rect()
        while (iterator.next(rectangle)) bands += Rect(rectangle)
        check(bands.zipWithNext().none { (a, b) -> b.top < a.bottom }) { "Key surface must have one horizontal span per band" }
        surfaceBuildCount++
        return PreparedSurface(path, region, centroid(region, PointF(bounds.centerX(), bounds.centerY())),
            RectF(bounds), bands).also { surfaces[key] = it }
    }

    /** Key surfaces have one contiguous horizontal span per band; candidate count is bounded. */
    fun placeInk(surface: PreparedSurface, inkWidth: Float, inkHeight: Float, preferred: PointF = surface.center,
                 gap: Float = 0f, minimumScale: Float = minimumContentScale,
                 obstacles: List<RectF> = emptyList()): Placement {
        placementCount++
        fun atScale(scale: Float): Placement? {
            val halfWidth = inkWidth * scale / 2f + gap
            val halfHeight = inkHeight * scale / 2f + gap
            val minY = surface.bounds.top + halfHeight
            val maxY = surface.bounds.bottom - halfHeight
            if (minY > maxY) return null
            fun candidate(y: Float): Placement? {
                val top = kotlin.math.floor(y - halfHeight).toInt()
                val bottom = kotlin.math.ceil(y + halfHeight).toInt()
                var left = surface.bounds.left + halfWidth
                var right = surface.bounds.right - halfWidth
                var covered = top
                for (band in surface.bands) {
                    if (band.bottom <= top || band.top >= bottom) continue
                    if (band.top > covered) return null
                    covered = maxOf(covered, band.bottom)
                    left = maxOf(left, band.left + halfWidth)
                    right = minOf(right, band.right - halfWidth)
                }
                if (covered < bottom || left > right) return null
                var x = preferred.x.coerceIn(left, right)
                for (obstacle in obstacles) {
                    if (y + halfHeight <= obstacle.top || y - halfHeight >= obstacle.bottom) continue
                    if (x + halfWidth <= obstacle.left || x - halfWidth >= obstacle.right) continue
                    val before = obstacle.left - halfWidth
                    val after = obstacle.right + halfWidth
                    x = when {
                        before >= left && after <= right -> if (kotlin.math.abs(before - preferred.x) <= kotlin.math.abs(after - preferred.x)) before else after
                        before >= left -> before
                        after <= right -> after
                        else -> return null
                    }
                }
                return Placement(x, y, scale)
            }
            val desiredY = preferred.y.coerceIn(minY, maxY)
            candidate(desiredY)?.let { if (kotlin.math.abs(it.x - preferred.x) < 0.5f) return it }
            val candidates = ArrayList<Float>()
            candidates += desiredY
            for (index in 0..16) candidates += minY + (maxY - minY) * index / 16f
            candidates += surface.bands.flatMap { listOf(it.top + halfHeight, it.bottom - halfHeight) }
                .filter { it in minY..maxY }.distinct().sortedBy { kotlin.math.abs(it - desiredY) }.take(16)
            for (obstacle in obstacles) {
                if (obstacle.top - halfHeight in minY..maxY) candidates += obstacle.top - halfHeight
                if (obstacle.bottom + halfHeight in minY..maxY) candidates += obstacle.bottom + halfHeight
            }
            return candidates.mapNotNull(::candidate).minByOrNull {
                (it.x - preferred.x) * (it.x - preferred.x) + (it.y - preferred.y) * (it.y - preferred.y)
            }
        }
        atScale(1f)?.let { return it }
        val minimum = atScale(minimumScale)
        if (minimum == null) {
            fun boundedCenter(preferred: Float, start: Float, end: Float, extent: Float): Float =
                if (extent <= end - start) preferred.coerceIn(start + extent / 2f, end - extent / 2f)
                else (start + end) / 2f
            return Placement(
                boundedCenter(preferred.x, surface.bounds.left, surface.bounds.right, inkWidth * minimumScale),
                boundedCenter(preferred.y, surface.bounds.top, surface.bounds.bottom, inkHeight * minimumScale),
                minimumScale, false
            )
        }
        var low = minimumScale
        var high = 1f
        var best: Placement = minimum
        repeat(12) {
            val middle = (low + high) / 2f
            val placement = atScale(middle)
            if (placement == null) high = middle else { low = middle; best = placement }
        }
        return best
    }

    fun needsSurface(width: Float, height: Float, bounds: RectF, left: Boolean, right: Boolean): Boolean {
        if (ownsLedSurface(bounds, left, right)) return true
        val corners = radii ?: return false
        val calibration = calibrationSnapshot
        if (bounds.bottom < height - maxOf(corners.first, corners.second) * calibration.size + calibration.shiftYPx) return false
        val leftBoundary = if (bounds.left < corners.first * calibration.size)
            T2eCornerGeometry.atY(corners.first.toFloat(), height, bounds.bottom - calibration.shiftYPx, calibration).x + calibration.shiftXPx else 0f
        val rightBoundary = if (bounds.right > width - corners.second * calibration.size)
            width - T2eCornerGeometry.atY(corners.second.toFloat(), height, bounds.bottom - calibration.shiftYPx, calibration).x + calibration.shiftXPx else width
        return bounds.left < leftBoundary || bounds.right > rightBoundary
    }

    fun surfacePath(width: Float, height: Float, bounds: RectF, radius: Float,
                    left: Boolean, right: Boolean, strokeInset: Float = 0f): Path =
        Path(preparedSurface(width, height, bounds, radius, left, right, strokeInset).path)

    fun displayPathRef(width: Float, height: Float, strokeInset: Float = 0f): Path? {
        prepare(width, height)
        if (radii == null) return null
        return displayPaths.getOrPut(strokeInset) { buildDisplayPath(width, height, strokeInset)!! }
    }
    fun displayPath(width: Float, height: Float, strokeInset: Float = 0f): Path? =
        displayPathRef(width, height, strokeInset)?.let(::Path)

    fun bottomContourPathRef(width: Float, height: Float): Path? {
        prepare(width, height)
        val corners = radii ?: return null
        if (bottomContour == null) bottomContour = T2eCornerGeometry.bottomContourPath(width, height,
            corners.first.toFloat(), corners.second.toFloat(), calibrationSnapshot)
        return bottomContour
    }

    fun ledRailPath(width: Float, height: Float, segment: ModifierLedSegment): Path {
        prepare(width, height)
        return Path(railPaths.getOrPut(segment) { railBuildCount++; buildLedRailPath(width, height, segment) })
    }

    fun ledSectionPath(width: Float, height: Float, segment: ModifierLedSegment): Path {
        prepare(width, height)
        return railSections.getOrPut(segment) {
            val measure = PathMeasure(ledRailPath(width, height, segment), false)
            val divider = (context.resources.displayMetrics.density + ledStrokeWidth(segment)) / 2f
            val range = if (followDisplayContour && ledLayout == ModifierLedLayouts.TITAN_2_ELITE &&
                segment.x >= 0.5f && segment.y > 0.5f) {
                if (segment.state == rocks.pkb.plektra.inputmethod.ui.ModifierLedState.SHIFT)
                    0f to measure.length / 2f - divider else measure.length / 2f + divider to measure.length
            } else 0f to measure.length
            Path().also { measure.getSegment(range.first, range.second, it, true) }
        }
    }

    fun ledExclusionPath(width: Float, height: Float, extraClearance: Float = 0f): Path {
        prepare(width, height)
        if (extraClearance != 0f) return buildLedExclusionPath(width, height, extraClearance)
        if (exclusion == null) exclusion = buildLedExclusionPath(width, height, 0f)
        return Path(exclusion!!)
    }

    fun ledContains(width: Float, height: Float, x: Float, y: Float): Boolean {
        prepare(width, height)
        if (ledRegion == null) ledRegion = Region().apply {
            touchRegionBuildCount++
            setPath(ledExclusionPath(width, height), Region(0, 0, width.toInt(), height.toInt()))
        }
        return ledRegion!!.contains(kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt())
    }

    /** The physical display boundary is independent of LED visibility. */
    private fun buildDisplayPath(width: Float, height: Float, strokeInset: Float = 0f): Path? {
        val corners = radii ?: return null
        val calibration = calibrationSnapshot
        return T2eCornerGeometry.path(width, height, corners.first.toFloat(), corners.second.toFloat(),
            calibration, strokeInset).apply {
            addRect(corners.first * calibration.size, -height,
                width - corners.second * calibration.size, height - strokeInset, Path.Direction.CW)
        }
    }

    fun contentPath(width: Float, height: Float, strokeInset: Float = 0f): Path? =
        displayPath(width, height, strokeInset)

    fun contains(width: Float, height: Float, x: Float, y: Float): Boolean {
        prepare(width, height)
        if (radii == null) return true
        if (contentRegion == null) contentRegion = Region().apply {
            touchRegionBuildCount++
            setPath(displayPath(width, height)!!, Region(0, 0, width.toInt(), height.toInt()))
        }
        return contentRegion!!.contains(kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt()) &&
            (!ledsVisible || !ledContains(width, height, x, y))
    }

    fun reservedLedHeight(straightStripHeight: Int): Int =
        if (followDisplayContour && (radii != null || ledsVisible)) 0 else straightStripHeight

    data class Transition(val start: PointF, val firstHandle: PointF, val secondHandle: PointF, val join: PointF)

    fun ledStrokeWidth(segment: ModifierLedSegment): Float =
        if (followDisplayContour && ledLayout == ModifierLedLayouts.TITAN_2_ELITE)
            LedStatusView.CONTOUR_LED_STROKE_DP * context.resources.displayMetrics.density
        else segment.height * ledContentHeight

    private fun sideRails(left: Boolean) = ledLayout.segments.filter { (it.x < 0.5f) == left }
        .distinctBy { it.y }.sortedBy { it.y }

    private fun buttonInset(left: Boolean): Float {
        val density = context.resources.displayMetrics.density
        val rails = sideRails(left)
        return density + buttonLedClearancePx() + rails.sumOf { ledStrokeWidth(it).toDouble() }.toFloat() +
            (rails.size - 1).coerceAtLeast(0) * LedStatusView.CONTOUR_LED_GAP_DP * density
    }

    private fun buttonLedClearancePx(): Float =
        if (ledLayout == ModifierLedLayouts.TITAN_2_ELITE)
            LedStatusView.CONTOUR_BUTTON_GAP_DP * context.resources.displayMetrics.density
        else 1f

    private fun outerSurface(width: Float, height: Float, left: Boolean): OuterSurface {
        (if (left) leftSurface else rightSurface)?.let { return it }
        val calibration = calibrationSnapshot
        val corners = ledRadii ?: radii ?: (0 to 0)
        val top = ledRowTop ?: height - maxOf(corners.first, corners.second) * calibration.size + calibration.shiftYPx
        val segments = ledLayout.segments.filter { (it.x < 0.5f) == left }
        val edge = if (ledLayout == ModifierLedLayouts.TITAN_2_ELITE) {
            // Standalone rails end at the display curve instead of extending into a logical key slot.
            val extent = (if (left) corners.first else corners.second) * calibration.size
            (if (left) extent else width - extent) + calibration.shiftXPx
        } else {
            (if (left) segments.maxOf { it.x + it.width } else segments.minOf { it.x }) * width
        }
        return OuterSurface(RectF(if (left) 0f else edge, top, if (left) edge else width, height), ledRowRadius)
    }

    private fun buildLedRailPath(width: Float, height: Float, segment: ModifierLedSegment): Path {
        val corners = ledRadii ?: radii ?: return Path()
        val stroke = ledStrokeWidth(segment)
        if (!followDisplayContour) {
            val calibration = calibrationSnapshot
            val centerY = height - calibration.offsetPx -
                (1f - segment.y - segment.height / 2f) * ledContentHeight - ledTopPadding + calibration.shiftYPx
            val left = T2eCornerGeometry.atY(corners.first.toFloat(), height,
                centerY - calibration.shiftYPx, calibration, stroke / 2f).x + calibration.shiftXPx
            val right = width - T2eCornerGeometry.atY(corners.second.toFloat(), height,
                centerY - calibration.shiftYPx, calibration, stroke / 2f).x + calibration.shiftXPx
            return Path().apply {
                moveTo(left + (right - left) * segment.x + stroke / 2f, centerY)
                lineTo(left + (right - left) * (segment.x + segment.width) - stroke / 2f, centerY)
            }
        }
        val left = segment.x < 0.5f
        val surface = outerSurface(width, height, left)
        val gap = LedStatusView.CONTOUR_LED_GAP_DP * context.resources.displayMetrics.density
        val rails = sideRails(left)
        val index = rails.indexOfFirst { it.y == segment.y }
        val preceding = rails.take(index).sumOf { (ledStrokeWidth(it) + gap).toDouble() }.toFloat()
        val offset = surface.borderHalfWidth + buttonLedClearancePx() + stroke / 2f + preceding
        val points = offsetContour(innerContour(width, height, left), -offset, left)
        val trimmed = trimRail(points, surface.bounds.top + stroke / 2f,
            if (left) surface.bounds.right - stroke / 2f else surface.bounds.left + stroke / 2f, left)
        return pointsPath(trimmed)
    }

    /** Gesture area stays inside the original outer slots; drawing reserves space in their surfaces. */
    private fun buildLedExclusionPath(width: Float, height: Float, extraClearance: Float = 0f): Path {
        if (ledRadii == null && radii == null) return Path()
        val gap = LedStatusView.CONTOUR_LED_GAP_DP * context.resources.displayMetrics.density + extraClearance
        return Path().apply {
            for (left in listOf(true, false)) {
                val occupied = Path()
                sideRails(left).forEach { segment ->
                    val rail = Path()
                    Paint().apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = ledStrokeWidth(segment) + 2f * gap
                    }.getFillPath(ledRailPath(width, height, segment), rail)
                    occupied.op(rail, Path.Op.UNION)
                }
                if (followDisplayContour) {
                    val slot = Path().apply { addRect(outerSurface(width, height, left).bounds, Path.Direction.CW) }
                    occupied.op(slot, Path.Op.INTERSECT)
                    occupied.op(insideContour(width, height, innerContour(width, height, left), left), Path.Op.DIFFERENCE)
                }
                op(occupied, Path.Op.UNION)
            }
        }
    }

    private fun trimRail(points: List<PointF>, top: Float, endX: Float, left: Boolean): List<PointF> {
        val trimmed = ArrayList<PointF>()
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val point = points[index]
            if (trimmed.isEmpty()) {
                if (point.y < top) continue
                val ratio = if (kotlin.math.abs(point.y - previous.y) < 0.001f) 1f
                    else ((top - previous.y) / (point.y - previous.y)).coerceIn(0f, 1f)
                trimmed += PointF(previous.x + (point.x - previous.x) * ratio, top)
            }
            if (if (left) point.x >= endX else point.x <= endX) {
                val before = trimmed.last()
                val ratio = if (kotlin.math.abs(point.x - before.x) < 0.001f) 1f
                    else ((endX - before.x) / (point.x - before.x)).coerceIn(0f, 1f)
                trimmed += PointF(endX, before.y + (point.y - before.y) * ratio)
                break
            }
            trimmed += point
        }
        return trimmed
    }

    private fun offsetContour(points: List<PointF>, distance: Float, left: Boolean): List<PointF> =
        points.mapIndexed { index, point ->
            val before = points[(index - 1).coerceAtLeast(0)]
            val after = points[(index + 1).coerceAtMost(points.lastIndex)]
            val dx = after.x - before.x
            val dy = after.y - before.y
            val length = hypot(dx, dy).coerceAtLeast(0.001f)
            val nx = if (left) dy / length else -dy / length
            val ny = if (left) -dx / length else dx / length
            PointF(point.x + nx * distance, point.y + ny * distance)
        }

    private fun pointsPath(points: List<PointF>): Path = Path().apply {
        points.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
    }

    private fun insideContour(width: Float, height: Float, points: List<PointF>, left: Boolean): Path =
        pointsPath(points).apply {
            if (points.isNotEmpty()) {
                val inside = if (left) width * 2f else -width
                lineTo(inside, points.last().y)
                lineTo(inside, points.first().y)
                close()
            }
        }

    private fun matchesSurface(bounds: RectF, surface: OuterSurface?, inset: Float): Boolean =
        surface != null && kotlin.math.abs(bounds.top - surface.bounds.top - inset) < 1f &&
            kotlin.math.abs(bounds.bottom - surface.bounds.bottom + inset) < 1f &&
            kotlin.math.abs(bounds.left - surface.bounds.left - inset) < 1f &&
            kotlin.math.abs(bounds.right - surface.bounds.right + inset) < 1f

    fun ownsLedSurface(bounds: RectF, left: Boolean, right: Boolean, inset: Float = 0f): Boolean =
        ledsVisible && followDisplayContour &&
            (left && matchesSurface(bounds, leftSurface, inset) || right && matchesSurface(bounds, rightSurface, inset))

    private fun buildSurfacePath(width: Float, height: Float, bounds: RectF, radius: Float,
                    left: Boolean, right: Boolean, strokeInset: Float = 0f): Path {
        val rect = RectF(bounds)
        val lowestLeft = left && ledsVisible && followDisplayContour && matchesSurface(rect, leftSurface, strokeInset)
        val lowestRight = right && ledsVisible && followDisplayContour && matchesSurface(rect, rightSurface, strokeInset)
        if (!hardwareTopRounding) {
            if (lowestLeft) rect.left = maxOf(rect.left, innerContour(width, height, true).first().x + strokeInset)
            if (lowestRight) rect.right = minOf(rect.right, innerContour(width, height, false).first().x - strokeInset)
        }
        val bottomRadius = radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2f).coerceAtLeast(0f)
        val outerLeft = matchesSurface(rect, leftSurface, strokeInset) || leftSurface == null && ledRowTop == null
        val outerRight = matchesSurface(rect, rightSurface, strokeInset) || rightSurface == null && ledRowTop == null
        val shapedLeft = left && outerLeft && radii != null && hardwareTopRounding
        val shapedRight = right && outerRight && radii != null && hardwareTopRounding
        val path = Path().apply {
            addRoundRect(rect, floatArrayOf(if (shapedLeft) 0f else bottomRadius, if (shapedLeft) 0f else bottomRadius,
                if (shapedRight) 0f else bottomRadius, if (shapedRight) 0f else bottomRadius,
                bottomRadius, bottomRadius, bottomRadius, bottomRadius), Path.Direction.CW)
        }
        contentPath(width, height, strokeInset)?.let { path.op(it, Path.Op.INTERSECT) }
        for (side in listOf(true, false)) {
            if (if (side) lowestLeft else lowestRight) {
                val contour = offsetContour(innerContour(width, height, side),
                    (strokeInset - outerSurface(width, height, side).borderHalfWidth).coerceAtLeast(0f), side)
                path.op(insideContour(width, height, contour, side), Path.Op.INTERSECT)
            } else if (hardwareTopRounding && if (side) left && outerLeft else right && outerRight) {
                val available = if (side) rect.right else width - rect.left
                val transition = topTransition(width, height, rect.top, rect.bottom, side, radius, available, strokeInset)
                    ?: continue
                path.op(insideContour(width, height, transitionPoints(transition) + PointF(
                    if (side) -width else width * 2f, rect.bottom + height), side), Path.Op.INTERSECT)
            }
        }
        return path
    }

    fun topTransition(width: Float, height: Float, top: Float, bottom: Float, left: Boolean,
                      radius: Float, availableWidth: Float, extraInset: Float = 0f): Transition? {
        val corners = radii ?: return null
        val calibration = calibrationSnapshot
        val displayRadius = (if (left) corners.first else corners.second).toFloat()
        val bottomRadius = radius.coerceAtMost(minOf(availableWidth, bottom - top) / 2f)
        val multiplier = if (hardwareTopRounding) topMultiplier else 1
        val topRadius = (radius * multiplier)
            .coerceAtMost(minOf(availableWidth - bottomRadius, bottom - top - bottomRadius)).coerceAtLeast(0f)
        if (topRadius == 0f) return null
        fun boundary(y: Float) = T2eCornerGeometry.atY(displayRadius, height,
            y - calibration.shiftYPx, calibration, extraInset).x
        val joinY = (top + topRadius).coerceAtMost(bottom - bottomRadius)
        val joinX = boundary(joinY)
        val dx = (boundary(joinY + 0.05f) - boundary(joinY - 0.05f)).coerceAtLeast(0f)
        val tangentLength = hypot(dx, 0.1f)
        val startX = (boundary(top) + topRadius).coerceAtMost(availableWidth - bottomRadius)
        val handle = minOf((joinY - top) * 0.55228475f,
            (startX - joinX).coerceAtLeast(0f) + topRadius * 0.25f)
        fun x(local: Float) = (if (left) local else width - local) + calibration.shiftXPx
        return Transition(PointF(x(startX), top), PointF(x(startX - handle), top),
            PointF(x(joinX - dx / tangentLength * handle), joinY - 0.1f / tangentLength * handle),
            PointF(x(joinX), joinY))
    }

    private fun transitionPoints(transition: Transition): List<PointF> = (0..32).map { index ->
        val t = index / 32f
        val u = 1f - t
        PointF(u*u*u*transition.start.x + 3*u*u*t*transition.firstHandle.x +
            3*u*t*t*transition.secondHandle.x + t*t*t*transition.join.x,
            u*u*u*transition.start.y + 3*u*u*t*transition.firstHandle.y +
                3*u*t*t*transition.secondHandle.y + t*t*t*transition.join.y)
    }

    fun edgeContour(width: Float, height: Float, top: Float, left: Boolean): List<PointF> =
        physicalContour(width, height, top, left, 0f)

    private fun physicalContour(width: Float, height: Float, top: Float, left: Boolean,
                                inset: Float): List<PointF> {
        val corners = ledRadii ?: radii ?: return emptyList()
        val calibration = calibrationSnapshot
        val radius = (if (left) corners.first else corners.second).toFloat()
        return buildList {
            val first = T2eCornerGeometry.atY(radius, height, top - calibration.shiftYPx, calibration, inset)
            add(PointF((if (left) first.x else width - first.x) + calibration.shiftXPx, top))
            for (index in 0..256) {
                val point = T2eCornerGeometry.point(radius, height, PI / 2 * index / 256, calibration, inset)
                if (point.y + calibration.shiftYPx > top) add(PointF(
                    (if (left) point.x else width - point.x) + calibration.shiftXPx, point.y + calibration.shiftYPx))
            }
        }
    }

    private fun innerContour(width: Float, height: Float, left: Boolean): List<PointF> =
        innerContours.getOrPut(left) { buildInnerContour(width, height, left) }

    private fun buildInnerContour(width: Float, height: Float, left: Boolean): List<PointF> {
        val surface = outerSurface(width, height, left)
        val rect = RectF(surface.bounds).apply { inset(surface.borderHalfWidth, surface.borderHalfWidth) }
        val inset = buttonInset(left) + surface.borderHalfWidth
        val transition = if (hardwareTopRounding && radii != null)
            topTransition(width, height, rect.top, rect.bottom, left, surface.radius,
                if (left) rect.right else width - rect.left, inset) else null
        val top = transition?.join?.y ?: rect.top
        val physical = physicalContour(width, height, top, left, inset)
        val contour = (if (transition == null) physical else transitionPoints(transition) + physical.drop(1)).toMutableList()
        val endX = if (left) rect.right else rect.left
        contour.lastOrNull()?.let { end ->
            if (if (left) endX > end.x else endX < end.x) contour += PointF(endX, end.y)
        }
        return contour
    }

    companion object {
        fun centroid(region: Region, fallback: PointF): PointF {
            val iterator = RegionIterator(region)
            val rectangle = Rect()
            var area = 0.0
            var x = 0.0
            var y = 0.0
            while (iterator.next(rectangle)) {
                val size = rectangle.width().toDouble() * rectangle.height()
                area += size
                x += rectangle.exactCenterX() * size
                y += rectangle.exactCenterY() * size
            }
            return if (area > 0.0) PointF((x / area).toFloat(), (y / area).toFloat()) else fallback
        }
    }
}
