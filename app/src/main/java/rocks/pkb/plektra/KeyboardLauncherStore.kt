package rocks.pkb.plektra

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

internal object KeyboardLauncherStore {
    const val ENABLED="shortcut_layer_enabled"
    const val TRIGGER="shortcut_layer_trigger"
    const val DELAY="shortcut_layer_delay_ms"
    const val ASSIGNMENTS="shortcut_layer_mappings"
    fun enabled(context:Context)=SettingsManager.getPreferences(context).getBoolean(ENABLED,false)
    fun trigger(context:Context)=SettingsManager.getPreferences(context).getInt(TRIGGER,KeyEvent.KEYCODE_SHIFT_LEFT)
        .takeIf {it in setOf(KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT,KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.KEYCODE_CTRL_RIGHT)} ?: KeyEvent.KEYCODE_SHIFT_LEFT
    fun delay(context:Context)=SettingsManager.getPreferences(context).getInt(DELAY,400).coerceIn(250,800)
    fun read(context:Context):Map<Int,LauncherMapping> = try {
        val root=JSONObject(SettingsManager.getPreferences(context).getString(ASSIGNMENTS,"{}") ?: "{}")
        buildMap {
            for(key in root.keys()) {
                val code=key.toIntOrNull()?.takeIf {it in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z} ?: continue
                val item=root.optJSONObject(key) ?: continue
                fun command(obj:JSONObject?):String = obj?.takeIf {it.optString("type")=="command"}?.optString("command")
                    ?.takeIf {it.isNotBlank() && it.length<=512 && it.none {ch->ch.isISOControl()}} ?: ""
                val short=command(item);val long=command(item.optJSONObject("longPress"))
                if(short.isNotEmpty() || long.isNotEmpty()) put(code,LauncherMapping(short,long.takeIf {it.isNotEmpty()}?.let(::LauncherMapping)))
            }
        }
    } catch(_:Exception) {emptyMap()}
    fun save(context:Context,mappings:Map<Int,LauncherMapping>) {
        val json=JSONObject()
        mappings.filterKeys {it in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z}.forEach {(key,value)->
            if(value.type!="none" || value.longPress?.type=="command") {
                val entry=JSONObject().put("type",if(value.type=="none")"none" else "command")
                if(value.type!="none") entry.put("command",value.value)
                value.longPress?.takeIf {it.type=="command"}?.let {entry.put("longPress",JSONObject().put("type","command").put("command",it.value))}
                json.put(key.toString(),entry)
            }
        }
        SettingsManager.getPreferences(context).edit().putString(ASSIGNMENTS,json.toString()).apply()
    }
}

/** Optional system key filtering and the IME share one state machine, not two competing owners. */
internal object KeyboardLauncherBridge {
    interface Target {fun dispatchKeyboardLauncher(event:KeyEvent):Boolean;fun cancelKeyboardLauncher()}
    @Volatile private var target:Target?=null
    fun register(value:Target) {target=value}
    fun unregister(value:Target) {if(target===value)target=null}
    fun dispatch(event:KeyEvent)=target?.dispatchKeyboardLauncher(event)==true
    fun cancel() {target?.cancelKeyboardLauncher()}
}
