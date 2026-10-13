package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Bundle
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.shizuku.ShizukuUserServiceShell

/**
 * which route a privileged command takes once it is through the broker's door
 * ([EmbeddedAdbShell.execute]). The door's checks (trigger, read-only screens, the closed command
 * set, terminal-last, the audit log) are the same for both; only the last step differs.
 */
enum class PrivilegedBackend(val auditName: String) {
    /** The app's own wireless-ADB broker (pairing, Wireless debugging, adbd). */
    BROKER("broker"),

    /** A Shizuku server (Shizuku, ShizukuTendCF, ...) through Plektra's UserService. */
    SHIZUKU("shizuku"),
}

/** The user's "Privileged access via" setting. */
enum class PrivilegedAccessMode(val prefValue: String) {
    /** Shizuku while its binder is alive and Plektra is allowed, otherwise the broker. */
    AUTOMATIC(SettingsManager.PRIVILEGED_ACCESS_AUTO),
    SHIZUKU_ONLY(SettingsManager.PRIVILEGED_ACCESS_SHIZUKU),
    BROKER_ONLY(SettingsManager.PRIVILEGED_ACCESS_BROKER);

    companion object {
        fun fromPref(value: String?): PrivilegedAccessMode =
            entries.firstOrNull { it.prefValue == value } ?: AUTOMATIC
    }
}

/** What Shizuku looks like from here. */
enum class ShizukuState {
    /** No Shizuku app on the phone (and no binder from a root-only server such as Sui). */
    NOT_INSTALLED,

    /** Installed, but its server is not running (no binder). */
    NOT_RUNNING,

    /**
     * A Shizuku server older than v11 is reachable, but it cannot hand out the permission or the
     * user service this backend needs ([rikka.shizuku.Shizuku.isPreV11]).
     */
    TOO_OLD,

    /** Running, but Plektra has not been allowed in Shizuku. */
    NOT_ALLOWED,

    /**
     * Running, not allowed, and Android will not show the prompt again. Seen on Sui only, after
     * "Deny and don't ask again" (`shouldShowRequestPermissionRationale()` is true, so
     * `requestPermission` answers DENIED without a dialog). Shizuku's own Deny is one-time, so
     * it stays [NOT_ALLOWED]. The user has to allow Plektra in Sui's management screen.
     */
    DENIED_PERMANENTLY,

    /** Running and Plektra is allowed. */
    READY,
}

/**
 * What one argv run did through Shizuku. [ok] means the program ran to its end within the
 * deadline and the output limit, whatever its exit code (the broker's adbd shell reports no exit
 * code, so callers judge the result by its output or by asking Android afterwards, as they do for
 * the broker). [exitCode] is null when the program did not finish. [serviceUnavailable] is true
 * when nothing ran, or the run was cut off, because Shizuku or its service could not be reached
 * or died. [uid] is the uid the service ran as (`Os.getuid()`: 2000 over adb, 0 under root), null
 * when no service answered.
 */
data class ShellRunResult(
    val ok: Boolean,
    val output: String?,
    val exitCode: Int?,
    val failure: String?,
    val serviceUnavailable: Boolean = false,
    val uid: Int? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_OK, ok)
        putString(KEY_OUTPUT, output)
        putInt(KEY_EXIT, exitCode ?: NO_EXIT)
        putString(KEY_FAILURE, failure)
        putInt(KEY_UID, uid ?: NO_EXIT)
    }

    companion object {
        private const val KEY_OK = "ok"
        private const val KEY_OUTPUT = "output"
        private const val KEY_EXIT = "exit"
        private const val KEY_FAILURE = "failure"
        private const val KEY_UID = "uid"
        private const val NO_EXIT = Int.MIN_VALUE

        fun unavailable(reason: String) =
            ShellRunResult(ok = false, output = null, exitCode = null, failure = reason, serviceUnavailable = true)

        /**
         * Reads the service's answer. The output limit is checked here as well, so a misbehaving
         * service cannot hand the app more than the broker would accept. Decoding N bytes gives
         * at most N chars, so a longer string cannot have come from a capped run.
         */
        fun fromBundle(bundle: Bundle?, maxOutputBytes: Int): ShellRunResult {
            if (bundle == null) return unavailable("Shizuku service returned nothing")
            val output = bundle.getString(KEY_OUTPUT)
            val tooLong = output != null && output.length > maxOutputBytes
            if (tooLong) {
                return ShellRunResult(false, null, null, "shell output exceeded $maxOutputBytes bytes")
            }
            val exit = bundle.getInt(KEY_EXIT, NO_EXIT).takeIf { it != NO_EXIT }
            return ShellRunResult(
                ok = bundle.getBoolean(KEY_OK, false),
                output = output,
                exitCode = exit,
                failure = bundle.getString(KEY_FAILURE),
                uid = bundle.getInt(KEY_UID, NO_EXIT).takeIf { it != NO_EXIT },
            )
        }
    }
}

