package rocks.pkb.plektra.ring

import android.content.Context
import rocks.pkb.plektra.inputmethod.KeyboardBacklightManager

/**
 * The vendor's keyboard-light master switch (`Settings.Global.agui_keyboard_background_light`,
 * 1 = lit, 0 = dark) as the ring sees it: three operations and nothing else.
 *
 * The backlight group owns this switch (`KeyboardBacklightManager`, with the same three operations
 * as `canWriteKeyboardLightSwitch`, `readKeyboardLightSwitch` and `writeKeyboardLightSwitch`), but
 * [RingBacklight] talks to this interface so tests can substitute it.
 *
 * [DirectKeyboardLightSwitch] delegates to `KeyboardBacklightManager`. Nothing here uses the ADB
 * broker; the switch needs only the `WRITE_SECURE_SETTINGS` grant.
 */
interface KeyboardLightSwitch {
    /** Whether the app may write the switch (it holds WRITE_SECURE_SETTINGS). */
    fun canWrite(context: Context): Boolean

    /** The switch as the vendor holds it, or [default] when the system has no value. */
    fun read(context: Context, default: Int): Int

    /** Writes 0 or 1; false when refused or when the permission is missing. */
    fun write(context: Context, value: Int): Boolean

    companion object {
        const val DARK = 0
        const val LIT = 1
    }
}

/** The vendor switch through the backlight group's [KeyboardBacklightManager], which owns it. */
object DirectKeyboardLightSwitch : KeyboardLightSwitch {
    override fun canWrite(context: Context): Boolean =
        KeyboardBacklightManager.canWriteKeyboardLightSwitch(context)

    override fun read(context: Context, default: Int): Int =
        KeyboardBacklightManager.readKeyboardLightSwitch(context, default)

    override fun write(context: Context, value: Int): Boolean =
        KeyboardBacklightManager.writeKeyboardLightSwitch(context, value)
}
