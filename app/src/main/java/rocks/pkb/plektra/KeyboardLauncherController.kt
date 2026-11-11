package rocks.pkb.plektra

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

internal data class LauncherMapping(val value:String, val longPress:LauncherMapping?=null) {
    val type:String get()=if(value.isBlank()) "none" else "command"
}

/** A held modifier owns the shortcut layer; releasing it cancels deferred actions. */
internal class KeyboardLauncherController(
    private val trigger:()->Int,
    private val delay:()->Int,
    private val mappings:()->Map<Int,LauncherMapping>,
    private val ordinary:(KeyEvent)->Unit,
    private val execute:(LauncherMapping)->Unit,
    private val activated:()->Unit={},
    private val beforeExecute:()->Unit={}
) {
    private val handler=Handler(Looper.getMainLooper())
    private val dual=LauncherPressDispatcher()
    private var held:KeyEvent?=null
    private var timer:Runnable?=null
    private var replayed=false
    private var triggerUsed=false
    var active=false;private set
    private val owned=mutableSetOf<Pair<Int,Int>>()
    private fun exclusiveShift(key:Int)=key==KeyEvent.KEYCODE_SHIFT_LEFT || key==KeyEvent.KEYCODE_SHIFT_RIGHT
    private fun stopTimer() {timer?.let(handler::removeCallbacks);timer=null}
    private fun arm() {
        if(held==null || replayed || active)return
        stopTimer();triggerUsed=true;active=true;activated()
    }
    private fun launch(mapping:LauncherMapping) {
        if(active && held!=null) {
            beforeExecute()
            execute(mapping)
        }
    }
    // Some hardware layouts report both physical Shift keys as SHIFT_LEFT.
    // Linux scan codes retain the physical side; synthetic keys keep their key code.
    private fun physicalModifierKey(event:KeyEvent):Int =
        if(event.keyCode==KeyEvent.KEYCODE_SHIFT_LEFT || event.keyCode==KeyEvent.KEYCODE_SHIFT_RIGHT) {
            when(event.scanCode) {
                42 -> KeyEvent.KEYCODE_SHIFT_LEFT
                54 -> KeyEvent.KEYCODE_SHIFT_RIGHT
                else -> event.keyCode
            }
        } else event.keyCode
    fun handle(event:KeyEvent):Boolean {
        val physicalKey=physicalModifierKey(event)
        val id=event.deviceId to physicalKey
        val isHeldKey=held?.let {it.deviceId==event.deviceId && physicalModifierKey(it)==physicalKey}==true
        if(physicalKey==trigger() || isHeldKey) {
            if(event.action==KeyEvent.ACTION_DOWN) {
                if(held!=null || event.repeatCount!=0)return true
                owned.add(id)
                held=KeyEvent(event);replayed=false;triggerUsed=false;active=false
                val pending=Runnable {arm()}
                timer=pending;handler.postDelayed(pending,delay().toLong());return true
            }
            if(event.action==KeyEvent.ACTION_UP) {
                stopTimer();owned.remove(id)
                val down=held ?: return true
                // Always stop before key-up: no delayed shortcut may reach the new editor.
                active=false;dual.cancel()
                if(!triggerUsed && !exclusiveShift(down.keyCode)) {
                    if(!replayed && !event.isCanceled)ordinary(down)
                    if(replayed || !event.isCanceled)ordinary(event)
                }
                held=null;replayed=false;return true
            }
            return true
        }
        if(event.action==KeyEvent.ACTION_UP && owned.remove(id)) {
            dual.handle(event,null,active && held!=null,delay()) {chosen,_->launch(chosen)}
            return true
        }
        if(owned.contains(id))return true
        if(event.action!=KeyEvent.ACTION_DOWN)return false
        held?.let { down ->
            if(!triggerUsed && !replayed) {
                if(exclusiveShift(down.keyCode)) {
                    // A dedicated Shift can chord immediately, without a second waiting period.
                    arm()
                } else if(event.eventTime-down.eventTime>=delay()) {
                    // Honor timestamps even if the main-thread activation timer was delayed.
                    arm()
                } else {
                    // A quick Fn chord remains an ordinary Fn chord; two quick taps retain Nav.
                    stopTimer();replayed=true;ordinary(down)
                }
            }
        }
        if(!active || held==null)return false
        dual.cancel();owned.add(id)
        if(event.keyCode==KeyEvent.KEYCODE_ESCAPE || event.keyCode==KeyEvent.KEYCODE_BACK) {
            active=false;return true
        }
        val mapping=mappings()[event.keyCode]
        if(mapping==null || mapping.type=="none" && mapping.longPress==null)return true
        if(dual.handle(event,mapping,true,delay()) {chosen,_->launch(chosen)})return true
        if(event.repeatCount==0)launch(mapping)
        return true
    }
    fun cancel() {
        stopTimer()
        if(replayed)held?.let {ordinary(KeyEvent.changeAction(it,KeyEvent.ACTION_UP))}
        held=null;replayed=false;active=false;dual.cancel()
    }
    fun consumeCanceled(event:KeyEvent):Boolean {
        val id=event.deviceId to physicalModifierKey(event)
        if(event.action==KeyEvent.ACTION_UP && owned.remove(id)) return true
        return event.action==KeyEvent.ACTION_DOWN && event.repeatCount!=0 && owned.contains(id)
    }
    fun clear() {cancel();owned.clear();dual.clear()}
}
