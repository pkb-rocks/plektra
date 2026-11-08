package rocks.pkb.plektra.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import rocks.pkb.plektra.inputmethod.aospkeyboard.HardwareKeyDecision
import rocks.pkb.plektra.inputmethod.aospkeyboard.TimerEvent
import rocks.pkb.plektra.inputmethod.aospkeyboard.TraceDiagnostic

class TypingTrainingRecorderTest {

    private val recorder = TypingTrainingRecorder(TypingTrainingContextTimeline(clock = { 0 }, nextSeq = { 0 }))

    @Test
    fun countsEveryEventAndDropsBeyondTheLimit() {
        val limit = TypingTrainingRecorder.LIMITS.getValue(TypingTrainingRecorder.KIND_DIAGNOSTIC)
        repeat(limit + 3) { recorder.onDiagnostic(TraceDiagnostic("main_thread_stall", it.toLong())) }

        repeat(4) { recorder.onTextEdit(TextEdit(it.toLong(), 0, 0, "", "a", "append")) }
        val stats = recorder.captureStats()

        assertEquals(limit, recorder.diagnostics.size)
        assertEquals((limit + 3).toLong(), stats.countsByKind[TypingTrainingRecorder.KIND_DIAGNOSTIC])
        assertEquals(3L, stats.droppedByKind[TypingTrainingRecorder.KIND_DIAGNOSTIC])
        assertEquals(4L, stats.countsByKind[TypingTrainingRecorder.KIND_TEXT_EDIT])
        assertEquals(4, recorder.textEdits.size)
        assertEquals(limit, stats.limits[TypingTrainingRecorder.KIND_DIAGNOSTIC])
    }

    @Test
    fun statsReportSequenceRangeAndOmitKindsWithoutDrops() {
        recorder.onKeyDecision(HardwareKeyDecision(12, 11, 0, "down", "bounce_keys", "suppressed", keyCode = 29))
        recorder.onTimer(TimerEvent(15, 1, "hardware_long_press", "scheduled", 29, 0, 300, 0, 300))

        val stats = recorder.captureStats()

        assertEquals(12L, stats.firstSeq)
        assertEquals(15L, stats.lastSeq)
        assertEquals(1L, stats.countsByKind[TypingTrainingRecorder.KIND_KEY_DECISION])
        assertFalse(TypingTrainingRecorder.KIND_KEY_DECISION in stats.droppedByKind)
    }
}
