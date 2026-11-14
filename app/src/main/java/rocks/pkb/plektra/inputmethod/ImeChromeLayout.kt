package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.ui.LedStatusView

internal class ImeChromeLayout(context: Context) : LinearLayout(context) {
    val geometry = KeyboardBarGeometry(context)
    var ledOnlyReservedHeightPx: Int? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    private val screenAwakeController = ImeTouchScreenAwakeController(context)
    private var ledTouchActive = false
    var regularCornerColors: Pair<Int, Int> = Color.BLACK to Color.BLACK
    var compactCornerColors: Pair<Int, Int> = Color.BLACK to Color.BLACK
    var bottomFillColors: Pair<Int, Int> = Color.BLACK to Color.BLACK
    private val cornerFillPaint = Paint()
    private var clipRevision = -1L
    private var foldScope: CoroutineScope? = null
    private var foldingFeatures: List<FoldingFeature> = emptyList()
    private var modeTransitionStartHeight: Int? = null
    private var animatedHeight: Int? = null
    private var heightAnimator: ValueAnimator? = null
    private val calibrationPreviewListener: () -> Unit = {
        geometry.refreshSettings(forceCalibration = true)
        applyBottomCornerClip()
        requestLayout()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rocks.pkb.plektra.T2eCornerCalibration.addPreviewListener(calibrationPreviewListener)
        foldScope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { scope ->
            scope.launch {
                try {
                    WindowInfoTracker.getOrCreate(context).windowLayoutInfo(context).collect { info ->
                        foldingFeatures = info.displayFeatures.filterIsInstance<FoldingFeature>()
                        android.util.Log.d("PlektraFold", "features=$foldingFeatures")
                        requestLayout()
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    android.util.Log.w("PlektraFold", "Folding information unavailable", error)
                }
            }
        }
    }

    internal fun drawTransparentArtwork(canvas: Canvas) {
        // Structural row backgrounds must not fill the space behind LED rails.
        // Individual keys and custom keyboard surfaces keep their own drawing.
        val backgrounds = mutableListOf<Pair<ViewGroup, android.graphics.drawable.Drawable>>()
        fun clearContainerBackgrounds(group: ViewGroup) {
            if (group === this || group.getTag(R.id.keyboard_artwork_container) == true) {
                group.background?.let { backgrounds += group to it; group.background = null }
            }
            for (i in 0 until group.childCount) {
                (group.getChildAt(i) as? ViewGroup)?.let(::clearContainerBackgrounds)
            }
        }
        try {
            clearContainerBackgrounds(this)
            dispatchDraw(canvas)
        } finally {
            backgrounds.forEach { (group, drawable) -> group.background = drawable }
        }
    }

    override fun draw(canvas: Canvas) {
        drawChrome(canvas)
        rocks.pkb.plektra.DebugShowcaseExport.afterDraw(this)
    }

    private fun drawChrome(canvas: Canvas) {
        if (ledOnlyReservedHeightPx != null) {
            super.draw(canvas)
            return
        }
        val radii = bottomCornerRadiiPx
        if (radii != null) {
            drawStatusRowSideFill(canvas, radii)
        }
        if (fillDisplayCorners || radii == null || radii.first <= 0 && radii.second <= 0) {
            super.draw(canvas)
            return
        }
        val path = geometry.displayPathRef(width.toFloat(), height.toFloat()) ?: return
        val bottomContour = geometry.bottomContourPathRef(width.toFloat(), height.toFloat()) ?: return
        cornerFillPaint.color = bottomFillColors.first
        cornerFillPaint.style = Paint.Style.STROKE
        cornerFillPaint.strokeWidth = 2f * resources.displayMetrics.density
        cornerFillPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(bottomContour, cornerFillPaint)
        cornerFillPaint.style = Paint.Style.FILL

        val contentSave = canvas.save()
        canvas.clipPath(path)
        super.draw(canvas)
        canvas.restoreToCount(contentSave)
    }

    private fun drawStatusRowSideFill(canvas: Canvas, radii: Pair<Int, Int>) {
        val row = nestedRow ?: return
        val colors = if (row === compactStatusRow) compactCornerColors else regularCornerColors
        cornerFillPaint.style = Paint.Style.FILL
        cornerFillPaint.color = colors.first
        canvas.drawRect(0f, row.top.toFloat(), radii.first.toFloat(), row.bottom.toFloat(), cornerFillPaint)
        cornerFillPaint.color = colors.second
        canvas.drawRect(
            (width - radii.second).toFloat(),
            row.top.toFloat(),
            width.toFloat(),
            row.bottom.toFloat(),
            cornerFillPaint
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val row = nestedRow ?: return
        val radii = bottomCornerRadiiPx ?: return
        val colors = if (row === compactStatusRow) compactCornerColors else regularCornerColors
        // Continue the row's themed surface to the bottom of the display.
        // Children draw afterward, keeping the modifier lights above the fill.
        cornerFillPaint.color = if (row === compactStatusRow) bottomFillColors.second else bottomFillColors.first
        canvas.drawRect(0f, row.bottom.toFloat(), width.toFloat(), height.toFloat(), cornerFillPaint)
        cornerFillPaint.color = colors.second
        canvas.drawRect((width - radii.second).toFloat(), row.bottom.toFloat(), width.toFloat(), height.toFloat(), cornerFillPaint)
    }
    var indicatorView: View? = null
    var expandedSurfaceView: View? = null
    var compactStatusRow: View? = null
    var contourIntegratedIndicators: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            geometry.ledsVisible = value
            applyBottomCornerClip()
            requestLayout()
            invalidate()
        }
    var onLedGeometryChanged: (() -> Unit)? = null
    /**
     * Keeps the nested suggestions row at its own height. The LED band below it keeps
     * the themed fill instead of the row being stretched to meet the contour.
     */
    var compactNestedRow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    private var nestedRow: View? = null
    private var originalRowMargins = intArrayOf(0, 0, 0)
    private var originalRowOutline: ViewOutlineProvider? = null
    private var originalRowClip = false
    private var originalRowMinHeight = 0
    private var nestedRowMinHeight = 0
    private var originalRowPaddingBottom = 0

    private fun updateNestedStatusRow() {
        indicatorView?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        (indicatorView?.layoutParams as? LayoutParams)?.topMargin = 0
        expandedSurfaceView?.clipToOutline = false
        nestedRow?.let { row ->
            (row.layoutParams as LayoutParams).apply {
                leftMargin = originalRowMargins[0]
                rightMargin = originalRowMargins[1]
                bottomMargin = originalRowMargins[2]
            }
            row.outlineProvider = originalRowOutline
            row.clipToOutline = originalRowClip
            // The row owner may have resized the row since; keep its value over the saved one.
            if (row.minimumHeight == nestedRowMinHeight) row.minimumHeight = originalRowMinHeight
            row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, originalRowPaddingBottom)
        }
        nestedRow = null
        val radii = bottomCornerRadiiPx ?: return
        if (expandedSurfaceView?.visibility == View.VISIBLE && indicatorView?.visibility == View.VISIBLE) {
            val density = resources.displayMetrics.density
            val radius = maxOf(radii.first, radii.second)
            val ledHeight = radius + density.toInt()
            (indicatorView?.layoutParams as? LayoutParams)?.apply {
                height = ledHeight
                topMargin = -ledHeight
            }
            return
        }
        if (softwareKeyboardModeActive || expandedSurfaceView?.visibility != View.GONE ||
            indicatorView?.visibility != View.VISIBLE || surfaceView?.visibility != View.VISIBLE
        ) return
        val surfaceIndex = indexOfChild(surfaceView)
        val row = (surfaceIndex - 1 downTo 0)
            .map(::getChildAt).firstOrNull { it.visibility == View.VISIBLE } ?: return
        val params = row.layoutParams as LayoutParams
        nestedRow = row
        originalRowMargins = intArrayOf(params.leftMargin, params.rightMargin, params.bottomMargin)
        originalRowOutline = row.outlineProvider
        originalRowClip = row.clipToOutline
        originalRowMinHeight = row.minimumHeight
        originalRowPaddingBottom = row.paddingBottom
        if (contourIntegratedIndicators) {
            val calibration = geometry.calibration
            val edgeInset = kotlin.math.ceil((calibration.offsetPx - calibration.shiftYPx).coerceAtLeast(0f)).toInt()
            row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, minOf(row.paddingBottom, edgeInset))
        }
        // Reserve a dedicated band so both LED rows remain visibly separate.
        val inset = if (contourIntegratedIndicators) 0 else
            (LedStatusView.LED_ZONE_HEIGHT_DP * resources.displayMetrics.density).toInt()
        val radius = maxOf(radii.first, radii.second)
        val stripTop = (resources.displayMetrics.density).toInt()
        // The LED surface draws first; overlap its empty center with the row.
        val requestedRowHeight = params.height.coerceAtLeast(0)
        val bottomInset = inset
        indicatorView?.layoutParams?.height = maxOf(radius + stripTop, requestedRowHeight + bottomInset)
        // A fixed-height row does not honor minimumHeight during measurement.
        // Overlapping more than that height puts the LED surface above the
        // row's top, while LinearLayout still reserves its full height below.
        // onLayout expands the row to meet the LEDs after measurement.
        val overlap = if (params.height >= 0) requestedRowHeight
            else (radius + stripTop - inset).coerceAtLeast(0)
        params.bottomMargin -= overlap
        row.minimumHeight = maxOf(originalRowMinHeight, overlap)
        nestedRowMinHeight = row.minimumHeight
        // Each outer button draws against the calibrated contour; the chrome clips the display edge.
        row.outlineProvider = originalRowOutline
        row.clipToOutline = false
        row.invalidateOutline()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (softwareKeyboardModeActive && animatedHeight != null) {
            val contentBottom = (0 until childCount).map { getChildAt(it) }
                .filter { it.visibility != View.GONE }.maxOfOrNull { it.bottom } ?: height
            val offset = height - paddingBottom - contentBottom
            for (index in 0 until childCount) getChildAt(index).offsetTopAndBottom(offset)
        }
        updateLedConstraintTop()
        rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.updateRows(this)
        val row = nestedRow as? ViewGroup
        if (row == null) return
        val originalContentHeight = row.height
        // A fixed-height row can be shorter than the requested overlap.
        // Anchor its actual bottom to the inner LED contour after layout.
        // The straight lower indicators occupy only the lower LED row;
        // their top edge is closer to the bottom than the two-row side arcs.
        val bottomInset = if (contourIntegratedIndicators) 0 else
            (LedStatusView.LED_ZONE_HEIGHT_DP * resources.displayMetrics.density).toInt()
        surfaceView?.let { surface ->
            val targetBottom = surface.bottom - bottomInset
            val extraHeight = (targetBottom - row.bottom).coerceAtLeast(0)
            // Fill the space up to the row's original top instead of translating
            // a short row downward and exposing an empty band above it.
            fun extendContent(view: View) {
                val oldTop = if (view === row || contourIntegratedIndicators) view.top else 0
                val oldLeft = view.left
                val oldWidth = view.width
                val oldHeight = view.height
                if (contourIntegratedIndicators && view is ViewGroup) {
                    val calibration = geometry.calibration
                    val inset = kotlin.math.ceil((calibration.offsetPx - calibration.shiftYPx).coerceAtLeast(0f)).toInt()
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, minOf(view.paddingBottom, inset))
                }
                val targetHeight = if (view === row) oldHeight + extraHeight
                    else ((view.parent as View).height - (view.parent as View).paddingBottom - oldTop).coerceAtLeast(0)
                view.measure(
                    MeasureSpec.makeMeasureSpec(oldWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
                )
                view.layout(oldLeft, oldTop, oldLeft + oldWidth, oldTop + targetHeight)
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        val child = view.getChildAt(index)
                        if (child.visibility == View.VISIBLE &&
                            child.height >= originalContentHeight - 4f * resources.displayMetrics.density &&
                            child.height <= view.height
                        ) extendContent(child)
                    }
                }
            }
            val compact = compactNestedRow && row === compactStatusRow
            if (!compact && (extraHeight > 0 || contourIntegratedIndicators)) extendContent(row)
        }
        if (contourIntegratedIndicators) {
            updateLedConstraintTop()
            rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.updateRows(row)
        } else {
            // Keep the existing vertical alignment when the contour LEDs are not active.
            for (index in 0 until row.childCount) {
                val child = row.getChildAt(index)
                if (child.visibility == View.VISIBLE && child.height < row.height) {
                    child.offsetTopAndBottom(row.height - row.paddingBottom - child.bottom)
                }
            }
            onLedGeometryChanged?.invoke()
        }
    }

    private fun updateLedConstraintTop() {
        val indicator = indicatorView
        val row = rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.bottomRow(this)
        val top = row?.top
        geometry.ledRowTop = top?.toFloat()
        geometry.ledRowRadius = row?.radius ?: 0f
        geometry.leftSurface = row?.left
        geometry.rightSurface = row?.right
        geometry.prepare(width.toFloat(), height.toFloat())
        if (!contourIntegratedIndicators || indicator == null) {
            onLedGeometryChanged?.invoke()
            return
        }
        val bounds = android.graphics.Rect(0, 0, indicator.width, indicator.height).also {
            offsetDescendantRectToMyCoords(indicator, it)
        }
        val calibration = geometry.calibration
        val arcTop = kotlin.math.floor(height -
            maxOf(bottomCornerRadiiPx?.first ?: 0, bottomCornerRadiiPx?.second ?: 0) * calibration.size +
            calibration.shiftYPx).toInt()
        val targetTop = minOf(top ?: arcTop, arcTop)
        val targetHeight = height - targetTop
        if (targetHeight > 0 && (indicator.height != targetHeight || bounds.top != targetTop ||
                bounds.bottom != height || indicator.width != width)) {
            val parentTop = bounds.top - indicator.top
            indicator.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY))
            indicator.layout(indicator.left, targetTop - parentTop, indicator.left + width, height - parentTop)
            bounds.top = targetTop
        }
        onLedGeometryChanged?.invoke()
    }

    // (left, right) display corner radii in px; null disables the outline clip.
    var bottomCornerRadiiPx: Pair<Int, Int>? = null
        set(value) {
            geometry.radii = value
            if (field == value) {
                applyBottomCornerClip()
                invalidate()
                requestLayout()
                return
            }
            field = value
            applyBottomCornerClip()
            requestLayout()
        }

    var fillDisplayCorners: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var surfaceView: View? = null
        set(value) {
            field = value
            requestLayout()
        }
    var softwareKeyboardModeActive: Boolean = false
        set(value) {
            if (field == value) return
            heightAnimator?.cancel()
            heightAnimator = null
            animatedHeight = null
            modeTransitionStartHeight = height.takeIf { isShown && it > 0 }
            field = value
            gravity = if (value) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
            geometry.hardwareTopRounding = !value
            applyBottomCornerClip()
            requestLayout()
        }

    init {
        setChildrenDrawingOrderEnabled(true)
        gravity = android.view.Gravity.BOTTOM
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        screenAwakeController.onTouchAction(event.actionMasked)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ledTouchActive = ledOnlyReservedHeightPx == null &&
                geometry.ledsVisible && geometry.ledContains(width.toFloat(), height.toFloat(), event.x, event.y)
        }
        if (ledTouchActive) {
            val indicator = indicatorView?.takeIf { it.visibility == View.VISIBLE } ?: return false
            val bounds = android.graphics.Rect(0, 0, indicator.width, indicator.height).also {
                offsetDescendantRectToMyCoords(indicator, it)
            }
            val local = MotionEvent.obtain(event).apply { offsetLocation(-bounds.left.toFloat(), -bounds.top.toFloat()) }
            val handled = try { indicator.dispatchTouchEvent(local) } finally { local.recycle() }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ledTouchActive = false
            return handled
        }
        return super.dispatchTouchEvent(event)
    }

    // Extend the shape above the view so even a bar shorter than the display
    // radius follows the original arc, rather than shrinking it to fit the bar.
    private fun applyBottomCornerClip() {
        geometry.prepare(width.toFloat(), height.toFloat())
        if (clipRevision == geometry.revision) return
        clipRevision = geometry.revision
        fun refresh(view: View) {
            view.invalidate()
            view.background?.invalidateSelf()
            if (view is ViewGroup) for (index in 0 until view.childCount) refresh(view.getChildAt(index))
        }
        refresh(this)
        val radii = bottomCornerRadiiPx
        val radius = radii?.let { maxOf(it.first, it.second) } ?: 0
        if (radius <= 0) {
            clipToOutline = false
            outlineProvider = ViewOutlineProvider.BACKGROUND
            return
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val left = radii!!.first.coerceIn(0, view.width / 2).toFloat()
                val right = radii.second.coerceIn(0, view.width / 2).toFloat()
                val path = rocks.pkb.plektra.T2eCornerGeometry.path(
                    view.width.toFloat(), view.height.toFloat(), left, right,
                    geometry.calibration
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    outline.setPath(path)
                } else {
                    @Suppress("DEPRECATION")
                    outline.setConvexPath(path)
                }
            }
        }
        // draw() clips the content and paints the bottom extension behind it.
        clipToOutline = false
        invalidateOutline()
    }

    override fun onDetachedFromWindow() {
        heightAnimator?.cancel()
        heightAnimator = null
        animatedHeight = null
        modeTransitionStartHeight = null
        foldScope?.cancel()
        foldScope = null
        foldingFeatures = emptyList()
        rocks.pkb.plektra.T2eCornerCalibration.removePreviewListener(calibrationPreviewListener)
        screenAwakeController.release()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateNestedStatusRow()
        val surface = surfaceView
        if (!softwareKeyboardModeActive || surface == null || surface.visibility == View.GONE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val target = measuredHeight
            setMeasuredDimension(measuredWidth, animatedModeHeight(target))
            return
        }

        var totalChildHeight = 0
        var maxWidth = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(
                child,
                widthMeasureSpec,
                0,
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                0
            )
            val params = child.layoutParams as MarginLayoutParams
            totalChildHeight += child.measuredHeight + params.topMargin + params.bottomMargin
            maxWidth = maxOf(maxWidth, child.measuredWidth + params.leftMargin + params.rightMargin)
        }

        val measuredWidth = resolveSize(maxWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val desiredHeight = paddingTop + paddingBottom + totalChildHeight
        val windowHeightLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) null
            else MeasureSpec.getSize(heightMeasureSpec)
        val heightLimit = listOfNotNull(foldHeightLimitPx(), windowHeightLimit).minOrNull()
        val targetHeight = minOf(desiredHeight, heightLimit ?: desiredHeight)
        val measuredHeight = animatedModeHeight(targetHeight).let { minOf(it, heightLimit ?: it) }
        // Measure keys at their final size; animate the viewport instead of squeezing rows.
        if (desiredHeight > targetHeight) {
            val reducedSurfaceHeight = (surface.measuredHeight - (desiredHeight - targetHeight)).coerceAtLeast(0)
            surface.measure(
                MeasureSpec.makeMeasureSpec(surface.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(reducedSurfaceHeight, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    private fun animatedModeHeight(target: Int): Int {
        modeTransitionStartHeight?.let { start ->
            modeTransitionStartHeight = null
            if (start != target && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                animatedHeight = start
                heightAnimator = ValueAnimator.ofInt(start, target).apply {
                    duration = 180L
                    addUpdateListener {
                        animatedHeight = it.animatedValue as Int
                        if (it.animatedFraction == 1f) animatedHeight = null
                        requestLayout()
                    }
                    start()
                }
            } else animatedHeight = null
        }
        return animatedHeight ?: target
    }

    private fun foldHeightLimitPx(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val unfoldedInnerDisplay = resources.configuration.smallestScreenWidthDp >= 600 &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        if (foldingFeatures.isEmpty() && !unfoldedInnerDisplay) return null
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val windowLocation = IntArray(2)
        rootView.getLocationOnScreen(windowLocation)
        val horizontalFoldBottom = foldingFeatures
            .filter { it.orientation == FoldingFeature.Orientation.HORIZONTAL }
            .maxOfOrNull { it.bounds.bottom + windowLocation[1] - bounds.top }
        return FoldKeyboardHeightPolicy.limit(bounds.height(), horizontalFoldBottom)
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val surfaceIndex = surfaceView
            ?.let(::indexOfChild)
            ?.takeIf { it in 0 until childCount }
            ?: return super.getChildDrawingOrder(childCount, drawingPosition)

        if (contourIntegratedIndicators) {
            return if (drawingPosition == childCount - 1) {
                surfaceIndex
            } else if (drawingPosition < surfaceIndex) {
                drawingPosition
            } else {
                drawingPosition + 1
            }
        }

        return if (drawingPosition == 0) {
            surfaceIndex
        } else {
            val shiftedPosition = drawingPosition - 1
            if (shiftedPosition < surfaceIndex) shiftedPosition else shiftedPosition + 1
        }
    }
}
