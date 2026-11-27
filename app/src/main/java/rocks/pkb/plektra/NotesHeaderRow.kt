package rocks.pkb.plektra

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * The notes header: [start] (Back), an optional [middle] (the title) and the [end]
 * actions in one row. Like the settings header, elements that meet a display cutout move
 * sideways ([SettingsHeaderCutoutLayout]); a cutout that reaches below the row makes the
 * row taller, so the rows underneath start below the camera.
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
    private var endX = 0

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
        val sides = (listOfNotNull(start) + end).filter { it.shown() }
        sides.forEach { child ->
            val params = child.layoutParams
            child.measure(
                if (params != null && params.width > 0) MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY) else loose,
                if (params != null && params.height > 0) MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY) else unspecified
            )
        }
        val startWidth = start?.takeIf { it.shown() }?.measuredWidth ?: 0
        val endWidth = end.filter { it.shown() }.sumOf { it.measuredWidth }
        var height = max(minRowHeight, sides.maxOfOrNull { it.measuredHeight } ?: 0)

        expectedOrigin(measuredOrigin)
        val originX = measuredOrigin[0]
        val originY = measuredOrigin[1]
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val spans = cutouts.mapNotNull { rect ->
            val top = rect.top - originY
            val bottom = rect.bottom - originY
            if (rect.isEmpty || bottom <= 0 || top >= height) return@mapNotNull null
            // Rows below the header stay clear of the camera, too.
            height = max(height, bottom)
            val left = rect.left - originX
            val right = rect.right - originX
            // Arranged left to right as in LTR; RTL mirrors the cutout instead.
            if (rtl) (width - right - cutoutGap)..(width - left + cutoutGap) else (left - cutoutGap)..(right + cutoutGap)
        }
        val arrangement = SettingsHeaderCutoutLayout.arrange(
            width = width,
            startWidth = startWidth,
            endWidth = endWidth,
            // The title takes the widest free stretch between Back and the actions.
            middlePreferred = width,
            middleMin = width,
            padStart = paddingStart,
            padEnd = paddingEnd,
            cutouts = spans
        )
        startX = arrangement.startX
        middleX = arrangement.middleX
        endX = arrangement.endX
        middleCapacity = arrangement.middleWidth
        middle?.takeIf { it.shown() }?.measure(
            MeasureSpec.makeMeasureSpec(arrangement.middleWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun place(view: View?, x: Int): Int {
            if (view == null || !view.shown()) return x
            val left = if (rtl) width - x - view.measuredWidth else x
            val top = (height - view.measuredHeight) / 2
            view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
            return x + view.measuredWidth
        }
        place(start, startX)
        place(middle, middleX)
        var x = endX
        end.forEach { x = place(it, x) }
        // Safety net should the row not lie where it was measured for.
        getLocationInWindow(placedOrigin)
        if (cutouts.isNotEmpty() && !placedOrigin.contentEquals(measuredOrigin)) post { requestLayout() }
    }
}
