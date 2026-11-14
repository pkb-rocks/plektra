package rocks.pkb.plektra.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import moe.shizuku.manager.adb.CLIENT_DEADLINE_MS
import moe.shizuku.manager.adb.MAX_SHELL_OUTPUT_BYTES
import rikka.shizuku.Shizuku
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.inputmethod.ShellRunResult
import rocks.pkb.plektra.inputmethod.ShizukuShell
import rocks.pkb.plektra.inputmethod.ShizukuState
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * the real [ShizukuShell]. Binds [PrivilegedShellService] through
 * `Shizuku.bindUserService` (the supported API; `Shizuku.newProcess` is hidden in API 13) and
 * calls it once per program (argv, no shell). The service enforces the output limit and the
 * deadline; this side waits at most the deadline plus [CALL_SLACK_MS] for an answer and then kills
 * the service, so a stuck call cannot hold the broker's lock.
 *
 * Works with any Shizuku server that speaks the Shizuku API (Shizuku, ShizukuTendCF, Sui).
 *
 * Lifetime: the service is bound on first use and unbound (`remove = true`, which ends its
 * process) after [IDLE_UNBIND_MS] without a command, or as soon as the keyboard is hidden
 * ([onKeyboardHidden]), so nothing keeps running in the background. A cold bind costs a new
 * `app_process`, about 1.5 to 2.5 s; warm commands take about 130 ms. `daemon(false)` stays: the
 * service dies with Plektra's process if nothing unbinds it.
 *
 * Server restarts: Shizuku's watchdog restarts a dead server within 10 to 20 s and the user
 * service dies with the old server. A command in flight then fails (the binder call throws, or
 * [onServiceDisconnected] cancels it) and comes back as a failed, retryable
 * [ShellRunResult.serviceUnavailable] result, never as success; the next command binds afresh.
 *
 * The service may run as uid 0. Shizuku runs as whatever user Shizuku runs as (shell, 2000,
 * when started over ADB; root, 0, when started with root), the closed command set is the same
 * either way, root is allowed and is NOT refused, and the uid the service reported is recorded in
 * every audit entry (`via shizuku (uid 0)`). Refusing root would only make the backend unusable on
 * rooted phones.
 */
internal object ShizukuUserServiceShell : ShizukuShell {

    private const val TAG = "ShizukuShell"

    /**
     * Bump whenever IPrivilegedShellService.aidl changes (BrokerSourceRulesTest pins the AIDL's
     * hash to this number, so forgetting fails the build). It is added to the VERSION_CODE in the
     * UserServiceArgs, so a service process left over from an older interface is never reused:
     * debug builds share a VERSION_CODE, an AIDL change does not.
     */
    const val AIDL_VERSION = 2

    /** Shizuku's manager app; ShizukuTendCF keeps the same package name. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** How long a cold start of the service (a new process) may take. */
    private const val BIND_TIMEOUT_MS = 10_000L

    /** Extra wait beyond the service's own deadline, for the binder round trip. */
    private const val CALL_SLACK_MS = 5_000L

    /** The service is unbound this long after the last command. */
    internal const val IDLE_UNBIND_MS = 60_000L

    internal const val REASON_SERVICE_STOPPED =
        "Shizuku's shell service stopped while the command was running (Shizuku may have restarted). Try again."

    /** One lock for the service, the binding, the busy count and the call in flight; see [ServiceBindingState]. */
    private val bindings = ServiceBindingState<IPrivilegedShellService, Binding>()

    /** One bind: what [unbind] needs to undo it, with a connection that knows its generation. */
    internal class Binding(val args: Shizuku.UserServiceArgs, val connection: ServiceConnection)

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val idleUnbind = Runnable { unbindIfIdle() }

    /** Calls run here so the caller can stop waiting; a stuck call ends when the service is killed. */
    private val calls = Executors.newCachedThreadPool { r ->
        Thread(r, "shizuku-shell-call").apply { isDaemon = true }
    }

