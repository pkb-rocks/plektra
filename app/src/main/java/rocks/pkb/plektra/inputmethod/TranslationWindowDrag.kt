package rocks.pkb.plektra.inputmethod

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Long press the title to move; the editable text keeps its normal selection gestures. */
internal class TranslationWindowDrag(private val title: View,
    private val position: () -> Pair<Int, Int>, private val bounds: () -> Rect,
    private val size: () -> Pair<Int, Int>, private val move: (Int, Int) -> Unit,
    private val requireLongPress: Boolean = true) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private var contact = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var origin = 0 to 0
    private val begin = Runnable { if (contact) { dragging = true; title.isPressed = true } }
    private val slop = ViewConfiguration.get(title.context).scaledTouchSlop
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel(); contact = true; downX = event.rawX; downY = event.rawY; origin = position()
                if(requireLongPress) handler.postDelayed(begin, ViewConfiguration.getLongPressTimeout().toLong())
                else { dragging = true; title.isPressed = true }
            }
            MotionEvent.ACTION_MOVE -> if (contact) {
                if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) cancel()
                if (dragging) {
                    val (width, height) = size()
                    val point = constrain(origin.first + (event.rawX - downX).toInt(),
                        origin.second + (event.rawY - downY).toInt(), width, height, bounds())
                    move(point.first, point.second)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
    fun cancel() { handler.removeCallbacks(begin); contact = false; dragging = false; title.isPressed = false }
    companion object {
        fun constrain(x: Int, y: Int, width: Int, height: Int, bounds: Rect): Pair<Int, Int> =
            x.coerceIn(bounds.left, (bounds.right - width).coerceAtLeast(bounds.left)) to
                y.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top))
    }
}
