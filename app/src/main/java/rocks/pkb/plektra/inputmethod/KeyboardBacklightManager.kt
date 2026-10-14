package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.provider.Settings

/**
 * Keeps the Unihertz keyboard backlight on beyond the stock 30 s cap, using a PERSISTENT vendor
 * setting instead of a fragile per-session LED hold.
 *
 * The vendor stores a keyboard-backlight timeout that accepts the sentinel `-1` = "never turn
 * off". It is written through the vendor binder service ([PrivilegedCommand.WriteBacklightTimeout]):
 *   - ALWAYS ON: keyboard_brightness_timeout = "-1"
 *   - REVERT:    keyboard_brightness_timeout = "30000" (the stock 30 s default)
 *
 * The value is a STORED setting, so it takes effect at once and SURVIVES REBOOTS: no light sensor,
 * no screen receivers, no per-keystroke re-latch, no per-reboot re-arm. Shell privilege is needed
 * only to WRITE it, once, through the app's own embedded wireless-ADB broker. Every broker call
 * goes through the typed [PrivilegedCommand] set, runs off the main thread and never throws, so a
 * broker that cannot connect is a silent no-op (the user can also change the value from the
 * vendor menu).
 *
 * Nothing here is called from the IME's key-handling path, and nothing here turns Wireless
 * debugging on (broker review 1.2b, 1.2c).
 *
 * The second half of this object is the in-process vendor switch (`agui_keyboard_background_light`)
 * that the Quick Settings tile toggles. It needs no broker at all while WRITE_SECURE_SETTINGS is
 * held, which is while a feature that needs it is on ([WriteSecureSettingsHolders.BACKLIGHT_TILE],
 * [WriteSecureSettingsHolders.BACKLIGHT], [WriteSecureSettingsHolders.RING_KEYBOARD_DARK]; see [PrivilegedSetup.setWriteSecureSettingsNeeded]).
 *
 * Every broker call takes the [BrokerTrigger] of the user action behind it (finding 1.3g).
 */
object KeyboardBacklightManager {

    // ---- always-on timeout, through the broker --------------------------------------------------

    /** Persist "keyboard backlight never turns off". Best-effort, off-thread, never throws. */
    fun applyAlwaysOn(context: Context, trigger: BrokerTrigger?) =
        runSet(context, PrivilegedCommand.BacklightTimeout.ALWAYS_ON, trigger)

    /** Restore the stock 30 s timeout. Best-effort, off-thread, never throws. */
    fun revertToDefault(context: Context, trigger: BrokerTrigger?) =
        runSet(context, PrivilegedCommand.BacklightTimeout.STOCK, trigger)

    /**
     * Reads the timeout the device actually has, or null if it could not be read. A read, so an
     * open screen's trigger is enough ([PrivilegedCommand.readOnly]).
     * BLOCKING on a broker round-trip; call off the main thread.
     */
    fun readAppliedValue(context: Context, trigger: BrokerTrigger?): String? {
        val result = PrivilegedShell.run(context, PrivilegedCommand.ReadBacklightTimeout, trigger)
        if (!result.ok) return null
        return parseParcelString(result.output)
    }

    /** True when the device is confirmed to hold the always-on sentinel. BLOCKING; off-thread. */
    fun isAlwaysOnOnDevice(context: Context, trigger: BrokerTrigger?): Boolean =
        readAppliedValue(context, trigger) == PrivilegedCommand.BacklightTimeout.ALWAYS_ON.wireValue

    /**
     * Pulls the String16 out of a `service call` result parcel.
     *
     * The output looks like `Result: Parcel(00000000 00000002 0031002d ... '....-.1....')`: word 0
     * is the exception code, word 1 the character count, and the rest pack two little-endian
     * UTF-16 units per word. Parsing the hex is exact; scraping the quoted ASCII rendering beside
     * it would lose any character it prints as a dot.
     */
    internal fun parseParcelString(raw: String?): String? {
        val parcel = raw?.substringAfter("Parcel(", "")?.takeIf { it.isNotEmpty() } ?: return null
        val words = Regex("[0-9a-fA-F]{8}")
            .findAll(parcel.substringBefore("'"))
            .map { it.value.toLong(16) }
            .toList()
        if (words.size < 2) return null
        val length = words[1].toInt()
        if (length <= 0 || length > 64) return null

        val out = StringBuilder(length)
        var index = 2
        while (out.length < length && index < words.size) {
            val word = words[index]
            out.append((word and 0xFFFF).toInt().toChar())
            if (out.length < length) {
                out.append(((word shr 16) and 0xFFFF).toInt().toChar())
            }
            index++
        }
        return out.toString().takeIf { it.length == length }
    }

