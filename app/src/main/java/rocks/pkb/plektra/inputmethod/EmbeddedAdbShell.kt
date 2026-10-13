package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.Observer
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbServerPinMismatchException
import moe.shizuku.manager.adb.EmbeddedAdbInit
import moe.shizuku.manager.adb.LegacyAdbKey
import moe.shizuku.manager.adb.MAX_SHELL_OUTPUT_BYTES
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs typed commands over the app's OWN embedded wireless-ADB broker (the vendored classes under
 * `moe.shizuku.manager.adb`), with NO dependency on the separate Shizuku app.
 *
 * Sequence:
 *   1. mDNS-discover the local `_adb-tls-connect._tcp` port on 127.0.0.1
 *   2. AdbClient("127.0.0.1", port, storedKey).connect()
 *   3. one shell stream per command, in order, on that one connection
 *
 * when the user's "Privileged access via" setting picks Shizuku
 * ([PrivilegedBackends]), the same door hands the checked command lines to Shizuku's UserService
 * instead of connecting to adbd; nothing else about the door changes.
 *
 * [execute] is the only way in, and it takes [PrivilegedCommand]s, never a string.
 * It is BLOCKING (network IO, up to ~8s of mDNS discovery): callers MUST invoke it OFF the main
 * thread. It never throws: every failure is returned, recorded in the audit log and surfaced via
 * [lastError] for troubleshooting UIs.
 */
object EmbeddedAdbShell {

    private const val TAG = "EmbeddedAdbShell"
    private const val MDNS_POLL_SECONDS = 8L

    /** What [verify]'s command prints; anything else means the peer is not a working adbd. */
    internal const val VERIFY_MARKER = "pastiera_verify"

    /** Last connect/shell error, for a troubleshooting surface. null after a clean run. */
    @Volatile
    var lastError: String? = null
        private set

    /** Raw output of the last successful shell command, for a troubleshooting surface. */
    @Volatile
    var lastResult: String? = null
        private set

    /**
     * True once the broker holds an ADB key (i.e. the device has been paired). A legacy
     * AES-wrapped key from before the Keystore key is discarded here, so such an install reads as not
     * paired and pairs again with a Keystore key.
     */
    fun isPaired(context: Context): Boolean {
        LegacyAdbKey.discard(EmbeddedAdbInit.prefs(context))
        return EmbeddedAdbInit.keySource(context).exists()
    }

    /**
     * Forget the stored key and adbd's pins, locally, so the app stops reading as paired and the
     * setup card offers to pair again. [PrivilegedSetup.resetToStock] first has the broker remove
     * the key from the phone, then calls this.
     *
     * [isPaired] only asks whether a key is held, and a key is minted the moment a pairing is
     * ATTEMPTED. Until that was fixed, one mistyped pairing code left an install permanently
     * claiming to be paired against a key the phone had never accepted, with every privileged
     * feature silently gated behind it and no way out but clearing app data. This is the way out.
     */
    fun forgetPairing(context: Context) {
        EmbeddedAdbInit.keySource(context).delete()
        LegacyAdbKey.discard(EmbeddedAdbInit.prefs(context))
        EmbeddedAdbInit.pinStore(context).clear()
        lastError = null
        lastResult = null
    }

