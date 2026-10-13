package rocks.pkb.plektra.inputmethod

import android.content.Context
import androidx.core.content.edit
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * Remembers what the ADB broker last did, so a screen can show the truth at once instead of
 * blocking on an 8 s check.
 *
 * Stored in the broker's own preferences file ([EmbeddedAdbInit.PREFS_NAME]), which is excluded
 * from backup: a verdict restored from another phone describes a pairing this one never had.
 */
object PrivilegedDiagnostics {

    const val REASON_NOT_PAIRED = "not_paired"
    const val REASON_WIRELESS_DEBUGGING_OFF = "wireless_debugging_off"

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

    private const val KEY_BROKER_STATUS = "privileged_broker_status"
    private const val KEY_BROKER_STATUS_AT = "privileged_broker_status_at"
}