    /**
     * Writes the vendor timeout off-thread. On a successful write it records the persisted
     * readiness flag ([PrivilegedDiagnostics.setBacklightApplied]), `true` for the always-on value
     * and `false` for the revert, so the UI can key readiness off "configured once" rather than
     * live Wireless-debugging state.
     */
    private fun runSet(context: Context, timeout: PrivilegedCommand.BacklightTimeout, trigger: BrokerTrigger?) {
        val appContext = context.applicationContext
        // Cheap gate: without a working broker the write can't land, so don't queue an ~8 s mDNS
        // discovery. Checking Wireless debugging as well as the pairing matters: Android turns it
        // off across reboots, and isPaired() only asks whether a key was ever stored. Recording the
        // reason is the difference between "backlight stopped and I can't get it back" and a screen
        // that says which switch to flip.
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            PrivilegedDiagnostics.record(
                appContext, PrivilegedDiagnostics.Step.BACKLIGHT, ok = false, reason = blocker
            )
            return
        }
        PrivilegedShell.runAsync {
            val result = PrivilegedShell.run(appContext, PrivilegedCommand.WriteBacklightTimeout(timeout), trigger)
            if (result.ok) {
                PrivilegedDiagnostics.setBacklightApplied(
                    appContext,
                    timeout == PrivilegedCommand.BacklightTimeout.ALWAYS_ON
                )
                // Already off the main thread and already past the broker, so ask the phone what it
                // holds now rather than assuming the write took.
                PrivilegedDiagnostics.recordObservedBacklightValue(
                    appContext,
                    runCatching { readAppliedValue(appContext, trigger) }.getOrNull()
                )
            }
            PrivilegedDiagnostics.record(
                appContext,
                PrivilegedDiagnostics.Step.BACKLIGHT,
                ok = result.ok,
                reason = if (result.ok) {
                    PrivilegedDiagnostics.REASON_OK
                } else {
                    result.failure ?: PrivilegedDiagnostics.REASON_SHELL_FAILED
                }
            )
        }
    }

    // ---- the vendor on/off switch, in-process ---------------------------------------------------

    /** The vendor's keyboard-backlight master switch (`Settings.Global`). Shared with the QS tile. */
    const val KEYBOARD_LIGHT_SWITCH = "agui_keyboard_background_light"

    /**
     * Hook for the ring group ("keyboard dark while the ring is lit", PhysiBoard
     * e8d997c0): these three are the whole in-process surface for the vendor switch, so the ring
     * can darken and restore the keyboard through the same row the tile toggles, without a broker.
     * [writeKeyboardLightSwitch] takes only 0 or 1; any other value is refused.
     */
    fun canWriteKeyboardLightSwitch(context: Context): Boolean =
        PrivilegedSetup.hasWriteSecureSettings(context)

    /** The switch as the vendor holds it (1 = lit), or [default] when the system has no value. */
    fun readKeyboardLightSwitch(context: Context, default: Int = KEYBOARD_LIGHT_ON): Int =
        runCatching {
            Settings.Global.getInt(context.contentResolver, KEYBOARD_LIGHT_SWITCH, default)
        }.getOrDefault(default)

    /** Writes the switch (0 or 1). False when refused, or when the permission is missing. */
    fun writeKeyboardLightSwitch(context: Context, value: Int): Boolean {
        if (value != KEYBOARD_LIGHT_OFF && value != KEYBOARD_LIGHT_ON) return false
        return runCatching {
            Settings.Global.putInt(context.contentResolver, KEYBOARD_LIGHT_SWITCH, value)
        }.getOrDefault(false)
    }

    const val KEYBOARD_LIGHT_OFF = 0
    const val KEYBOARD_LIGHT_ON = 1
}