    /** A connection for ONE bind; its callbacks are ignored once a newer bind or an unbind replaced it. */
    private fun connectionFor(gen: Long, latch: CountDownLatch) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                bindings.connected(gen, IPrivilegedShellService.Stub.asInterface(binder))
            }
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The binder call usually throws DeadObjectException by itself; this makes the wait end
            // even when it does not.
            bindings.disconnected(gen)
        }
    }

    override fun state(context: Context): ShizukuState {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (alive) {
            if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return ShizukuState.TOO_OLD
            val granted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (granted) return ShizukuState.READY
            // Only Sui answers true here, after "Deny and don't ask again": requestPermission then
            // returns DENIED without a dialog. Shizuku's and ShizukuTendCF's Deny is one-time.
            val deniedForGood = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
            return if (deniedForGood) ShizukuState.DENIED_PERMANENTLY else ShizukuState.NOT_ALLOWED
        }
        return if (isInstalled(context)) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
    }

    private fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Shows Shizuku's "allow Plektra" prompt. Call it from a tap only. False when Shizuku is not
     * running (or too old to ask), so there is nothing to ask.
     */
    fun requestPermission(requestCode: Int): Boolean = runCatching {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
        Shizuku.requestPermission(requestCode)
        true
    }.getOrDefault(false)

    override fun runArgv(context: Context, argv: List<String>): ShellRunResult {
        if (state(context) != ShizukuState.READY) {
            return ShellRunResult.unavailable("Shizuku is not running or Plektra is not allowed.")
        }
        // Claimed before the service is looked at, under the same lock the idle unbind decides
        // under: the unbind either ran first (and connect binds afresh) or sees this command.
        bindings.claim()
        mainHandler.removeCallbacks(idleUnbind)
        var call: Future<Bundle>? = null
        try {
            val claimed = connect(context.applicationContext)
                ?: return ShellRunResult.unavailable("Shizuku did not start Plektra's shell service.")
            val shell = claimed.service
            val submitted = calls.submit<Bundle> { shell.run(argv.toTypedArray(), MAX_SHELL_OUTPUT_BYTES, CLIENT_DEADLINE_MS) }
            call = submitted
            bindings.setInFlight(submitted)
            val result = awaitResult(submitted, CLIENT_DEADLINE_MS + CALL_SLACK_MS) {
                // A call that never answered leaves a stuck service: kill it, the next call binds
                // again. Only this command's own generation; a newer bind is left alone.
                unbind { bindings.unbindGeneration(claimed.generation, it) }
            }
            // A dead binder means a dead service: forget it so the next call binds a fresh one.
            if (result.serviceUnavailable) bindings.dropService(claimed.generation)
            return result
        } finally {
            call?.let { bindings.clearInFlight(it) }
            if (bindings.release()) mainHandler.postDelayed(idleUnbind, IDLE_UNBIND_MS)
        }
    }

    /**
     * Waits for [call] and turns every way it can end into a [ShellRunResult]. A service that died
     * or disconnected during the call (the call threw, or was cancelled by [onServiceDisconnected])
     * is a failed result with [ShellRunResult.serviceUnavailable], never a success. Split out so a
     * test can drop the binder mid-run without a Shizuku server.
     */
    internal fun awaitResult(call: Future<Bundle>, waitMs: Long, onTimeout: () -> Unit = {}): ShellRunResult = try {
        ShellRunResult.fromBundle(call.get(waitMs, TimeUnit.MILLISECONDS), MAX_SHELL_OUTPUT_BYTES)
    } catch (_: TimeoutException) {
        call.cancel(true)
        onTimeout()
        ShellRunResult(false, null, null, CappedProcessRunner.deadlineFailure(CLIENT_DEADLINE_MS))
    } catch (_: CancellationException) {
        ShellRunResult.unavailable(REASON_SERVICE_STOPPED)
    } catch (e: ExecutionException) {
        // The service died (DeadObjectException) or threw.
        val cause = e.cause ?: e
        Log.w(TAG, "Shizuku shell call failed", cause)
        ShellRunResult.unavailable("$REASON_SERVICE_STOPPED (${cause.javaClass.simpleName}: ${cause.message})")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        ShellRunResult(false, null, null, "interrupted")
    }

    /**
     * The keyboard window was hidden: unbind now unless a command is running (the idle timer then
     * does it after the command). Call from the IME's hide hook; cheap and safe when nothing is bound.
     */
    fun onKeyboardHidden() {
        mainHandler.post { unbindIfIdle() }
    }

    private fun unbindIfIdle() {
        // Idle check and unbind decision are one step under the state's lock.
        unbind { bindings.unbindIfIdle(it) }
    }

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedShellService::class.java.name)
    )
        .daemon(false)
        .tag("privileged_shell")
        .processNameSuffix("privileged_shell")
        .version(AIDL_VERSION + BuildConfig.VERSION_CODE)

    /** The bound service, binding it first if needed. BLOCKING, up to [BIND_TIMEOUT_MS]. */
    private fun connect(context: Context): ServiceBindingState.Claimed<IPrivilegedShellService>? {
        bindings.current()?.takeIf { it.service.asBinder().pingBinder() }?.let { return it }
        val latch = CountDownLatch(1)
        val args = args(context)
        // Shizuku delivers the connection on the main thread; bind from there too.
        val gen = bindings.beginBind(
            create = { Binding(args, connectionFor(it, latch)) },
            post = { b ->
                mainHandler.post {
                    runCatching { Shizuku.bindUserService(b.args, b.connection) }
                        .onFailure {
                            Log.w(TAG, "bindUserService failed", it)
                            latch.countDown()
                        }
                }
            },
        )
        try {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return bindings.current()?.takeIf { it.generation == gen && it.service.asBinder().pingBinder() }
    }

    /**
     * Unbinds and kills the service process (a stuck command dies with it). [take] decides, under
     * the state's lock, whether there is a binding to undo and hands it to the callback it is given;
     * the Shizuku call itself is posted to the main thread from inside that lock, so unbinds and
     * binds reach Shizuku in the order they were decided.
     */
    private fun unbind(take: ((Binding) -> Unit) -> Boolean) {
        take { b ->
            mainHandler.post { runCatching { Shizuku.unbindUserService(b.args, b.connection, true) } }
        }
    }
}
