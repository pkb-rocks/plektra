package rocks.pkb.plektra.inputmethod

import android.content.Context
import androidx.core.content.edit
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * Remembers what the ADB broker last did, so a screen can show the truth at once instead of
 * blocking on an 8 s check, and so a failed privileged step leaves a trace.
 *
 * [EmbeddedAdbShell.lastError] is an in-memory field on a process that restarts constantly, and
 * `Log.i`/`Log.d` are stripped from release builds, so without this a step that stopped working
 * (typically because Android turned Wireless debugging off across a reboot) fails in silence.
 *
 * Stored in the broker's own preferences file ([EmbeddedAdbInit.PREFS_NAME]), which is excluded
 * from backup: a verdict restored from another phone describes a pairing this one never had.
 */
object PrivilegedDiagnostics {

    /**
     * One entry per privileged step that reports through [record]. The group that adds a step
     * adds its entry here.
     */
    enum class Step(val key: String) {
        // the always-on keyboard backlight, and the WRITE_SECURE_SETTINGS grant that
        // the Quick Settings tile writes through.
        BACKLIGHT("backlight"),
        WRITE_SECURE_SETTINGS("write_secure_settings")
    }

    const val REASON_OK = "ok"
    const val REASON_NOT_PAIRED = "not_paired"
    const val REASON_WIRELESS_DEBUGGING_OFF = "wireless_debugging_off"
    const val REASON_SHELL_FAILED = "shell_failed"

    data class Outcome(
        val step: Step,
        val ok: Boolean,
        val reason: String,
        val atMs: Long
    )

    fun record(context: Context, step: Step, ok: Boolean, reason: String) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit {
            putBoolean(okKey(step), ok)
            putString(reasonKey(step), reason)
            putLong(atKey(step), System.currentTimeMillis())
        }
    }

    /** The last recorded outcome for [step], or null if the step has never run. */
    fun last(context: Context, step: Step): Outcome? {
        val prefs = EmbeddedAdbInit.prefs(context.applicationContext)
        val at = prefs.getLong(atKey(step), 0L)
        if (at == 0L) return null
        return Outcome(
            step = step,
            ok = prefs.getBoolean(okKey(step), false),
            reason = prefs.getString(reasonKey(step), REASON_OK) ?: REASON_OK,
            atMs = at
        )
    }

    fun snapshot(context: Context): List<Outcome> =
        Step.entries.mapNotNull { last(context, it) }

    /**
     * Remember the last [EmbeddedAdbShell.verify] result, so a screen can show the truth
     * immediately and refresh it in the background.
     */
    fun recordBrokerStatus(context: Context, status: EmbeddedAdbShell.BrokerStatus) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit {
            putString(KEY_BROKER_STATUS, status.name)
            putLong(KEY_BROKER_STATUS_AT, System.currentTimeMillis())
        }
    }

    /** The last verified broker status and when it was taken, or null if never checked. */
    fun lastBrokerStatus(context: Context): Pair<EmbeddedAdbShell.BrokerStatus, Long>? {
        val prefs = EmbeddedAdbInit.prefs(context.applicationContext)
        val at = prefs.getLong(KEY_BROKER_STATUS_AT, 0L)
        if (at == 0L) return null
        val name = prefs.getString(KEY_BROKER_STATUS, null) ?: return null
        val status = runCatching { EmbeddedAdbShell.BrokerStatus.valueOf(name) }.getOrNull()
            ?: return null
        return status to at
    }

    // what the keyboard-backlight step last wrote and read back. Kept in the broker's
    // own backup-excluded prefs because both describe THIS phone's vendor setting; a value restored
    // from another phone would be a false claim.

    /**
     * True once the always-on value has been written successfully (and not reverted since). It
     * survives reboots, as the value on the phone does, so this, not live Wireless debugging, is
     * the readiness signal. A one-way latch: [observedBacklightValue] is the check on the phone.
     */
    fun backlightApplied(context: Context): Boolean =
        EmbeddedAdbInit.prefs(context.applicationContext).getBoolean(KEY_BACKLIGHT_APPLIED, false)

    fun setBacklightApplied(context: Context, applied: Boolean) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit { putBoolean(KEY_BACKLIGHT_APPLIED, applied) }
    }

    /**
     * The vendor timeout last read back off the phone, and when. Recorded off-thread after a write,
     * because reading costs a broker round-trip and must never run on the UI thread.
     */
    fun recordObservedBacklightValue(context: Context, value: String?) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit {
            putString(KEY_OBSERVED_BACKLIGHT, value)
            putLong(KEY_OBSERVED_BACKLIGHT_AT, System.currentTimeMillis())
        }
    }

    fun observedBacklightValue(context: Context): Pair<String?, Long>? {
        val prefs = EmbeddedAdbInit.prefs(context.applicationContext)
        val at = prefs.getLong(KEY_OBSERVED_BACKLIGHT_AT, 0L)
        if (at == 0L) return null
        return prefs.getString(KEY_OBSERVED_BACKLIGHT, null) to at
    }

    /**
     * Why the broker cannot run right now, or null when it should work.
     *
     * Checking Wireless debugging matters as much as checking the pairing: [EmbeddedAdbShell.isPaired]
     * only asks whether a key was ever stored, and Android disables Wireless debugging across
     * reboots, so a paired device is routinely unable to connect.
     */
    fun brokerBlocker(context: Context): String? = when {
        !EmbeddedAdbShell.isPaired(context) -> REASON_NOT_PAIRED
        !EmbeddedAdbShell.isWirelessDebuggingEnabled(context) -> REASON_WIRELESS_DEBUGGING_OFF
        else -> null
    }

    private const val KEY_BACKLIGHT_APPLIED = "privileged_backlight_applied"
    private const val KEY_OBSERVED_BACKLIGHT = "privileged_backlight_device_value"
    private const val KEY_OBSERVED_BACKLIGHT_AT = "privileged_backlight_device_value_at"

    private const val KEY_BROKER_STATUS = "privileged_broker_status"
    private const val KEY_BROKER_STATUS_AT = "privileged_broker_status_at"

    private fun okKey(step: Step) = "privileged_${step.key}_ok"
    private fun reasonKey(step: Step) = "privileged_${step.key}_reason"
    private fun atKey(step: Step) = "privileged_${step.key}_at"
}
