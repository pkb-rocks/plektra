package rocks.pkb.plektra.inputmethod

import android.content.Context
import androidx.core.content.edit
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * Which features need WRITE_SECURE_SETTINGS right now (adversary review 2026-10-12, finding 1.3c).
 *
 * WRITE_SECURE_SETTINGS lets this process switch Wireless debugging back on by itself, so it is
 * held only while a feature that needs it is enabled: the grant is taken when the first holder
 * appears and revoked ([PrivilegedCommand.RevokeWriteSecureSettings]) when the last one goes, or
 * on a reset. The broker itself holds nothing. The backlight (the vendor keyboard-light
 * switch the Quick Settings tile writes) calls [PrivilegedSetup.setWriteSecureSettingsNeeded] with
 * its own holder name when the user turns it on or off.
 *
 * Stored in the broker's backup-excluded preferences. Holder names are constants of the features;
 * they are only ever compared, never put in a command line.
 */
object WriteSecureSettingsHolders {
    private const val KEY = "write_secure_settings_holders"

    /**
     * the smart backlight switch. While it is on the app holds the permission, so
     * the Quick Settings tile can write the vendor keyboard-light switch in-process.
     */
    const val BACKLIGHT = "backlight"

    /**
     * the "Quick Settings tile" switch in the Smart backlight section. Keeps the
     * permission for the keyboard-light tile on its own, so the tile works with Smart backlight off.
     */
    const val BACKLIGHT_TILE = "backlight_tile"

    fun holders(context: Context): Set<String> =
        EmbeddedAdbInit.prefs(context).getStringSet(KEY, emptySet()).orEmpty().toSet()

    fun anyNeeded(context: Context): Boolean = holders(context).isNotEmpty()

    fun set(context: Context, holder: String, needed: Boolean) {
        val now = holders(context).let { if (needed) it + holder else it - holder }
        EmbeddedAdbInit.prefs(context).edit(commit = true) { putStringSet(KEY, now) }
    }

    fun clear(context: Context) {
        EmbeddedAdbInit.prefs(context).edit(commit = true) { remove(KEY) }
    }

    /**
     * What the broker should do about the permission: grant it when a feature needs it and it is
     * missing, revoke it when nothing needs it and it is held, otherwise nothing.
     */
    internal fun action(needed: Boolean, held: Boolean): PrivilegedCommand? = when {
        needed && !held -> PrivilegedCommand.GrantWriteSecureSettings
        !needed && held -> PrivilegedCommand.RevokeWriteSecureSettings
        else -> null
    }
}

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
