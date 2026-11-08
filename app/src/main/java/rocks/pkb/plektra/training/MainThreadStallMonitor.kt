package rocks.pkb.plektra.training

import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import rocks.pkb.plektra.inputmethod.aospkeyboard.TraceDiagnostic

/**
 * Reports main-looper messages that run longer than [thresholdMs]. The IME and the training
 * activity share this thread, so a stall here delays key and touch handling.
 * Only installed for the duration of a training session.
 */
class MainThreadStallMonitor(
    private val thresholdMs: Long = 32,
    private val onStall: (TraceDiagnostic) -> Unit
) {
    private var dispatchStart = 0L
    private var dispatchTarget = ""
    private var gcCountAtStart = 0L
    private var blockingGcTimeAtStart = 0L

    private val printer = Printer { line ->
        if (line.startsWith(">>>>> Dispatching")) {
            dispatchStart = SystemClock.uptimeMillis()
            dispatchTarget = line
            gcCountAtStart = runtimeStat("art.gc.gc-count")
            blockingGcTimeAtStart = runtimeStat("art.gc.blocking-gc-time")
        } else if (line.startsWith("<<<<< Finished") && dispatchStart > 0) {
            val duration = SystemClock.uptimeMillis() - dispatchStart
            if (duration >= thresholdMs) {
                val gcs = runtimeStat("art.gc.gc-count") - gcCountAtStart
                val blockingGcMs = runtimeStat("art.gc.blocking-gc-time") - blockingGcTimeAtStart
                onStall(
                    TraceDiagnostic(
                        type = "main_thread_stall",
                        uptimeMs = dispatchStart,
                        durationMs = duration,
                        detail = "${dispatchTarget.removePrefix(">>>>> Dispatching to ").take(240)} gcs=$gcs blockingGcMs=$blockingGcMs"
                    )
                )
            }
            dispatchStart = 0
        }
    }

    fun start() = Looper.getMainLooper().setMessageLogging(printer)

    fun stop() = Looper.getMainLooper().setMessageLogging(null)

    private fun runtimeStat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L
}
