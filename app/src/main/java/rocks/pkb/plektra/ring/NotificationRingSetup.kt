package rocks.pkb.plektra.ring

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.AppNotificationListener
import rocks.pkb.plektra.inputmethod.BrokerGrantLedger
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.inputmethod.PrivilegedShell
import rocks.pkb.plektra.inputmethod.WriteSecureSettingsHolders

/**
 * The grants the ring needs, and the one pairing that supplies all of them.
 *
 * Notification access and the full-screen permission are each a trip into system settings
 * for the user. The paired broker can grant both in one shell line, which is why this is
 * wired into [rocks.pkb.plektra.inputmethod.PrivilegedSetup]: turn the ring on, and a
 * phone paired once is set up without another tap. The settings intents remain as the route
 * for a phone that was never paired.
 *
 * Every broker command goes through [PrivilegedCommand.RingGrants], a closed set; this object never
 * builds a shell string. The outcome is always read back from a local API, not from the shell.
 * Nothing here turns Wireless debugging on, and nothing here runs from the IME's key path. Every
 * broker call takes the [BrokerTrigger] of the user action behind it.
 *
 * The keyboard-dark switch needs WRITE_SECURE_SETTINGS. It no longer has a grant of its own: it is
 * the [WriteSecureSettingsHolders.RING_KEYBOARD_DARK] holder, so a reset or the last holder going
 * revokes it. Notification access the broker gives is recorded in [BrokerGrantLedger], so a reset
 * withdraws it.
 */
object NotificationRingSetup {
    private const val TAG = "NotificationRing"

    fun isListenerGranted(context: Context): Boolean =
        NotificationRingListener.isAccessGranted(context)

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }

    fun canPostNotifications(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    /** Everything is in place for a ring to actually appear. */
    fun isReady(context: Context): Boolean =
        isListenerGranted(context) && canUseFullScreenIntent(context) && canPostNotifications(context)

    /** The keyboard can be darkened for a ring: the switch is on and the app may write the vendor row. */
    fun isKeyboardDarkReady(context: Context): Boolean =
        !SettingsManager.isRingKeyboardDarkEnabled(context) || RingBacklight.canWrite(context)

    /** True while the ring and its keyboard-dark switch are both on: the holder's condition. */
    fun needsKeyboardDarkPermission(context: Context): Boolean =
        SettingsManager.isNotificationRingEnabled(context) && SettingsManager.isRingKeyboardDarkEnabled(context)

    /** The broker grants still needed, in order. Empty when nothing is missing. */
    internal fun missingGrants(context: Context): List<PrivilegedCommand.RingGrant> = buildList {
        if (!isListenerGranted(context)) add(PrivilegedCommand.RingGrant.ALLOW_LISTENER)
        if (!canUseFullScreenIntent(context)) add(PrivilegedCommand.RingGrant.ALLOW_FULL_SCREEN_INTENT)
        if (!canPostNotifications(context)) add(PrivilegedCommand.RingGrant.ALLOW_POST_NOTIFICATIONS)
    }

    /**
     * The keyboard-dark switch's WRITE_SECURE_SETTINGS, held while [needsKeyboardDarkPermission].
     * Queued off-thread; call it when the ring or its keyboard-dark switch changes. True when a
     * broker grant or revoke was queued.
     */
    fun updateKeyboardDarkHolder(context: Context, trigger: BrokerTrigger?): Boolean =
        PrivilegedSetup.setWriteSecureSettingsNeeded(
            context,
            WriteSecureSettingsHolders.RING_KEYBOARD_DARK,
            needsKeyboardDarkPermission(context),
            trigger
        )

    /**
     * Grant whatever is missing through the broker, in a single connection: the three ring grants.
     * The [WriteSecureSettingsHolders.RING_KEYBOARD_DARK] holder is brought up to date first; while
     * the ring and "keep the keyboard dark" are on, that grants
     * `WRITE_SECURE_SETTINGS` so [RingBacklight] works with no broker in its path. Best-effort,
     * off-thread, never throws; no-op when nothing is missing, and a quiet failure when the broker
     * cannot run (not paired, Wireless debugging off). [onDone] runs on the worker thread with the
     * final readiness of the ring grants. [includeKeyboardDark] is false from
     * [PrivilegedSetup.applyAll], which handles the holders itself.
     */
    fun grantViaBroker(
        context: Context,
        trigger: BrokerTrigger?,
        includeKeyboardDark: Boolean = true,
        onDone: ((ready: Boolean) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        // Always, not only when the permission is missing: another holder (the backlight) may be
        // what holds it now, and the ring must count as a holder too or turning that one off
        // would revoke it from under the ring.
        if (includeKeyboardDark) updateKeyboardDarkHolder(appContext, trigger)
        val missing = missingGrants(appContext)
        if (missing.isEmpty()) {
            if (onDone != null) PrivilegedShell.runAsync { onDone(isReady(appContext)) }
            return
        }
        PrivilegedShell.runAsync {
            val result = PrivilegedShell.run(appContext, PrivilegedCommand.RingGrants(missing), trigger)
            // The shell's own result is not the answer: these commands are quiet on success, and the
            // grants themselves are what matter, so ask the platform for those.
            if (PrivilegedCommand.RingGrant.ALLOW_LISTENER in missing && isListenerGranted(appContext)) {
                // So a reset withdraws the access the broker gave.
                BrokerGrantLedger.recordListenerAllowed(appContext, AppNotificationListener.RING)
            }
            val ready = isReady(appContext)
            Log.i(TAG, "broker grant ok=${result.ok} ready=$ready keyboardDarkReady=${isKeyboardDarkReady(appContext)}")
            onDone?.invoke(ready)
        }
    }

    /**
     * Take the grants back. For a "reset device settings to stock" action (the toolbox group's):
     * notification access is the one thing here that outlives an uninstall in a way the user could
     * not see. BLOCKING — call off the main thread. Returns true when nothing remains granted.
     * Not called by anything in the ring group.
     */
    fun revokeViaBroker(context: Context, trigger: BrokerTrigger?): Boolean {
        val appContext = context.applicationContext
        if (!isListenerGranted(appContext) && !canUseFullScreenIntent(appContext)) return true
        PrivilegedShell.run(
            appContext,
            PrivilegedCommand.RingGrants(
                listOf(
                    PrivilegedCommand.RingGrant.DISALLOW_LISTENER,
                    PrivilegedCommand.RingGrant.RESET_FULL_SCREEN_INTENT
                )
            ),
            trigger
        )
        val withdrawn = !isListenerGranted(appContext)
        if (withdrawn) BrokerGrantLedger.recordListenerDisallowed(appContext, AppNotificationListener.RING)
        return withdrawn
    }

    fun listenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun fullScreenSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            appNotificationSettingsIntent(context)
        }

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
