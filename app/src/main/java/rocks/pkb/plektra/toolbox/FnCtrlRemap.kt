package rocks.pkb.plektra.toolbox

import android.content.Context
import android.provider.Settings
import android.util.Log
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedBackends
import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import rocks.pkb.plektra.inputmethod.PrivilegedShell
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * Fn -> Ctrl at the system level (experimental, Titan 2 Elite).
 *
 * Unihertz stores the programmable-key mapping in `Settings.System` as integers. `function = 1`
 * is INFERRED to mean Ctrl from a Titan 2 Elite that had Fn=Ctrl set; it may differ on other
 * firmware, and a reboot may be needed for it to take effect.
 *
 * The write goes through `Settings.System` when "Modify system settings" is granted, and
 * otherwise through the broker's typed [PrivilegedCommand.SetFnProgrammableKey]. Either way the
 * result is confirmed by reading the two rows back through the Settings API, never from shell
 * output. The device's ORIGINAL values are captured once, before the first overwrite, so a reset
 * restores the exact prior state. The capture lives in the broker's preferences file
 * ([EmbeddedAdbInit.PREFS_NAME]), which is excluded from backups: it describes this phone.
 *
 * BLOCKING on the broker path — call off the main thread, never from the IME input path.
 */
object FnCtrlRemap {

    private const val TAG = "FnCtrlRemap"

    /** Sentinel for "the system had no value for this key when we captured it". */
    const val VALUE_UNSET = Int.MIN_VALUE

    private const val KEY_CAPTURED = "toolbox_fn_ctrl_captured"
    private const val KEY_PREV_ENABLE = "toolbox_fn_ctrl_prev_enable"
    private const val KEY_PREV_FUNCTION = "toolbox_fn_ctrl_prev_function"

    private const val FN_KEY_ENABLE = PrivilegedCommand.SetFnProgrammableKey.KEY_ENABLE
    private const val FN_KEY_FUNCTION = PrivilegedCommand.SetFnProgrammableKey.KEY_FUNCTION

    enum class Result { SUCCESS, NEEDS_PERMISSION, FAILED }

    /** True when the system already reports Fn mapped to Ctrl (enable=1, function=1). */
    fun isSet(context: Context): Boolean = readPair(context) == (1 to 1)

    /** Apply Fn -> Ctrl. Captures the original values first (once). */
    fun apply(context: Context, trigger: BrokerTrigger?): Result {
        captureOriginalIfNeeded(context)
        val result = write(context, 1, 1, trigger)
        // Nothing was written, so a capture taken now would go stale before the next attempt.
        if (result == Result.NEEDS_PERMISSION) clearCapture(context)
        return result
    }

    /**
     * Undo [apply]: restore the captured original values, or disable the remap (0/0) when the
     * original was unset or never captured. On success the capture is cleared.
     */
    fun revert(context: Context, trigger: BrokerTrigger?): Result {
        val prefs = prefs(context)
        val captured = prefs.getBoolean(KEY_CAPTURED, false)
        val target = revertTarget(
            captured,
            prefs.getInt(KEY_PREV_ENABLE, VALUE_UNSET),
            prefs.getInt(KEY_PREV_FUNCTION, VALUE_UNSET)
        )
        val result = write(context, target.first, target.second, trigger)
        if (result == Result.SUCCESS) clearCapture(context)
        return result
    }

    /** What to write back: the captured value when there is a usable one, otherwise 0. */
    internal fun revertTarget(captured: Boolean, prevEnable: Int, prevFunction: Int): Pair<Int, Int> {
        fun usable(v: Int) = v in 0..PrivilegedCommand.SetFnProgrammableKey.MAX_VALUE
        return (if (captured && usable(prevEnable)) prevEnable else 0) to
            (if (captured && usable(prevFunction)) prevFunction else 0)
    }

    private fun write(context: Context, enable: Int, function: Int, trigger: BrokerTrigger?): Result {
        if (Settings.System.canWrite(context)) {
            val ok = runCatching {
                Settings.System.putInt(context.contentResolver, FN_KEY_ENABLE, enable)
                Settings.System.putInt(context.contentResolver, FN_KEY_FUNCTION, function)
            }.getOrDefault(false)
            return if (ok && readPair(context) == (enable to function)) Result.SUCCESS else Result.FAILED
        }
        if (PrivilegedBackends.hasAccess(context)) {
            if (enable !in 0..PrivilegedCommand.SetFnProgrammableKey.MAX_VALUE ||
                function !in 0..PrivilegedCommand.SetFnProgrammableKey.MAX_VALUE
            ) return Result.FAILED
            val ran = PrivilegedShell.run(context, PrivilegedCommand.SetFnProgrammableKey(enable, function), trigger).ok
            // The shell is not the witness: read the rows back through the Settings API.
            val seen = readPair(context)
            if (ran && seen != (enable to function)) Log.w(TAG, "Fn rows read $seen after writing $enable/$function")
            return if (ran && seen == (enable to function)) Result.SUCCESS else Result.FAILED
        }
        return Result.NEEDS_PERMISSION
    }

    private fun readPair(context: Context): Pair<Int, Int> = runCatching {
        val cr = context.contentResolver
        Settings.System.getInt(cr, FN_KEY_ENABLE, -1) to Settings.System.getInt(cr, FN_KEY_FUNCTION, -1)
    }.getOrDefault(-1 to -1)

    /**
     * Snapshot the device's ORIGINAL programmable-key values before we first overwrite them. No-op
     * once captured. UNSET is stored when the system has no value for a key, so a revert can fall
     * back to "off".
     */
    private fun captureOriginalIfNeeded(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_CAPTURED, false)) return
        val cr = context.contentResolver
        val enable = runCatching { Settings.System.getInt(cr, FN_KEY_ENABLE, VALUE_UNSET) }.getOrDefault(VALUE_UNSET)
        val function = runCatching { Settings.System.getInt(cr, FN_KEY_FUNCTION, VALUE_UNSET) }.getOrDefault(VALUE_UNSET)
        prefs.edit()
            .putInt(KEY_PREV_ENABLE, enable)
            .putInt(KEY_PREV_FUNCTION, function)
            .putBoolean(KEY_CAPTURED, true)
            .apply()
    }

    private fun clearCapture(context: Context) {
        prefs(context).edit()
            .remove(KEY_CAPTURED)
            .remove(KEY_PREV_ENABLE)
            .remove(KEY_PREV_FUNCTION)
            .apply()
    }

    private fun prefs(context: Context) = EmbeddedAdbInit.prefs(context.applicationContext)
}
