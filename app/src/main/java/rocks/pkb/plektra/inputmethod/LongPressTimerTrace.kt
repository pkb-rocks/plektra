package rocks.pkb.plektra.inputmethod

import android.os.SystemClock
import rocks.pkb.plektra.inputmethod.aospkeyboard.TimerEvent
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceHub
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceSink
import rocks.pkb.plektra.inputmethod.aospkeyboard.TraceDiagnostic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Records scheduled, fired and cancelled steps of the hardware long-press timer while a
 * typing-training editor is traced; a no-op otherwise. Only primitive values on the key path.
 */
internal class LongPressTimerTrace(
    private val sink: () -> TouchTraceSink? = { TouchTraceHub.activeSink },
    private val clock: () -> Long = SystemClock::uptimeMillis
) {
    private class Pending(
        val generation: Long,
        val keyCode: Int,
        val thresholdMs: Long,
        val scheduledUptimeMs: Long,
        val mode: String,
        val causeSeq: Long,
        val downTimeMs: Long,
        val downEventTimeMs: Long
    )

    private val byGeneration = ConcurrentHashMap<Long, Pending>()
    private val currentByKey = ConcurrentHashMap<Int, Long>()

    /** Returns the timer generation to pass to [fired]; 0 when tracing is off. */
    fun scheduled(keyCode: Int, thresholdMs: Long, mode: String): Long {
        val target = sink() ?: return 0
        val now = clock()
        val down = TouchTraceHub.lastRawDown(keyCode)
        val pending = Pending(
            generation = generations.incrementAndGet(),
            keyCode = keyCode,
            thresholdMs = thresholdMs,
            scheduledUptimeMs = now,
            mode = mode,
            causeSeq = down?.seq ?: 0,
            downTimeMs = down?.downTimeMs ?: -1,
            downEventTimeMs = down?.eventTimeMs ?: -1
        )
        // A runnable that is rescheduled without removal stays posted and still fires.
        currentByKey.put(keyCode, pending.generation)?.let { previous ->
            byGeneration[previous]?.let { emit(target, it, "superseded", now) }
        }
        if (byGeneration.size >= MAX_PENDING) byGeneration.clear()
        byGeneration[pending.generation] = pending
        emit(target, pending, "scheduled", now)
        return pending.generation
    }

    fun fired(generation: Long, keyStillPressed: Boolean) {
        if (generation == 0L) return
        val pending = byGeneration.remove(generation) ?: return
        currentByKey.remove(pending.keyCode, generation)
        val target = sink() ?: return
        val now = clock()
        emit(target, pending, "fired", now, keyStillPressed = keyStillPressed)
        // Kept for readers of schema 5 diagnostics.
        target.onDiagnostic(
            TraceDiagnostic(
                type = "long_press_fired",
                uptimeMs = now,
                durationMs = now - pending.scheduledUptimeMs,
                keyCode = pending.keyCode,
                detail = "threshold=${pending.thresholdMs} mode=${pending.mode} pressed=$keyStillPressed"
            )
        )
    }

    /** Call only when a still-posted runnable was actually removed. */
    fun cancelled(keyCode: Int, reason: String) {
        val generation = currentByKey.remove(keyCode) ?: return
        val pending = byGeneration.remove(generation) ?: return
        val target = sink() ?: return
        emit(target, pending, "cancelled", clock(), reason = reason)
    }

    fun cancelAll(reason: String) {
        currentByKey.keys.toList().forEach { cancelled(it, reason) }
    }

    private fun emit(
        target: TouchTraceSink,
        pending: Pending,
        kind: String,
        now: Long,
        reason: String = "",
        keyStillPressed: Boolean? = null
    ) {
        target.onTimer(
            TimerEvent(
                seq = TouchTraceHub.nextSeq(),
                generation = pending.generation,
                timer = TIMER,
                kind = kind,
                keyCode = pending.keyCode,
                uptimeMs = now,
                thresholdMs = pending.thresholdMs,
                scheduledUptimeMs = pending.scheduledUptimeMs,
                dueUptimeMs = pending.scheduledUptimeMs + pending.thresholdMs,
                causeSeq = pending.causeSeq,
                downTimeMs = pending.downTimeMs,
                downEventTimeMs = pending.downEventTimeMs,
                mode = pending.mode,
                reason = reason,
                keyStillPressed = keyStillPressed
            )
        )
    }

    companion object {
        const val TIMER = "hardware_long_press"
        private const val MAX_PENDING = 256
        private val generations = AtomicLong()
    }
}
