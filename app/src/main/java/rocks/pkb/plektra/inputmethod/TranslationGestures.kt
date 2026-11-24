package rocks.pkb.plektra.inputmethod

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import kotlin.math.sign

/** Translation picker and preview only. A drag chooses; only a stationary double tap applies the choice. */
internal class TranslationGestures {
    private var connection: InputConnection? = null
    private var target: String? = null
    private var down = false
    private var x0 = 0f
    private var y0 = 0f
    private var lastX = 0f
    private var downAt = 0L
    private var moved = false
    private var horizontal = false
    private var vertical = false
    private var accumulated = 0f
    private var firstTapAt = -1L
    private var firstTapX = 0f
    private var firstTapY = 0f
    private var secondTap = false

    fun reset() {
        connection = null; target = null; down = false; firstTapAt = -1L
        moved = false; horizontal = false; vertical = false; accumulated = 0f; secondTap = false
    }
    fun motion(ownPackage: String, info: EditorInfo?, input: InputConnection?, event: MotionEvent,
               x: Float = event.x, y: Float = event.y, reviewTarget: String? = null,
               onCommand: ((String, Int) -> Unit)? = null): Boolean {
        if ((onCommand == null && !active(ownPackage, info)) || input == null ||
            (onCommand != null && reviewTarget.isNullOrBlank())) { reset(); return false }
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD) &&
            InputDevice.getDevice(event.deviceId)?.name?.contains("touchpad", ignoreCase = true) != true) return false
        val token = reviewTarget ?: info?.extras?.getString(TARGET)
        if (input !== connection || token != target) { reset(); connection = input; target = token }
        if (!x.isFinite() || !y.isFinite() || event.pointerCount != 1) { down = false; firstTapAt = -1; return true }
        fun send(operation: String, steps: Int = 0) {
            if (onCommand != null) { onCommand(operation, steps); return }
            input.performPrivateCommand("plektra.translation.ACTION", Bundle().apply {
                putString("operation", operation); putInt("steps", steps); putString(TARGET, token)
            })
        }
        fun move() {
            if (!down) return
            val dx = x - x0; val dy = y - y0
            if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) { moved = true; firstTapAt = -1 }
            var justStarted = false
            if (!horizontal && !vertical) {
                if (abs(dx) > TAP_SLOP && abs(dx) > abs(dy) * 1.2f) { horizontal = true; justStarted = true }
                else if (abs(dy) > TAP_SLOP && abs(dy) > abs(dx) * 1.2f) vertical = true
            }
            if (horizontal) {
                accumulated = if (justStarted) dx else accumulated + (x - lastX)
                val count = ((abs(accumulated) + .00001f) / STEP).toInt().coerceAtMost(5)
                if (count > 0) {
                    val steps = count * accumulated.sign.toInt()
                    send(MOVE, steps); accumulated -= steps * STEP
                }
            }
            lastX = x
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                secondTap = firstTapAt >= 0 && event.eventTime - firstTapAt in 0..DOUBLE_TAP_MS &&
                    abs(x - firstTapX) <= DOUBLE_TAP_SLOP && abs(y - firstTapY) <= DOUBLE_TAP_SLOP
                if (!secondTap) firstTapAt = -1
                down = true; downAt = event.eventTime; x0 = x; y0 = y; lastX = x
                moved = false; horizontal = false; vertical = false; accumulated = 0f
            }
            MotionEvent.ACTION_MOVE -> move()
            MotionEvent.ACTION_UP -> {
                if (down) {
                    move()
                    val tap = !moved && event.eventTime - downAt in 0..MAX_TAP_MS
                    if (tap && secondTap) { firstTapAt = -1; send(ACCEPT) }
                    else if (tap) { firstTapAt = event.eventTime; firstTapX = x; firstTapY = y }
                    else firstTapAt = -1
                }
                down = false
            }
            MotionEvent.ACTION_CANCEL -> { down = false; firstTapAt = -1 }
        }
        // Never let a review contact also insert a normal suggestion or reposition its word.
        return true
    }
    companion object {
        const val ACTIVE = "plektra.translation.ACTIVE"
        const val TARGET = "plektra.translation.TARGET"
        const val MOVE = "spellReviewMove"
        const val ACCEPT = "spellReviewAccept"
        private const val STEP = .10f
        private const val TAP_SLOP = .025f
        private const val DOUBLE_TAP_SLOP = .10f
        private const val DOUBLE_TAP_MS = 300L
        private const val MAX_TAP_MS = 400L
        fun active(ownPackage: String, info: EditorInfo?) = info?.packageName == ownPackage &&
            info.privateImeOptions == "plektra.translation" && info.extras?.getBoolean(ACTIVE, false) == true &&
            !info.extras?.getString(TARGET).isNullOrBlank()
        fun coordinate(event: MotionEvent, axis: Int, fallbackExtent: Int): Float {
            val device = InputDevice.getDevice(event.deviceId)
            val range = device?.getMotionRange(axis, event.source) ?: device?.getMotionRange(axis)
            val valid = range != null && range.min.isFinite() && range.range.isFinite() && range.range > 0f
            val value = if (axis == MotionEvent.AXIS_X) event.x else event.y
            return if (valid) (value - range!!.min) / range.range else value / fallbackExtent.coerceAtLeast(1)
        }
    }
}
