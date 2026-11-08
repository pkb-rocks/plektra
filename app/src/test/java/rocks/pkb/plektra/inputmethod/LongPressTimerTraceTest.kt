package rocks.pkb.plektra.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCommit
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot
import rocks.pkb.plektra.inputmethod.aospkeyboard.TimerEvent
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchSample
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceHub
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceSink
import rocks.pkb.plektra.inputmethod.aospkeyboard.TraceDiagnostic

class LongPressTimerTraceTest {

    private val timers = mutableListOf<TimerEvent>()
    private val diagnostics = mutableListOf<TraceDiagnostic>()
    private val sink = object : TouchTraceSink {
        override fun onGeometry(snapshot: KeyboardGeometrySnapshot) = Unit
        override fun onTouch(sample: TouchSample) = Unit
        override fun onKeyCommitted(commit: KeyCommit) = Unit
        override fun onTimer(event: TimerEvent) {
            timers += event
        }
        override fun onDiagnostic(event: TraceDiagnostic) {
            diagnostics += event
        }
    }
    private var now = 1_000L
    private var active = true
    private val trace = LongPressTimerTrace(sink = { sink.takeIf { active } }, clock = { now })

    @After
    fun tearDown() {
        TouchTraceHub.noteRawDown(KEY, TouchTraceHub.RawKeyDown(0, -1, -1))
    }

    @Test
    fun scheduledAndFiredShareGenerationAndReferenceTheRawDown() {
        TouchTraceHub.noteRawDown(KEY, TouchTraceHub.RawKeyDown(seq = 41, eventTimeMs = 990, downTimeMs = 990))
        val generation = trace.scheduled(KEY, thresholdMs = 300, mode = "alt")
        now = 1_340
        trace.fired(generation, keyStillPressed = true)

        assertEquals(listOf("scheduled", "fired"), timers.map { it.kind })
        assertTrue(timers.all { it.generation == generation && it.causeSeq == 41L && it.downEventTimeMs == 990L })
        val fired = timers.last()
        assertEquals(1_300L, fired.dueUptimeMs)
        assertEquals(40L, fired.uptimeMs - fired.dueUptimeMs)
        assertEquals(true, fired.keyStillPressed)
        assertTrue(timers[0].seq < timers[1].seq)
        // Schema 5 readers keep their long_press_fired diagnostic.
        assertEquals("long_press_fired", diagnostics.single().type)
        assertEquals(340L, diagnostics.single().durationMs)
    }

    @Test
    fun cancellationRecordsReasonAndLaterFireIsIgnored() {
        val generation = trace.scheduled(KEY, 300, "alt")
        now = 1_100
        trace.cancelled(KEY, "key_up")
        trace.fired(generation, keyStillPressed = false)
        trace.cancelled(KEY, "key_up")

        assertEquals(listOf("scheduled", "cancelled"), timers.map { it.kind })
        assertEquals("key_up", timers.last().reason)
        assertEquals(1_100L, timers.last().uptimeMs)
    }

    @Test
    fun cancelAfterFireRecordsNothing() {
        val generation = trace.scheduled(KEY, 300, "alt")
        trace.fired(generation, keyStillPressed = false)
        trace.cancelled(KEY, "key_up")

        assertEquals(listOf("scheduled", "fired"), timers.map { it.kind })
    }

    @Test
    fun reschedulingMarksTheStillPostedTimerAsSuperseded() {
        val first = trace.scheduled(KEY, 300, "alt")
        val second = trace.scheduled(KEY, 300, "alt")
        trace.fired(first, keyStillPressed = true)
        trace.cancelled(KEY, "key_up")

        assertNotEquals(first, second)
        assertEquals(listOf("scheduled", "superseded", "scheduled", "fired", "cancelled"), timers.map { it.kind })
        assertEquals(listOf(first, first, second, first, second), timers.map { it.generation })
    }

    @Test
    fun inactiveSinkRecordsNothing() {
        active = false
        val generation = trace.scheduled(KEY, 300, "alt")
        trace.fired(generation, keyStillPressed = true)

        assertEquals(0L, generation)
        assertTrue(timers.isEmpty())
    }

    private companion object {
        const val KEY = 29
    }
}
