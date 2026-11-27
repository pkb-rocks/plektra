package rocks.pkb.plektra

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * The notes header: [start] (Back), an optional [middle] (the title) and the end actions
 * in one row. Like the settings header, elements that meet a display cutout move sideways
 * ([SettingsHeaderCutoutLayout]); a cutout that reaches below the row makes the row
 * taller, so the rows underneath start below the camera. When the actions cannot sit
 * beside Back without touching it, the window edge or a cutout, they flow into rows below.
 */
internal class NotesHeaderRow(context: Context) : ViewGroup(context) {
    var start: View? = null
        set(value) { replace(field, value); field = value }
    var middle: View? = null
        set(value) { replace(field, value); field = value }

    /** Actions, left to right; they keep their measured widths and stay together. */
    private val end = mutableListOf<View>()

    var minRowHeight = 0
    var cutoutGap = 0

    /** Width the title gets in this row, measured even while the title is shown elsewhere. */
    var middleCapacity = 0
        private set

    /** Display cutout rectangles in window coordinates. */
    var cutouts: List<Rect> = emptyList()
        set(value) {
            if (value == field) return
            field = value
            requestLayout()
        }

    private val measuredOrigin = IntArray(2)
    private val placedOrigin = IntArray(2)
    private var startX = 0
    private var middleX = 0
    private var firstRowHeight = 0
    /** Per visible action: x (LTR), row top and row bottom. */
    private val endPositions = mutableListOf<IntArray>()

    /** True while the actions do not fit beside Back and flow into rows below. */
    var wrapped = false
        private set

    fun addEnd(view: View) {
        end += view
        addView(view)
    }

    private fun replace(old: View?, new: View?) {
        if (old === new) return
        if (old != null && old.parent === this) removeView(old)
        if (new != null) {
            (new.parent as? ViewGroup)?.removeView(new)
            addView(new)
        }
    }

    /**
     * Where this row will lie in the window: the top start of its parent's content area, as
     * the row is the parent's first child. Known before layout, so a changed status bar
     * inset never measures the row against its previous position.
     */
    private fun expectedOrigin(out: IntArray) {
        val parent = parent as? View
        if (parent == null) {
            getLocationInWindow(out)
            return
        }
        parent.getLocationInWindow(out)
        out[0] += parent.paddingLeft
        out[1] += parent.paddingTop
    }

    private fun View?.shown(): Boolean = this != null && visibility != GONE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val loose = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val visibleEnd = end.filter { it.shown() }
        val sides = (listOfNotNull(start) + visibleEnd).filter { it.shown() }
        sides.forEach { child ->
            val params = child.layoutParams
            child.measure(
                if (params != null && params.width > 0) MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY) else loose,
                if (params != null && params.height > 0) MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY) else unspecified
            )
        }
        val startWidth = start?.takeIf { it.shown() }?.measuredWidth ?: 0
        val endWidth = visibleEnd.sumOf { it.measuredWidth }
        val rowHeight = max(minRowHeight, sides.maxOfOrNull { it.measuredHeight } ?: 0)

        expectedOrigin(measuredOrigin)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // Cutout spans (row-local, LTR order, widened by the gap) meeting rows top until bottom.
        fun spans(top: Int, bottom: Int): List<IntRange> = cutouts.mapNotNull { rect ->
            val rectTop = rect.top - measuredOrigin[1]
            val rectBottom = rect.bottom - measuredOrigin[1]
            if (rect.isEmpty || rectBottom <= top || rectTop >= bottom) return@mapNotNull null
            val left = rect.left - measuredOrigin[0]
            val right = rect.right - measuredOrigin[0]
            // RTL mirrors the cutout; placement mirrors back in onLayout.
            if (rtl) (width - right - cutoutGap)..(width - left + cutoutGap) else (left - cutoutGap)..(right + cutoutGap)
        }
        fun overlaps(x: Int, w: Int, blocked: List<IntRange>) = blocked.any { x < it.last && x + w > it.first }
        // Cutouts reaching below a row push the rows underneath below the camera.
        fun clearedBottom(top: Int, bottom: Int): Int =
            cutouts.filter { !it.isEmpty && it.bottom - measuredOrigin[1] > top && it.top - measuredOrigin[1] < bottom }
                .maxOfOrNull { max(bottom, it.bottom - measuredOrigin[1]) } ?: bottom

        val firstBottom = clearedBottom(0, rowHeight)
        val firstSpans = spans(0, firstBottom)
        fun arrange(endBlock: Int) = SettingsHeaderCutoutLayout.arrange(
            width = width,
            startWidth = startWidth,
            endWidth = endBlock,
            // The title takes the widest free stretch between Back and the actions.
            middlePreferred = width,
            middleMin = width,
            padStart = paddingStart,
            padEnd = paddingEnd,
            cutouts = firstSpans
        )
        val single = arrange(endWidth)
        val singleFits = visibleEnd.isEmpty() || (
            single.endX >= paddingStart &&
                single.endX >= single.startX + startWidth &&
                single.endX + endWidth <= width - paddingEnd &&
                !overlaps(single.endX, endWidth, firstSpans))
        endPositions.clear()
        val arrangement: SettingsHeaderArrangement
        var height = firstBottom
        if (singleFits) {
            arrangement = single
            var x = single.endX
            visibleEnd.forEach { endPositions += intArrayOf(x, 0, firstBottom); x += it.measuredWidth }
        } else {
            // Too narrow for Back, the actions and the cutouts in one row: the actions flow
            // into rows below, each one whole, separate and beside any cutout.
            arrangement = arrange(0)
            var x = paddingStart
            var top = firstBottom
            var bottom = clearedBottom(top, top + rowHeight)
            visibleEnd.forEach { action ->
                val w = action.measuredWidth
                while (true) {
                    val blocked = spans(top, bottom)
                    var candidate = x
                    while (true) candidate = blocked.firstOrNull { candidate < it.last && candidate + w > it.first }?.last ?: break
                    if (candidate + w <= width - paddingEnd || x == paddingStart) {
                        x = candidate
                        break
                    }
                    x = paddingStart
                    top = bottom
                    bottom = clearedBottom(top, top + rowHeight)
                }
                endPositions += intArrayOf(x, top, bottom)
                x += w
            }
            height = bottom
        }
        wrapped = !singleFits
        firstRowHeight = firstBottom
        startX = arrangement.startX
        middleX = arrangement.middleX
        middleCapacity = arrangement.middleWidth
        middle?.takeIf { it.shown() }?.measure(
            MeasureSpec.makeMeasureSpec(arrangement.middleWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(firstBottom, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun place(view: View, x: Int, top: Int, bottom: Int) {
            val left = if (rtl) width - x - view.measuredWidth else x
            val y = top + (bottom - top - view.measuredHeight) / 2
            view.layout(left, y, left + view.measuredWidth, y + view.measuredHeight)
        }
        start?.takeIf { it.shown() }?.let { place(it, startX, 0, firstRowHeight) }
        middle?.takeIf { it.shown() }?.let { place(it, middleX, 0, firstRowHeight) }
        end.filter { it.shown() }.forEachIndexed { index, view ->
            val (x, top, bottom) = endPositions.getOrNull(index) ?: return@forEachIndexed
            place(view, x, top, bottom)
        }
        // Safety net should the row not lie where it was measured for.
        getLocationInWindow(placedOrigin)
        if (cutouts.isNotEmpty() && !placedOrigin.contentEquals(measuredOrigin)) post { requestLayout() }
    }
}
