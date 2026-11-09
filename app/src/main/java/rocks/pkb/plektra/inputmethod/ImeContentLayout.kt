package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import rocks.pkb.plektra.inputmethod.ui.EmojiPickerView
import android.widget.LinearLayout

/** All expanded panels share the chrome's content boundary, including canvas-based keyboards. */
internal class ImeContentLayout(context: Context) : LinearLayout(context) {
    private val position = IntArray(2)
    private val chromePosition = IntArray(2)

    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
                                         parentHeightMeasureSpec: Int, heightUsed: Int) {
        if (child is EmojiPickerView && MeasureSpec.getMode(parentHeightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val params = child.layoutParams as MarginLayoutParams
            val available = (MeasureSpec.getSize(parentHeightMeasureSpec) - paddingTop - paddingBottom -
                params.topMargin - params.bottomMargin - heightUsed).coerceAtLeast(0)
            val height = if (params.height >= 0) minOf(params.height, available) else available
            child.measure(getChildMeasureSpec(parentWidthMeasureSpec,
                paddingLeft + paddingRight + params.leftMargin + params.rightMargin + widthUsed, params.width),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        } else {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed)
        }
    }

    private fun chrome(): ImeChromeLayout? {
        var ancestor = parent
        while (ancestor != null && ancestor !is ImeChromeLayout) ancestor = ancestor.parent
        return ancestor as? ImeChromeLayout
    }

    private fun withinContent(canvas: Canvas, draw: () -> Unit) {
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        val chrome = chrome()
        val path = chrome?.geometry?.displayPathRef(chrome.width.toFloat(), chrome.height.toFloat())
        if (path != null) {
            getLocationInWindow(position)
            chrome.getLocationInWindow(chromePosition)
            val dx = (chromePosition[0] - position[0]).toFloat()
            val dy = (chromePosition[1] - position[1]).toFloat()
            canvas.translate(dx, dy)
            canvas.clipPath(path)
            canvas.translate(-dx, -dy)
        }
        draw()
        canvas.restoreToCount(save)
    }

    override fun draw(canvas: Canvas) = withinContent(canvas) { super.draw(canvas) }

    override fun dispatchDraw(canvas: Canvas) = withinContent(canvas) { super.dispatchDraw(canvas) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val chrome = chrome()
        if (chrome != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            getLocationInWindow(position)
            chrome.getLocationInWindow(chromePosition)
            if (!chrome.geometry.contains(chrome.width.toFloat(), chrome.height.toFloat(),
                    event.x + position[0] - chromePosition[0], event.y + position[1] - chromePosition[1])) return false
        }
        return super.dispatchTouchEvent(event)
    }
}
