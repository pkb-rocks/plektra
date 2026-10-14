package rocks.pkb.plektra.shizuku

import rocks.pkb.plektra.inputmethod.ShellRunResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * runs one program to its end under the broker's two limits: at most
 * `maxOutputBytes` of output (stdout and stderr together, as adbd's shell gives them) and at most
 * `deadlineMs` from start to exit. Past either limit the process is killed and the run fails, as
 * an [moe.shizuku.manager.adb.AdbClient] session would. The program is started from an argv
 * array, never through `sh -c`, so there is no wrapper shell of ours.
 *
 * What the kill guarantees, exactly: `Process.destroyForcibly()` ends the process that was
 * started, and only that one. Java's ProcessBuilder cannot put the child in its own process
 * group, so a child of that process is NOT killed. On Android `pm`, `settings`, `wm` and
 * `appops` may be shell scripts that run `cmd` as a child `[INFERENCE: platform files, not in
 * this repo]`; if so, that `cmd` can outlive the kill and still finish its action. The run is
 * reported as failed either way, never as success. Plain JVM, so it is tested with a fake
 * process; it runs inside the Shizuku UserService ([PrivilegedShellService]).
 */
internal object CappedProcessRunner {

    private const val CHUNK = 8 * 1024

    /**
     * Starts `ProcessBuilder(argv)` (stderr merged into stdout) and runs it under the limits. A
     * program that cannot start fails the run like any other failure. [start] is the seam for
     * tests.
     */
    fun runArgv(
        argv: List<String>,
        maxOutputBytes: Int,
        deadlineMs: Long,
        start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    ): ShellRunResult {
        val process = try {
            start(argv)
        } catch (e: IOException) {
            return ShellRunResult(false, null, null, "${e.javaClass.simpleName}: ${e.message}")
        }
        return run(process, maxOutputBytes, deadlineMs)
    }

    fun run(
        process: Process,
        maxOutputBytes: Int,
        deadlineMs: Long,
        nowMs: () -> Long = System::currentTimeMillis,
    ): ShellRunResult {
        val started = nowMs()
        // Nothing is ever written to the command's stdin.
        runCatching { process.outputStream.close() }

        val buffer = ByteArrayOutputStream()
        val overflow = AtomicBoolean(false)
        val reader = Thread({
            try {
                val input = process.inputStream
                val chunk = ByteArray(CHUNK)
                while (!overflow.get()) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) {
                        if (buffer.size() + n > maxOutputBytes) {
                            overflow.set(true)
                        } else {
                            buffer.write(chunk, 0, n)
                        }
                    }
                }
            } catch (_: IOException) {
                // The stream closes when the process is killed.
            }
        }, "shizuku-shell-output").apply { isDaemon = true }
        reader.start()

        fun output() = synchronized(buffer) { buffer.toString(Charsets.UTF_8.name()) }

        fun kill(reason: String): ShellRunResult {
            runCatching { process.destroyForcibly() }
            reader.join(1_000)
            return ShellRunResult(ok = false, output = output().ifEmpty { null }, exitCode = null, failure = reason)
        }

        try {
            reader.join(deadlineMs.coerceAtLeast(1))
            if (overflow.get()) return kill("shell output exceeded $maxOutputBytes bytes")
            if (reader.isAlive) return kill(deadlineFailure(deadlineMs))
            val left = deadlineMs - (nowMs() - started)
            if (left <= 0 || !process.waitFor(left, TimeUnit.MILLISECONDS)) {
                return kill(deadlineFailure(deadlineMs))
            }
            return ShellRunResult(ok = true, output = output(), exitCode = process.exitValue(), failure = null)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return kill("interrupted")
        }
    }

    fun deadlineFailure(deadlineMs: Long) = "the command did not finish within $deadlineMs ms"
}
