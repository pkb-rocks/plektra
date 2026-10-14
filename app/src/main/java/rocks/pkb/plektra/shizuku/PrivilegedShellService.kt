package rocks.pkb.plektra.shizuku

import android.content.Context
import android.os.Bundle
import android.system.Os
import androidx.annotation.Keep
import moe.shizuku.manager.adb.CLIENT_DEADLINE_MS
import moe.shizuku.manager.adb.MAX_SHELL_OUTPUT_BYTES
import rocks.pkb.plektra.inputmethod.ShellRunResult
import kotlin.system.exitProcess

/**
 * the Shizuku UserService. Shizuku starts it in its own process, as Shizuku's
 * user (shell when Shizuku was started over ADB, root when started with root), from Plektra's APK,
 * and hands its binder to Plektra only. It runs one program per call, from an argv array with no
 * shell (`ProcessBuilder(argv)`), under the broker's limits: [MAX_SHELL_OUTPUT_BYTES] of output and
 * [CLIENT_DEADLINE_MS] per program, whatever the caller asks for. Every answer carries the uid the
 * service runs as, so the app can audit it.
 *
 * Which commands reach it is decided in the app process, by the broker's door
 * ([rocks.pkb.plektra.inputmethod.EmbeddedAdbShell.execute]); this class only runs them.
 */
class PrivilegedShellService : IPrivilegedShellService.Stub {

    /** Shizuku before API 13 calls the no-argument constructor. */
    @Keep
    constructor() : super()

    /** Shizuku API 13+ passes a Context when the class has this constructor. */
    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(argv: Array<String>?, maxOutputBytes: Int, deadlineMs: Long): Bundle {
        val uid = Os.getuid()
        if (argv == null || argv.isEmpty() || argv[0].isBlank()) {
            return ShellRunResult(false, null, null, "empty argv", uid = uid).toBundle()
        }
        val cap = maxOutputBytes.coerceIn(1, MAX_SHELL_OUTPUT_BYTES)
        val deadline = deadlineMs.coerceIn(1L, CLIENT_DEADLINE_MS)
        return CappedProcessRunner.runArgv(argv.asList(), cap, deadline).copy(uid = uid).toBundle()
    }
}