/**
 * The Shizuku side of the door, behind an interface so tests can run without a Shizuku server.
 * [runArgv] takes a raw argv: only [EmbeddedAdbShell.execute] may call it, after its checks, with
 * an array from [PrivilegedCommand.argvs] (BrokerSourceRulesTest).
 */
internal interface ShizukuShell {
    fun state(context: Context): ShizukuState

    /**
     * BLOCKING: binds the service if needed, then runs the program [argv] (no shell). A service
     * that dies or disconnects during the run gives a failed result with
     * [ShellRunResult.serviceUnavailable] set, never an ok one. Never throws.
     */
    fun runArgv(context: Context, argv: List<String>): ShellRunResult
}

/** Chooses the backend for the setting and Shizuku's state. */
object PrivilegedBackends {

    /** The real Shizuku; tests swap in a fake. */
    @Volatile
    internal var shizuku: ShizukuShell = ShizukuUserServiceShell

    /** The setting and Shizuku's state at one moment, and the backend they choose. */
    data class Resolved(
        val mode: PrivilegedAccessMode,
        val shizukuState: ShizukuState,
        val backend: PrivilegedBackend,
    )

    fun mode(context: Context): PrivilegedAccessMode =
        PrivilegedAccessMode.fromPref(SettingsManager.getPrivilegedAccessBackend(context))

    fun shizukuState(context: Context): ShizukuState =
        runCatching { shizuku.state(context.applicationContext) }.getOrDefault(ShizukuState.NOT_RUNNING)

    /**
     * The rule: Automatic picks Shizuku only when it is [ShizukuState.READY]; "Shizuku only" picks
     * Shizuku whatever its state (a command then fails with the reason, never falls back to the
     * broker); "Built-in broker only" never uses Shizuku.
     */
    fun choose(mode: PrivilegedAccessMode, state: ShizukuState): PrivilegedBackend = when (mode) {
        PrivilegedAccessMode.AUTOMATIC ->
            if (state == ShizukuState.READY) PrivilegedBackend.SHIZUKU else PrivilegedBackend.BROKER
        PrivilegedAccessMode.SHIZUKU_ONLY -> PrivilegedBackend.SHIZUKU
        PrivilegedAccessMode.BROKER_ONLY -> PrivilegedBackend.BROKER
    }

    fun resolve(context: Context): Resolved {
        val mode = mode(context)
        val state = shizukuState(context)
        return Resolved(mode, state, choose(mode, state))
    }

    fun current(context: Context): PrivilegedBackend = resolve(context).backend

    /**
     * Cheap pre-flight before a privileged action: can the chosen backend run at all? For the
     * broker that is "a key is stored" ([EmbeddedAdbShell.isPaired]); for Shizuku, "running and
     * allowed". Not a readiness claim for the UI: screens use the verified status.
     */
    fun hasAccess(context: Context): Boolean {
        val resolved = resolve(context)
        return when (resolved.backend) {
            PrivilegedBackend.SHIZUKU -> resolved.shizukuState == ShizukuState.READY
            PrivilegedBackend.BROKER -> EmbeddedAdbShell.isPaired(context)
        }
    }

    /** The reason a command could not run through Shizuku in [state]; null when it is ready. */
    fun shizukuUnavailableReason(state: ShizukuState): String? = when (state) {
        ShizukuState.READY -> null
        ShizukuState.NOT_ALLOWED -> REASON_SHIZUKU_NOT_ALLOWED
        ShizukuState.NOT_RUNNING -> REASON_SHIZUKU_NOT_RUNNING
        ShizukuState.NOT_INSTALLED -> REASON_SHIZUKU_NOT_INSTALLED
        ShizukuState.TOO_OLD -> REASON_SHIZUKU_TOO_OLD
        ShizukuState.DENIED_PERMANENTLY -> REASON_SHIZUKU_DENIED_PERMANENTLY
    }

    const val REASON_SHIZUKU_NOT_ALLOWED = "Shizuku is running but Plektra is not allowed."
    const val REASON_SHIZUKU_NOT_RUNNING = "Shizuku is not running."
    const val REASON_SHIZUKU_NOT_INSTALLED = "Shizuku is not installed."
    const val REASON_SHIZUKU_TOO_OLD = "Shizuku is too old (it needs version 11 or newer)."
    const val REASON_SHIZUKU_DENIED_PERMANENTLY = "Plektra was denied in Sui with \"don't ask again\"."
}
