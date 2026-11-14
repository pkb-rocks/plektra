package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Single entry point for everything the app wants to do through the embedded ADB broker.
 *
 * The user pairs Wireless debugging once; from then on every privileged change is applied in one
 * go, at pairing success ([moe.shizuku.manager.adb.AdbPairingService]) and when the user turns a
 * feature on in the app's screens. All steps must be idempotent, run off-thread and never throw,
 * so calling this repeatedly is harmless. Every call needs a [BrokerTrigger].
 *
 * A feature that needs a privileged grant adds one guarded step inside [applyAll], queued with
 * [PrivilegedShell.runAsync] before [finishSetup], and its commands to [PrivilegedCommand].
 * Nothing here is called from the IME's key-handling path, and nothing here turns Wireless
 * debugging on.
 */
object PrivilegedSetup {
    private const val TAG = "PrivilegedSetup"

    /** How long [finishSetup] waits for `adb_wifi_enabled` to read 0 after the shell wrote it. */
    private const val WIRELESS_OFF_CONFIRM_MS = 3_000L

    fun applyAll(context: Context, reason: String, trigger: BrokerTrigger?) {
        val appContext = context.applicationContext
        // Log.i is stripped from release builds, so a bail-out here would leave no trace at all.
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            Log.w(TAG, "applyAll ($reason) skipped: $blocker")
            return
        }
        Log.i(TAG, "applyAll ($reason)")
        // Verify first: right after pairing this is the connect that pins adbd's certificate
        // (trust on first use), seconds after the pairing code authenticated it.
        PrivilegedShell.runAsync {
            PrivilegedShell.runSession(appContext, listOf(PrivilegedCommand.Verify), trigger)
        }
        // Queue each feature's steps here, before finishSetup.
        PrivilegedShell.runAsync { finishSetup(appContext, trigger) }
    }

    /**
     * The end of a setup run: when the user keeps "Turn Wireless debugging off after
     * setup" on (the default), switch Wireless debugging off through the shell, as the last
     * command. The outcome is read back from the global, not from the shell. BLOCKING.
     */
    internal fun finishSetup(context: Context, trigger: BrokerTrigger?) {
        val app = context.applicationContext
        if (!BrokerSettings.turnOffWirelessDebuggingAfterSetup(app)) return
        if (!EmbeddedAdbShell.isWirelessDebuggingEnabled(app)) return
        PrivilegedShell.run(app, PrivilegedCommand.DisableWirelessDebugging, trigger)
        val deadline = SystemClock.elapsedRealtime() + WIRELESS_OFF_CONFIRM_MS
        while (EmbeddedAdbShell.isWirelessDebuggingEnabled(app) && SystemClock.elapsedRealtime() < deadline) {
            runCatching { Thread.sleep(100) }
        }
        val off = !EmbeddedAdbShell.isWirelessDebuggingEnabled(app)
        BrokerAuditLog.record(app, BrokerAuditLog.Kind.WIRELESS_DEBUGGING, "turned_off_after_setup", ok = off)
    }

    // ---- reset to stock / forget pairing ---------------------------------

    /**
     * What a reset achieved. [unpaired] is true only when a fresh connection was refused after the
     * broker unpaired itself. When it is false the user must remove the key named after the app
     * (see [moe.shizuku.manager.adb.EmbeddedAdbInit.keyName]) from Settings > Wireless debugging >
     * Paired devices by hand.
     */
    data class ResetResult(
        val unpaired: Boolean,
        val reason: String?,
    )

    /**
     * "Forget pairing", which is also the reset to stock. While the broker can still connect,
     * unpair this key (`IAdbManager.unpairDevice`, terminal, because adbd then drops the
     * connection). Then confirm the unpair (a new connection must be refused) and delete the key
     * and pins here whatever happened. BLOCKING (two mDNS searches); call off the main thread.
     */
    fun resetToStock(context: Context, trigger: BrokerTrigger?): ResetResult {
        val app = context.applicationContext
        var reason: String? = null
        var unpaired = false
        val fingerprint = EmbeddedAdbShell.keyFingerprint(app)
        val unpair = fingerprint?.let { PrivilegedCommand.UnpairSelf.forThisDevice(it) }
        val commands = listOfNotNull(unpair)
        when {
            fingerprint == null -> reason = REASON_NO_KEY
            unpair == null -> reason = REASON_UNPAIR_UNVERIFIED_SDK
        }

        if (commands.isNotEmpty() && fingerprint != null) {
            val results = PrivilegedShell.runSession(app, commands, trigger)
            if (unpair != null) {
                val result = results.last()
                val sent = (result.ok && PrivilegedCommand.UnpairSelf.replyIsSuccess(result.output)) ||
                    result.failure == EmbeddedAdbShell.REASON_CONNECTION_CLOSED
                if (sent && trigger != null) {
                    // The proof is adbd refusing this key now, not anything the shell printed.
                    val after = EmbeddedAdbShell.verify(app, trigger)
                    unpaired = after == EmbeddedAdbShell.BrokerStatus.REJECTED
                    if (!unpaired) reason = "after unpairing, the broker status is $after"
                } else {
                    reason = result.failure ?: "unpairDevice reported an error"
                }
            }
        }
        BrokerAuditLog.record(app, BrokerAuditLog.Kind.UNPAIR, "self", ok = unpaired, detail = reason)

        EmbeddedAdbShell.forgetPairing(app)
        BrokerAuditLog.record(app, BrokerAuditLog.Kind.KEY, "deleted", ok = true)
        return ResetResult(unpaired = unpaired, reason = reason)
    }

    const val REASON_NO_KEY = "no readable key to unpair"
    const val REASON_UNPAIR_UNVERIFIED_SDK = "unpairing is not verified on this Android version"
}
