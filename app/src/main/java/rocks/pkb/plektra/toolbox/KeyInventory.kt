package rocks.pkb.plektra.toolbox

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent

/**
 * Every physical key on the Titan 2 Elite, and what it currently does.
 *
 * Bindings live in two unrelated places: vendor rows in `Settings.System` that the firmware
 * reads before any app sees the key, and the keyboard's own handling for keys that do reach the
 * IME. Neither half is much use alone. This assembles what can be read into one answer per key.
 *
 * Reads only, and deliberately no broker: `Settings.System` is readable without permission, so
 * the inventory works before pairing. Only changing a binding needs the broker.
 *
 * PhysiBoard's version also described dictation, assistant and screen-trackpad
 * triggers. Those features are separate ports; each adds its line to the key it owns here and
 * points [Key.editor] at its own screen.
 */
object KeyInventory {

    /** Where the user goes to change this key, if anywhere. */
    sealed interface Editor {
        /** Fn's modifier role and its layer mappings: Pastiera's Fn layer (nav mode) screen. */
        data object FnLayer : Editor

        /** Fixed by the system or the hardware, or owned by something this screen cannot open. */
        data object None : Editor
    }

    data class Key(
        val label: String,
        /** What the hardware reports, so an unfamiliar key can still be identified. */
        val hardware: String,
        /** What it does today, assembled from both layers. */
        val binding: String,
        val editor: Editor
    )

    private const val FN_ENABLE = "fn_programmable_key_enable"
    private const val FN_FUNCTION = "fn_programmable_key_function"
    private const val FN_LONG_ACTIVITY = "fn_long_press_activity"
    private const val FUNC1_SHORT = "func1_short_press_activity"
    private const val FUNC1_DOUBLE = "func1_double_press_activity"
    private const val FUNC1_LONG_ACTIVITY = "func1_long_press_activity"
    private const val SHIFT_L_ENABLE = "shift_l_programmable_key_enable"
    private const val SHIFT_R_ENABLE = "shift_r_programmable_key_enable"
    private const val ALT_L_ENABLE = "alt_l_programmable_key_enable"
    private const val HOME_ENABLE = "home_programmable_key_enable"
    private const val RECENT_ENABLE = "recent_programmable_key_enable"

    fun keys(context: Context): List<Key> = listOfNotNull(
        fnKey(context),
        Key("Sym", "scancode 253 (AGUI_SYM)", "Symbol and emoji pages", Editor.None),
        orangeKey(context),
        Key("Space", "keyboard matrix, also the fingerprint sensor", "Space", Editor.None),
        modifierKey(context, "Right Shift", "shift_r", SHIFT_R_ENABLE, KeyEvent.KEYCODE_SHIFT_RIGHT, always = true),
        modifierKey(context, "Left Shift", "shift_l", SHIFT_L_ENABLE, KeyEvent.KEYCODE_SHIFT_LEFT),
        modifierKey(context, "Alt", "alt_l", ALT_L_ENABLE, KeyEvent.KEYCODE_ALT_LEFT),
        vendorFlagKey(
            context, "Home", "scancode 102", HOME_ENABLE,
            onText = "Vendor remapping enabled", offText = "Home"
        ),
        vendorFlagKey(
            context, "Recent apps", "navigation key", RECENT_ENABLE,
            onText = "Vendor remapping enabled", offText = "Recent apps"
        ),
        Key("Back", "scancode 158", "Back", Editor.None),
        Key("Volume up / down", "gpio-keys 115 / 114", "Volume", Editor.None),
        Key("Power", "ff_key 116", "Power and screen lock", Editor.None)
    )

    private fun fnKey(context: Context): Key {
        val remapped = read(context, FN_ENABLE) == "1" && read(context, FN_FUNCTION) == "1"
        val parts = buildList {
            add(if (remapped) "Acts as Ctrl" else "Fn layer")
            read(context, FN_LONG_ACTIVITY)?.takeIf { !remapped }
                ?.let { add("long press opens ${shortName(it)}") }
        }
        return Key(
            label = "Fn",
            hardware = "scancode 251 (FUNC3)",
            binding = parts.joinToString(" · "),
            editor = Editor.FnLayer
        )
    }

    /**
     * The orange side key belongs to the vendor's side-key app, not to a Pastiera screen, so it
     * has no editor here: sending it to the Fn layer or trackpad page is exactly the mis-routing
     * PhysiBoard issue #18 reported.
     */
    private fun orangeKey(context: Context): Key {
        val longTarget = read(context, FUNC1_LONG_ACTIVITY)
        val parts = buildList {
            read(context, FUNC1_SHORT)?.let { add("tap: ${shortName(it)}") }
            read(context, FUNC1_DOUBLE)?.let { add("double: ${shortName(it)}") }
            add(if (longTarget != null) "hold: ${shortName(longTarget)}" else "hold: nothing")
        }
        return Key(
            label = "Orange side key",
            hardware = "ff_key 249",
            binding = parts.joinToString(" · "),
            editor = Editor.None
        )
    }

    private fun vendorFlagKey(
        context: Context,
        label: String,
        hardware: String,
        key: String,
        onText: String,
        offText: String
    ) = Key(
        label = label,
        hardware = hardware,
        binding = if (read(context, key) == "1") onText else offText,
        editor = Editor.None
    )

    /**
     * A Shift or Alt key. Shortcut keys > Key setting can make it type another modifier; the
     * firmware keeps the key code it now types in `persist.sys.agui_programmable_<key>_key`.
     * Without that property the key is listed only when [always] (Right Shift, as before).
     */
    private fun modifierKey(
        context: Context,
        label: String,
        vendorKey: String,
        enableKey: String,
        defaultKeyCode: Int,
        always: Boolean = false
    ): Key? {
        val typed = readProperty("persist.sys.agui_programmable_${vendorKey}_key")
        if (typed == null && !always) return null
        return Key(
            label = label,
            hardware = "keyboard matrix",
            binding = modifierBinding(read(context, enableKey), typed, defaultKeyCode),
            editor = Editor.None
        )
    }

    /** [enabled] is the vendor shortcut flag; [typedKeyCode] the key code the key now types. */
    internal fun modifierBinding(enabled: String?, typedKeyCode: String?, defaultKeyCode: Int): String {
        if (enabled == "1") return "Vendor remapping enabled"
        val typed = typedKeyCode?.toIntOrNull()
        return if (typed == null || typed == defaultKeyCode) {
            "Types ${modifierName(defaultKeyCode)}"
        } else {
            "Types ${modifierName(typed)} (vendor Key setting)"
        }
    }

    private fun modifierName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "Meta"
        else -> "key code $keyCode"
    }

    /** Vendor rows hold fully-qualified activities; only the tail means anything to a reader. */
    internal fun shortName(activity: String): String =
        activity.substringAfterLast('.').ifEmpty { activity }

    private fun read(context: Context, key: String): String? = runCatching {
        Settings.System.getString(context.contentResolver, key)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** A system property; `android.os.SystemProperties` is hidden API, so it is reached reflectively. */
    private fun readProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as String?
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
