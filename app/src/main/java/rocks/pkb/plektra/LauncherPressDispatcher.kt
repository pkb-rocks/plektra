package rocks.pkb.plektra

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/** Owns both edges of dual-action keys. All callbacks run on the main thread. */
internal class LauncherPressDispatcher {
    private val handler = Handler(Looper.getMainLooper())
    private data class Press(val down: KeyEvent, val short: LauncherMapping, val long: LauncherMapping,
        val execute: (LauncherMapping, KeyEvent) -> Unit, var completed: Boolean = false,
        var timer: Runnable? = null)
    private val presses = mutableMapOf<Pair<Int, Int>, Press>()
    fun owns(event: KeyEvent): Boolean = presses.containsKey(event.deviceId to event.keyCode)
    fun handle(event: KeyEvent, mapping: LauncherMapping?, active: Boolean, delay: Int,
        execute: (LauncherMapping, KeyEvent) -> Unit): Boolean {
        val id = event.deviceId to event.keyCode
        if(event.action == KeyEvent.ACTION_UP) {
            val press = presses.remove(id) ?: return false
            press.timer?.let(handler::removeCallbacks)
            if(!press.completed && active && !event.isCanceled) {
                press.completed = true
                // Event timestamps decide even if the UI thread processed a release before the timer.
                val chosen = if(event.eventTime - press.down.eventTime >= delay) press.long else press.short
                if(chosen.type != "none") press.execute(chosen, press.down)
            }
            return true
        }
        if(event.action != KeyEvent.ACTION_DOWN) return false
        presses[id]?.let { previous ->
            if(event.repeatCount != 0 || previous.down.downTime == event.downTime) return true
            previous.timer?.let(handler::removeCallbacks)
            presses.remove(id) // recover after a canceled/missing release
        }
        val long = mapping?.longPress?.takeIf { it.type != "none" } ?: return false
        if(!active || event.repeatCount != 0) return false
        // A second physical key cancels the first action rather than launching both unexpectedly.
        cancel()
        val press = Press(KeyEvent(event), mapping.copy(longPress = null), long, execute)
        val timer = Runnable {
            if(presses[id] === press && !press.completed) {
                press.completed = true
                press.execute(press.long, press.down)
            }
        }
        press.timer = timer
        presses[id] = press
        handler.postDelayed(timer, delay.toLong())
        return true
    }
    /** Keep ownership until release, so a canceled key-up never leaks into a newly focused editor. */
    fun cancel() {
        presses.values.forEach { it.completed = true; it.timer?.let(handler::removeCallbacks) }
    }
    fun clear() { cancel(); presses.clear() }
}
