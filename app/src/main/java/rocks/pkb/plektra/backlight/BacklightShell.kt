package rocks.pkb.plektra.backlight

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.Shizuku
import rocks.pkb.plektra.ShizukuStatus
import rocks.pkb.plektra.resolveShizukuStatus

/** Outcome of one shell command. [completed] is false when it timed out or could not start. */
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

/** [BacklightShell] backed by Shizuku's shell process. */
internal object ShizukuBacklightShell : BacklightShell {
    private const val TAG = "KeyboardBacklight"
    private const val TIMEOUT_SECONDS = 5L

    override fun status(): ShizukuStatus = resolveShizukuStatus()

    override fun run(command: UnihertzBacklightVendorCommand): BacklightShellResult {
        if (status() != ShizukuStatus.Connected) return BacklightShellResult(false, null, "")
        val process = try {
            startProcess(command.argv().toTypedArray())
        } catch (error: Exception) {
            Log.w(TAG, "Could not start ${command::class.simpleName}", error)
            return BacklightShellResult(false, null, "")
        }
        return try {
            process.outputStream.close()
            val output = AtomicReference<String?>(null)
            val reader = Thread {
                output.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrNull())
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            reader.join(TimeUnit.SECONDS.toMillis(1))
            val text = output.get()
            if (!finished || text == null) {
                // Output that was not read completely is never interpreted.
                BacklightShellResult(false, null, "")
            } else {
                BacklightShellResult(true, process.exitValue(), text)
            }
        } catch (error: Exception) {
            Log.w(TAG, "${command::class.simpleName} failed", error)
            BacklightShellResult(false, null, "")
        } finally {
            process.destroy()
        }
    }

    private fun startProcess(argv: Array<String>): Process {
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        newProcess.isAccessible = true
        return newProcess.invoke(null, argv, null, null) as Process
    }
}