    /** Cheap best-effort check that Wireless debugging is on (the `adb_wifi_enabled` global). */
    fun isWirelessDebuggingEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, PrivilegedCommand.ADB_WIFI_ENABLED, 0) == 1
        }.getOrDefault(false)

    /** This key's adb fingerprint, or null when no readable key is held. Never throws. */
    internal fun keyFingerprint(context: Context): String? = runCatching {
        AdbKey.existing(EmbeddedAdbInit.keySource(context), EmbeddedAdbInit.keyName(context.packageName)).adbFingerprint
    }.getOrNull()

    /**
     * Discover the local `_adb-tls-connect._tcp` port. Returns null if nothing is
     * advertised within [MDNS_POLL_SECONDS]. BLOCKING; call off the main thread.
     */
    private fun discoverPort(context: Context): Int? {
        // Wireless debugging (and its mDNS advertisement) only exists from Android 11.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        EmbeddedAdbInit.ensure()
        val portQueue = ArrayBlockingQueue<Int>(1)
        val observer = Observer<Int> { p -> if (p > 0) portQueue.offer(p) }
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT, observer)
        mdns.start()
        return try {
            portQueue.poll(MDNS_POLL_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            null
        } finally {
            runCatching { mdns.stop() }
        }
    }

    /**
     * Serializes discovery + shell across callers: overlapping NsdManager discoveries for the
     * same service type fail silently, so two privileged steps started together would both see
     * "no service found".
     */
    private val brokerLock = Any()

    /**
     * Why the broker can or cannot run, established by actually using it.
     *
     * [REJECTED] is the case no amount of stored state can reveal: a key is held and the phone is
     * advertising, but the connection is refused because the phone never accepted this key.
     * [PIN_MISMATCH] is adbd presenting a different certificate from the pinned one: the user has
     * to forget the pairing and pair again.
     */
    enum class BrokerStatus {
        OK, NOT_PAIRED, WIRELESS_DEBUGGING_OFF, NO_SERVICE, REJECTED, PIN_MISMATCH,

        /** Shizuku was chosen but is not running, not allowed or not installed. */
        SHIZUKU_UNAVAILABLE,
    }

    /** What [verifyWithBackend] found, and through which backend. */
    data class Verdict(val status: BrokerStatus, val backend: PrivilegedBackend)

    /**
     * THE door to the broker. Runs [commands] in order on one connection and returns one result
     * per command. Before anything connects it checks, in this one place:
     * 1. [trigger] is present, fresh and allows every command (user action only; a
     *    [BrokerTrigger.Kind.SCREEN] trigger runs [PrivilegedCommand.readOnly] commands only);
     * 2. a [PrivilegedCommand.terminal] command, if any, comes last;
     * 3. every command line validates (no command runs if one fails).
     * Only then does it choose the backend ([PrivilegedBackends]): Shizuku runs
     * the lines through its UserService, the broker connects to adbd. [brokerOnly] skips the choice
     * (the reset's unpair check is about the broker's own key). Each command, and each refusal,
     * goes to the [BrokerAuditLog], a command's entry naming its backend. When a
     * command fails, the ones after it are not run. A terminal command that drops the connection
     * reports failure "connection closed"; its caller checks the outcome some other way.
     */
    internal fun execute(
        context: Context,
        commands: List<PrivilegedCommand>,
        trigger: BrokerTrigger?,
        brokerOnly: Boolean = false,
    ): List<PrivilegedResult> = synchronized(brokerLock) {
        val app = context.applicationContext
        if (commands.isEmpty()) return@synchronized emptyList()

        fun refuse(reason: String, status: BrokerStatus? = null): List<PrivilegedResult> {
            commands.forEach {
                BrokerAuditLog.record(app, BrokerAuditLog.Kind.REFUSED, it.id, ok = false, detail = reason)
            }
            lastError = reason
            return commands.map { PrivilegedResult(ok = false, output = null, failure = reason, status = status) }
        }

        fun fail(reason: String, status: BrokerStatus, backend: PrivilegedBackend): List<PrivilegedResult> {
            commands.forEach {
                BrokerAuditLog.record(
                    app, BrokerAuditLog.Kind.COMMAND, it.id, ok = false, detail = auditDetail(backend, reason)
                )
            }
            lastError = reason
            return commands.map {
                PrivilegedResult(ok = false, output = null, failure = reason, status = status, backend = backend)
            }
        }

        when {
            trigger == null -> return@synchronized refuse(REFUSED_NO_TRIGGER)
            !trigger.isFresh() -> return@synchronized refuse(REFUSED_STALE_TRIGGER)
            // An open screen may only read ([PrivilegedCommand.readOnly]); every write needs a tap.
            trigger.kind == BrokerTrigger.Kind.SCREEN && commands.any { !it.readOnly } ->
                return@synchronized refuse(REFUSED_NOT_ALLOWED)
            commands.any { !trigger.allows(it) } -> return@synchronized refuse(REFUSED_NOT_ALLOWED)
            commands.dropLast(1).any { it.terminal } -> return@synchronized refuse(REFUSED_TERMINAL_NOT_LAST)
        }
        val argvs = commands.map { command ->
            runCatching { command.argvs(app.packageName) }
                .getOrElse { return@synchronized refuse("invalid ${command.id}: ${it.message}") }
        }
        val lines = argvs.map { PrivilegedCommand.shellLine(it) }

        // past every check, choose the route.
        if (!brokerOnly) {
            val resolved = PrivilegedBackends.resolve(app)
            if (resolved.backend == PrivilegedBackend.SHIZUKU) {
                PrivilegedBackends.shizukuUnavailableReason(resolved.shizukuState)?.let { reason ->
                    return@synchronized fail(reason, BrokerStatus.SHIZUKU_UNAVAILABLE, PrivilegedBackend.SHIZUKU)
                }
                return@synchronized runViaShizuku(app, commands, argvs)
            }
        }

        val broker = PrivilegedBackend.BROKER
        EmbeddedAdbInit.ensure()
        if (!isPaired(app)) return@synchronized fail(REASON_NOT_PAIRED, BrokerStatus.NOT_PAIRED, broker)
        if (!isWirelessDebuggingEnabled(app)) {
            return@synchronized fail(REASON_WIRELESS_DEBUGGING_OFF, BrokerStatus.WIRELESS_DEBUGGING_OFF, broker)
        }
        val port = discoverPort(app)
        if (port == null || port <= 0) {
            Log.w(TAG, "embedded adb shell: $REASON_NO_SERVICE")
            return@synchronized fail(REASON_NO_SERVICE, BrokerStatus.NO_SERVICE, broker)
        }
        runSession(app, port, commands, lines)
    }

    /**
     * The audit detail of a command entry: its backend, the uid the Shizuku service ran as when
     * one answered (`via shizuku (uid 2000)`), and the reason when it failed.
     */
    internal fun auditDetail(backend: PrivilegedBackend, reason: String? = null, uid: Int? = null): String {
        val who = uid?.let { " (uid $it)" }.orEmpty()
        return if (reason == null) "via ${backend.auditName}$who" else "via ${backend.auditName}$who: $reason"
    }

    /**
     * runs each command's argv arrays through Shizuku's UserService, no
     * shell, under the same output limit and deadline as the broker, and audits each like
     * [runSession] does. Called only from [execute], after its checks. A command of several arrays
     * runs them in order like `a; b` did: a non-zero exit does not stop the next one, the output is
     * concatenated and the exit code is the last one's. A non-zero exit code does not fail a command
     * (adbd's shell reports none, so callers already judge results by output or by asking
     * Android); it is noted in the audit entry.
     *
     * A service that dies or disconnects mid-run (a Shizuku server restart takes it along) comes
     * back as a failed result with the status SHIZUKU_UNAVAILABLE, never as success: the caller
     * shows the failure and the user retries, which binds a fresh service.
     */
    private fun runViaShizuku(
        app: Context,
        commands: List<PrivilegedCommand>,
        argvs: List<List<List<String>>>,
    ): List<PrivilegedResult> {
        val backend = PrivilegedBackend.SHIZUKU
        val shell = PrivilegedBackends.shizuku
        val results = ArrayList<PrivilegedResult>(commands.size)
        for (i in commands.indices) {
            val command = commands[i]
            val run = runArgvs(shell, app, argvs[i])
            if (run.ok) {
                val output = run.output.orEmpty()
                lastResult = output
                lastError = null
                val exit = run.exitCode?.takeIf { it != 0 }?.let { ", exit $it" }.orEmpty()
                BrokerAuditLog.record(
                    app, BrokerAuditLog.Kind.COMMAND, command.id, ok = true,
                    detail = auditDetail(backend, uid = run.uid) + exit
                )
                results += PrivilegedResult(ok = true, output = output, failure = null, backend = backend)
                continue
            }
            val reason = run.failure ?: "Shizuku command failed"
            Log.w(TAG, "shizuku shell failed: $reason")
            BrokerAuditLog.record(
                app, BrokerAuditLog.Kind.COMMAND, command.id, ok = false,
                detail = auditDetail(backend, reason, run.uid)
            )
            lastError = reason
            val status = if (run.serviceUnavailable) BrokerStatus.SHIZUKU_UNAVAILABLE else null
            results += PrivilegedResult(
                ok = false, output = run.output, failure = reason, status = status, backend = backend
            )
            for (j in i + 1 until commands.size) {
                BrokerAuditLog.record(
                    app, BrokerAuditLog.Kind.COMMAND, commands[j].id, ok = false,
                    detail = auditDetail(backend, REASON_EARLIER_FAILED)
                )
                results += PrivilegedResult(
                    ok = false, output = null, failure = REASON_EARLIER_FAILED, backend = backend
                )
            }
            return results
        }
        return results
    }

    /** One command's argv arrays in order; the first run that does not finish ends it as a failure. */
    private fun runArgvs(shell: ShizukuShell, app: Context, argvs: List<List<String>>): ShellRunResult {
        val output = StringBuilder()
        var last: ShellRunResult? = null
        // The deadline is per program, not per command: each argv gets its own CLIENT_DEADLINE_MS
        // in the service, so a command of N programs can hold the broker lock for up to N times
        // that. (The old one-line route allowed one deadline per line.) Kept as is on purpose.
        for (argv in argvs) {
            val run = runCatching { shell.runArgv(app, argv) }.getOrElse {
                ShellRunResult(false, null, null, "Shizuku call failed: ${describe(it)}", serviceUnavailable = true)
            }
            if (!run.ok) return run.copy(output = (output.toString() + run.output.orEmpty()).ifEmpty { null })
            output.append(run.output.orEmpty())
            if (output.length > MAX_SHELL_OUTPUT_BYTES) {
                return run.copy(
                    ok = false, output = null, exitCode = null,
                    failure = "shell output exceeded $MAX_SHELL_OUTPUT_BYTES bytes"
                )
            }
            last = run
        }
        return last?.copy(output = output.toString())
            ?: ShellRunResult(false, null, null, "no command to run")
    }

    /** Connect on [port] and run [lines], one stream each. Records every command it attempts. */
    private fun runSession(
        app: Context,
        port: Int,
        commands: List<PrivilegedCommand>,
        lines: List<String>,
    ): List<PrivilegedResult> {
        val keyStore = EmbeddedAdbInit.keySource(app)
        val pins = EmbeddedAdbInit.pinStore(app)
        val results = ArrayList<PrivilegedResult>(commands.size)

        val broker = PrivilegedBackend.BROKER

        fun failRest(reason: String, status: BrokerStatus?) {
            for (i in results.size until commands.size) {
                BrokerAuditLog.record(
                    app, BrokerAuditLog.Kind.COMMAND, commands[i].id, ok = false, detail = auditDetail(broker, reason)
                )
                results += PrivilegedResult(
                    ok = false, output = null, failure = reason, status = status, backend = broker
                )
            }
            lastError = reason
        }

        val key = try {
            // Never generates: only pairing may mint a key.
            AdbKey.existing(keyStore, EmbeddedAdbInit.keyName(app.packageName))
        } catch (e: AdbKeyException) {
            // The phone still trusts the old key and we can no longer sign with it, so the
            // pairing is gone either way. Forgetting it makes the setup card ask to pair again.
            Log.e(TAG, "ADB key unreadable", e)
            keyStore.delete()
            BrokerAuditLog.record(app, BrokerAuditLog.Kind.KEY, "deleted_unreadable", ok = false, detail = e.message)
            failRest(describe(e), BrokerStatus.NOT_PAIRED)
            return results
        }

        try {
            AdbClient("127.0.0.1", port, key, pins).use { client ->
                try {
                    client.connect()
                } catch (e: AdbServerPinMismatchException) {
                    Log.e(TAG, "adbd certificate changed", e)
                    failRest(REASON_PIN_MISMATCH, BrokerStatus.PIN_MISMATCH)
                    return results
                } catch (t: Throwable) {
                    Log.e(TAG, "embedded adb connect failed", t)
                    failRest(describe(t), BrokerStatus.REJECTED)
                    return results
                }
                for (i in commands.indices) {
                    val command = commands[i]
                    val out = ByteArrayOutputStream()
                    try {
                        client.shellCommand(lines[i]) { bytes -> out.write(bytes) }
                        val output = out.toString(Charsets.UTF_8.name())
                        lastResult = output
                        lastError = null
                        BrokerAuditLog.record(
                            app, BrokerAuditLog.Kind.COMMAND, command.id, ok = true, detail = auditDetail(broker)
                        )
                        results += PrivilegedResult(ok = true, output = output, failure = null, backend = broker)
                    } catch (t: Throwable) {
                        val partial = out.toString(Charsets.UTF_8.name()).ifEmpty { null }
                        val reason = if (command.terminal) REASON_CONNECTION_CLOSED else describe(t)
                        if (!command.terminal) Log.e(TAG, "embedded adb shell failed", t)
                        BrokerAuditLog.record(
                            app, BrokerAuditLog.Kind.COMMAND, command.id, ok = false,
                            detail = auditDetail(broker, reason)
                        )
                        lastError = reason
                        results += PrivilegedResult(ok = false, output = partial, failure = reason, backend = broker)
                        failRest(REASON_EARLIER_FAILED, null)
                        return results
                    }
                }
            }
        } catch (t: Throwable) {
            // close() never throws, but nothing here may escape to the caller.
            Log.e(TAG, "embedded adb session failed", t)
            failRest(describe(t), BrokerStatus.REJECTED)
        }
        return results
    }

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"

    /**
     * Actually connect and run a trivial command, and report what happened.
     *
     * [isPaired] answers "is a key stored", which is not the same question as "does the phone
     * accept us" — a key is minted the moment a pairing is attempted, so a mistyped pairing code
     * used to leave every privileged feature gated behind a key that had never worked, with the
     * UI reporting success. Nothing short of connecting can tell the difference.
     *
     * The cheap prerequisites are answered without a trigger or a connection. BLOCKING (up to ~8s
     * of mDNS discovery plus a connect); call off the main thread.
     *
     * it verifies whichever backend [execute] would use, so with Shizuku
     * chosen an OK means Shizuku's shell answered. [brokerOnly] checks the broker itself.
     */
    fun verify(context: Context, trigger: BrokerTrigger, brokerOnly: Boolean = false): BrokerStatus =
        verifyWithBackend(context, trigger, brokerOnly).status

    /** [verify], also saying which backend answered (or would have). */
    fun verifyWithBackend(context: Context, trigger: BrokerTrigger, brokerOnly: Boolean = false): Verdict {
        val resolved = PrivilegedBackends.resolve(context)
        val backend = if (brokerOnly) PrivilegedBackend.BROKER else resolved.backend
        if (backend == PrivilegedBackend.BROKER) {
            if (!isPaired(context)) return Verdict(BrokerStatus.NOT_PAIRED, backend)
            if (!isWirelessDebuggingEnabled(context)) return Verdict(BrokerStatus.WIRELESS_DEBUGGING_OFF, backend)
        } else if (resolved.shizukuState != ShizukuState.READY) {
            return Verdict(BrokerStatus.SHIZUKU_UNAVAILABLE, backend)
        }
        val result = execute(context, listOf(PrivilegedCommand.Verify), trigger, brokerOnly).single()
        val ran = result.backend ?: backend
        if (!result.ok) return Verdict(result.status ?: BrokerStatus.REJECTED, ran)
        return if (isVerifyOutput(result.output.orEmpty())) {
            Verdict(BrokerStatus.OK, ran)
        } else {
            // A real shell echoes the marker back; anything else is not a shell we can trust.
            lastError = "Unexpected verify output from ${ran.auditName}"
            Verdict(BrokerStatus.REJECTED, ran)
        }
    }

    /** True when [output] is exactly the verify marker, allowing only the shell's line ending. */
    internal fun isVerifyOutput(output: String): Boolean =
        output.removeSuffix("\n").removeSuffix("\r") == VERIFY_MARKER

    const val REASON_NOT_PAIRED = "Not paired yet — set up wireless debugging first."
    const val REASON_WIRELESS_DEBUGGING_OFF = "Wireless debugging is off."
    const val REASON_NO_SERVICE = "No adb-tls-connect service found. Is wireless debugging on?"
    const val REASON_PIN_MISMATCH = "adbd's certificate changed. Forget the pairing and pair again."
    const val REASON_CONNECTION_CLOSED = "connection closed"
    const val REASON_EARLIER_FAILED = "not run: an earlier command failed"
    const val REFUSED_NO_TRIGGER = "refused: not started by a user action in Settings"
    const val REFUSED_STALE_TRIGGER = "refused: the user action is too old"
    const val REFUSED_NOT_ALLOWED = "refused: not allowed for this kind of user action"
    const val REFUSED_TERMINAL_NOT_LAST = "refused: a command that ends the session must be last"
}
