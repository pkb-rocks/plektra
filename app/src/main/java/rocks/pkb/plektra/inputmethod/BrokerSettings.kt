package rocks.pkb.plektra.inputmethod

import android.content.Context
import androidx.core.content.edit
import moe.shizuku.manager.adb.EmbeddedAdbInit

/** The broker's user settings, kept with its other state in the backup-excluded preferences. */
object BrokerSettings {
    private const val KEY_WIRELESS_DEBUGGING_OFF_AFTER_SETUP = "wireless_debugging_off_after_setup"

    /** Finding 1.3e: on by default. */
    fun turnOffWirelessDebuggingAfterSetup(context: Context): Boolean =
        EmbeddedAdbInit.prefs(context).getBoolean(KEY_WIRELESS_DEBUGGING_OFF_AFTER_SETUP, true)

    fun setTurnOffWirelessDebuggingAfterSetup(context: Context, on: Boolean) {
        EmbeddedAdbInit.prefs(context).edit { putBoolean(KEY_WIRELESS_DEBUGGING_OFF_AFTER_SETUP, on) }
    }
}
