package rocks.pkb.plektra.backlight

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import rocks.pkb.plektra.ShizukuStatus
import rocks.pkb.plektra.resolveShizukuStatus

/**
 * Outcome of one shell command. [completed] is true only when the process exited by itself
 * within the time limit and its output was read completely.
 */
internal data class BacklightShellResult(
    val completed: Boolean,
    val exitCode: Int?,
    val output: String
) {
    val succeeded: Boolean get() = completed && exitCode == 0
}

/** Runs [UnihertzBacklightVendorCommand]s with shell privileges. Blocking. */
internal interface BacklightShell {
    fun status(): ShizukuStatus
    fun run(command: UnihertzBacklightVendorCommand): BacklightShellResult
}

/**
 * [BacklightShell] backed by Shizuku's shell process.
 *
 * `ShizukuRemoteProcess` does not override `Process.waitFor(long, TimeUnit)`. The inherited
 * version polls `exitValue()`, whose "still running" error reaches the client as an
 * IllegalArgumentException and escapes the poll, so the remote `waitForTimeout` is used instead.
 * After Shizuku's binder dies, every remote call, including `destroy()`, can throw.
 */
internal class ShizukuBacklightShell(
    private val statusProvider: () -> ShizukuStatus = ::resolveShizukuStatus,
    private val startProcess: (Array<String>) -> ShizukuRemoteProcess = ::newShizukuProcess,
    private val timeoutMillis: Long = TimeUnit.SECONDS.toMillis(5)
) : BacklightShell {

    override fun status(): ShizukuStatus = statusProvider()

    override fun run(command: UnihertzBacklightVendorCommand): BacklightShellResult {
        if (status() != ShizukuStatus.Connected) return NOT_COMPLETED
        val name = command::class.simpleName
        val process = try {
            startProcess(command.argv().toTypedArray())
        } catch (error: Exception) {
            Log.w(TAG, "Could not start $name", error)
            return NOT_COMPLETED
        }
        return try {
            runCatching { process.outputStream.close() }
            val output = AtomicReference<String?>(null)
            val input = process.inputStream
            val reader = Thread {
                output.set(runCatching { input.bufferedReader().use { it.readText() } }.getOrNull())
            }.apply {
                isDaemon = true
                start()
            }
            val exited = process.waitForTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!exited) return NOT_COMPLETED
            val exitCode = process.exitValue()
            reader.join(READER_GRACE_MILLIS)
            // Output that was not read completely is never interpreted.
            val text = output.get() ?: return NOT_COMPLETED
            BacklightShellResult(true, exitCode, text)
        } catch (error: Exception) {
            Log.w(TAG, "$name failed", error)
            NOT_COMPLETED
        } finally {
            runCatching { process.destroy() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    companion object {
        private const val TAG = "KeyboardBacklight"
        private const val READER_GRACE_MILLIS = 1_000L
        private val NOT_COMPLETED = BacklightShellResult(false, null, "")

        private fun newShizukuProcess(argv: Array<String>): ShizukuRemoteProcess {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcess.isAccessible = true
            return newProcess.invoke(null, argv, null, null) as ShizukuRemoteProcess
        }
    }
}
