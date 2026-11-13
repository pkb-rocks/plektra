package rocks.pkb.plektra.inputmethod

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import rocks.pkb.plektra.SettingsManager

/**
 * Single entry point for everything the app wants to do through the embedded ADB broker.
 *
 * The user pairs Wireless debugging once; from then on every privileged change is applied in one
 * go, at pairing success ([moe.shizuku.manager.adb.AdbPairingService]) and when the user turns a
 * feature on in the app's screens. All steps must be idempotent, run off-thread and never throw,
 * so calling this repeatedly is harmless. Every call needs a [BrokerTrigger].
 *
 * A feature that needs a privileged grant adds one guarded step inside [applyAll], queued with
 * [PrivilegedShell.runAsync] before [finishSetup], its commands to [PrivilegedCommand], and
 * registers in [WriteSecureSettingsHolders] if it needs WRITE_SECURE_SETTINGS. Nothing here is
 * called from the IME's key-handling path, and nothing here turns Wireless debugging on.
 */
object PrivilegedSetup {
    private const val TAG = "PrivilegedSetup"

    /** How long [finishSetup] waits for `adb_wifi_enabled` to read 0 after the shell wrote it. */
    private const val WIRELESS_OFF_CONFIRM_MS = 3_000L

    fun applyAll(context: Context, reason: String, trigger: BrokerTrigger?) {
        val appContext = context.applicationContext
        syncFeatureHolders(appContext)
        // Log.i is stripped from release builds, so a bail-out here would leave no trace at all.
        // A step records its own failure through PrivilegedDiagnostics.record.
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            Log.w(TAG, "applyAll ($reason) skipped: $blocker")
            // a blocked broker means no step runs, so record it for the steps whose
            // screens show a failure reason. Backlight only counts when the user turned it on.
            recordBlocked(appContext, PrivilegedDiagnostics.Step.WRITE_SECURE_SETTINGS, blocker)
            if (SettingsManager.getSmartBacklightEnabled(appContext)) {
                recordBlocked(appContext, PrivilegedDiagnostics.Step.BACKLIGHT, blocker)
            }
            return
        }
        Log.i(TAG, "applyAll ($reason)")
        PrivilegedShell.runAsync {
            // Verify first: right after pairing this is the connect that pins adbd's certificate
            // (trust on first use), seconds after the pairing code authenticated it. Then grant or
            // revoke WRITE_SECURE_SETTINGS to match the features that hold it (the Quick Settings
            // tile writes through it).
            val commands = buildList {
                add(PrivilegedCommand.Verify)
                writeSecureSettingsAction(appContext)?.let { add(it) }
            }
            val results = PrivilegedShell.runSession(appContext, commands, trigger)
            recordPermissionOutcome(appContext, commands, results)
        }
        // the always-on timeout, if the user wants it.
        if (SettingsManager.getSmartBacklightEnabled(appContext)) {
            KeyboardBacklightManager.applyAlwaysOn(appContext, trigger)
        }
        // Queue each feature's steps here, before finishSetup.
        PrivilegedShell.runAsync { finishSetup(appContext, trigger) }
    }

    private fun recordBlocked(context: Context, step: PrivilegedDiagnostics.Step, blocker: String) {
        PrivilegedDiagnostics.record(context, step, ok = false, reason = blocker)
    }

    /**
     * Brings [WriteSecureSettingsHolders] in line with the features' own switches before
     * [applyAll] decides whether to grant or revoke. The switches set their holder as the user
     * flips them; this covers an install that turned a feature on before holders existed (it
     * would otherwise read as "nothing needs it" and lose the permission) and a re-pair after a
     * reset, which clears the holders.
     */
    internal fun syncFeatureHolders(context: Context) {
        WriteSecureSettingsHolders.set(
            context,
            WriteSecureSettingsHolders.BACKLIGHT,
            SettingsManager.getSmartBacklightEnabled(context)
        )
        // the keyboard-light tile's own switch.
        WriteSecureSettingsHolders.set(
            context,
            WriteSecureSettingsHolders.BACKLIGHT_TILE,
            SettingsManager.getBacklightTileEnabled(context)
        )
    }

    /**
     * Queues [finishSetup] after the privileged steps already queued, for a user action that ran
     * broker commands outside [applyAll] (a feature switched off), so Wireless
     * debugging is switched off after it too when the user keeps that setting on. Never throws.
     */
    fun queueFinishSetup(context: Context, trigger: BrokerTrigger?) {
        val app = context.applicationContext
        PrivilegedShell.runAsync { finishSetup(app, trigger) }
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

    // ---- WRITE_SECURE_SETTINGS, held only while needed ---------------------------

    /** True when this app holds WRITE_SECURE_SETTINGS. Asked of Android, not of the shell. */
    fun hasWriteSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun writeSecureSettingsAction(context: Context): PrivilegedCommand? =
        WriteSecureSettingsHolders.action(
            needed = WriteSecureSettingsHolders.anyNeeded(context),
            held = hasWriteSecureSettings(context)
        )

    /**
     * A feature that needs WRITE_SECURE_SETTINGS calls this when the user turns it on or off.
     * The permission is granted when the first holder appears and revoked when the last one goes.
     * Off-thread, never throws. Returns true when a grant or revoke was queued (so the caller
     * knows a broker session will run, and can queue [queueFinishSetup] after it).
     */
    fun setWriteSecureSettingsNeeded(
        context: Context,
        holder: String,
        needed: Boolean,
        trigger: BrokerTrigger?,
    ): Boolean {
        val app = context.applicationContext
        // the other features' holders first, from their own switches, so one
        // going off never revokes from under another whose holder was never recorded (an upgrade
        // that has not run applyAll since the holder was added). This call's own value wins.
        syncFeatureHolders(app)
        WriteSecureSettingsHolders.set(app, holder, needed)
        val command = writeSecureSettingsAction(app) ?: return false
        PrivilegedShell.runAsync {
            val result = PrivilegedShell.run(app, command, trigger)
            recordPermissionOutcome(app, listOf(command), listOf(result))
        }
        return true
    }

    /**
     * Audit a grant or revoke by what Android now says, never by what the shell printed. Android
     * settles `checkSelfPermission` a few ms after `pm grant`/`pm revoke` returns (device test:
     * a granted permission was audited as failed 2 ms after the shell finished), so wait for the
     * expected state first. Blocks up to [PERMISSION_SETTLE_MS]: callers are all off the main
     * thread ([PrivilegedShell.runAsync] and [resetToStock]'s contract).
     */
    private fun recordPermissionOutcome(
        context: Context,
        commands: List<PrivilegedCommand>,
        results: List<PrivilegedResult>,
    ) {
        commands.forEachIndexed { i, command ->
            val failure = results.getOrNull(i)?.failure
            when (command) {
                PrivilegedCommand.GrantWriteSecureSettings -> {
                    val granted = awaitState(expected = true) { hasWriteSecureSettings(context) }
                    BrokerAuditLog.record(
                        context, BrokerAuditLog.Kind.GRANT, "write_secure_settings",
                        ok = granted, detail = failure
                    )
                    // the diagnostics step the backlight screens read.
                    PrivilegedDiagnostics.record(
                        context, PrivilegedDiagnostics.Step.WRITE_SECURE_SETTINGS, ok = granted,
                        reason = if (granted) {
                            PrivilegedDiagnostics.REASON_OK
                        } else {
                            failure ?: PrivilegedDiagnostics.REASON_SHELL_FAILED
                        }
                    )
                }
                PrivilegedCommand.RevokeWriteSecureSettings -> BrokerAuditLog.record(
                    context, BrokerAuditLog.Kind.REVOKE, "write_secure_settings",
                    ok = !awaitState(expected = false) { hasWriteSecureSettings(context) },
                    detail = failure
                )
                else -> Unit
            }
        }
    }

    private const val PERMISSION_SETTLE_MS = 1_000L
    private const val PERMISSION_POLL_MS = 50L

    /**
     * Polls [read] until it returns [expected] or [timeoutMs] passes, and returns the last value
     * read (so a command that really failed is reported as such, after the wait). The clock and
     * sleep are parameters so the unit test needs no real time.
     */
    internal fun awaitState(
        expected: Boolean,
        timeoutMs: Long = PERMISSION_SETTLE_MS,
        pollMs: Long = PERMISSION_POLL_MS,
        now: () -> Long = SystemClock::elapsedRealtime,
        sleep: (Long) -> Unit = Thread::sleep,
        read: () -> Boolean,
    ): Boolean {
        val deadline = now() + timeoutMs
        var value = read()
        while (value != expected && now() < deadline) {
            sleep(pollMs)
            value = read()
        }
        return value
    }

    // ---- reset to stock / forget pairing ---------------------------------

    /**
     * What a reset achieved. [unpaired] is true only when a fresh connection was refused after the
     * broker unpaired itself. When it is false the user must remove the key named after the app
     * (see [moe.shizuku.manager.adb.EmbeddedAdbInit.keyName]) from Settings > Wireless debugging >
     * Paired devices by hand. [writeSecureSettingsStillHeld] is true when the
     * revoke could not run; uninstalling the app, or pairing again and resetting, clears it.
     */
    data class ResetResult(
        val unpaired: Boolean,
        val writeSecureSettingsStillHeld: Boolean,
        val reason: String?,
    )

    /**
     * "Forget pairing", which is also this branch's reset to stock. While the broker can still
     * connect, in one session: revoke WRITE_SECURE_SETTINGS and unpair this key (`IAdbManager.unpairDevice`, last, because
     * adbd then drops the connection). Then confirm the unpair (a new connection must be refused) and delete the
     * key and pins here whatever happened. BLOCKING (two mDNS searches); call off the main thread.
     */
    fun resetToStock(context: Context, trigger: BrokerTrigger?): ResetResult {
        val app = context.applicationContext
        var reason: String? = null
        var unpaired = false
        val fingerprint = EmbeddedAdbShell.keyFingerprint(app)
        val unpair = fingerprint?.let { PrivilegedCommand.UnpairSelf.forThisDevice(it) }
        val commands = buildList {
            if (hasWriteSecureSettings(app)) add(PrivilegedCommand.RevokeWriteSecureSettings)
            unpair?.let { add(it) }
        }
        when {
            fingerprint == null -> reason = REASON_NO_KEY
            unpair == null -> reason = REASON_UNPAIR_UNVERIFIED_SDK
        }

        if (commands.isNotEmpty() && fingerprint != null) {
            val results = PrivilegedShell.runSession(app, commands, trigger)
            recordPermissionOutcome(app, commands, results)
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
        WriteSecureSettingsHolders.clear(app)
        BrokerAuditLog.record(app, BrokerAuditLog.Kind.KEY, "deleted", ok = true)
        val stillHeld = hasWriteSecureSettings(app)
        return ResetResult(unpaired = unpaired, writeSecureSettingsStillHeld = stillHeld, reason = reason)
    }

    const val REASON_NO_KEY = "no readable key to unpair"
    const val REASON_UNPAIR_UNVERIFIED_SDK = "unpairing is not verified on this Android version"
}
