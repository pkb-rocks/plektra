package rocks.pkb.plektra

import android.content.Context
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.data.mappings.AltModifierMappingResolver
import rocks.pkb.plektra.inputmethod.BounceKeyFilter
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import rocks.pkb.plektra.inputmethod.MultiTapController

/** Read-only device and mapping facts for `scripts/input_flow.py`. */
object InputFlowProbe {
    private val metaStates = linkedMapOf(
        "base" to 0,
        "shift" to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        "alt" to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
        "shift+alt" to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
    )

    /** Android's view of every keyboard plus Plektra's profile decision for it. */
    fun devices(context: Context): JSONArray {
        val override = SettingsManager.getPhysicalKeyboardProfileOverride(context)
        val result = JSONArray()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.keyboardType == InputDevice.KEYBOARD_TYPE_NONE) continue
            val profile = DeviceSpecific.resolveInputProfile(device, override)
            val allCodes = IntArray(KeyEvent.getMaxKeyCode() + 1) { it }
            val present = device.hasKeys(*allCodes)
            val keyCharacterMap = device.keyCharacterMap
            val keys = JSONObject()
            allCodes.filter { present[it] }.forEach { code ->
                keys.put(KeyEvent.keyCodeToString(code), JSONObject().apply {
                    put("label", keyCharacterMap.getDisplayLabel(code).takeIf { it.code != 0 }?.toString() ?: "")
                    metaStates.forEach { (name, meta) ->
                        put(name, describe(keyCharacterMap.get(code, meta)))
                    }
                })
            }
            result.put(JSONObject().apply {
                put("id", device.id)
                put("name", device.name)
                put("vendorId", device.vendorId)
                put("productId", device.productId)
                put("isExternal", device.isExternal)
                put("isVirtual", device.isVirtual)
                put("keyboardType", device.keyboardType)
                put("sources", device.sources)
                put("kcmType", keyCharacterMap.keyboardType)
                put("profile", profile.profileId)
                put("profileKind", profile.kind.name)
                put("profileAutoDetected", profile.autoDetected)
                put("keys", keys)
            })
        }
        return result
    }

    /** Note ids and the active id, read without NotesRepository's migration write; no note content. */
    fun notes(context: Context): JSONObject {
        val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
        val collection = prefs.getString("collection", null)?.let(::JSONArray) ?: JSONArray()
        return JSONObject().apply {
            put("ids", JSONArray((0 until collection.length()).map { collection.getJSONObject(it).getString("id") }))
            put("active", prefs.getString("active_note", null) ?: JSONObject.NULL)
        }
    }

    /**
     * The real Notes editor's body; the UI hierarchy reports an empty field's hint as its text.
     */
    fun noteBody(): JSONObject? {
        val bodies = mutableListOf<android.widget.EditText>()
        fun find(view: android.view.View) {
            if (view is android.widget.EditText && view.id == R.id.notes_body && view.isShown &&
                view.context.javaClass == NotesActivity::class.java) bodies += view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))
        }
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::find)
        val body = bodies.singleOrNull() ?: return null
        return JSONObject().apply {
            put("text", body.text.toString())
            put("focused", body.hasFocus())
        }
    }

    private fun describe(unicode: Int): String {
        val codePoint = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
        val text = if (codePoint == 0) "" else String(Character.toChars(codePoint))
        return if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) "dead:$text" else text
    }

    /** Text Plektra resolves per hardware key code in the active layout and Alt binding. */
    fun keymap(context: Context): JSONObject {
        val layout = JSONObject()
        LayoutMappingRepository.getLayout().forEach { (code, mapping) ->
            layout.put(KeyEvent.keyCodeToString(code), JSONObject().apply {
                put("lowercase", mapping.lowercase)
                put("uppercase", mapping.uppercase)
                put("multiTap", mapping.taps.size > 1 && mapping.multiTapEnabled)
            })
        }
        val alt = JSONObject()
        AltModifierMappingResolver.resolve(context.assets, context).forEach { (code, text) ->
            alt.put(KeyEvent.keyCodeToString(code), text)
        }
        val bounceKeys = SettingsManager.getBounceKeysEnabled(context) &&
            SettingsManager.getBounceKeysCategoryEnabled(context, BounceKeyFilter.Category.CHARACTER)
        return JSONObject().apply {
            put("multiTapTimeoutMs", MultiTapController.TIMEOUT_MS)
            put("bounceKeysDelayMs", if (bounceKeys) SettingsManager.getBounceKeysDelayMs(context) else 0L)
            put("layoutSetting", SettingsManager.getKeyboardLayout(context))
            put("physicalProfileOverride", SettingsManager.getPhysicalKeyboardProfileOverride(context))
            put("layout", layout)
            put("alt", alt)
        }
    }
}
