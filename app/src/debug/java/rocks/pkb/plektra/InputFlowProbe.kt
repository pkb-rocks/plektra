package rocks.pkb.plektra

import android.content.Context
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.data.mappings.AltModifierMappingResolver
import rocks.pkb.plektra.inputmethod.DeviceSpecific

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
        return JSONObject().apply {
            put("layoutSetting", SettingsManager.getKeyboardLayout(context))
            put("physicalProfileOverride", SettingsManager.getPhysicalKeyboardProfileOverride(context))
            put("layout", layout)
            put("alt", alt)
        }
    }
}
