package rocks.pkb.plektra.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

/**
 * the idle/hide unbind races a command that is just starting, and a late
 * callback of an old connection races a newer bind. The state class decides both under one lock
 * and tags every bind with a generation; these pin the three outcomes that matter.
 */
class ServiceBindingStateTest {

    private class Bind(val gen: Long)

    private val state = ServiceBindingState<String, Bind>()
    private val posted = mutableListOf<Bind>()

    private fun bind(): Long = state.beginBind({ Bind(it) }, { posted += it })

    /** (a) A command that claimed the service is never cut off by the idle unbind. */
    @Test
    fun idleUnbindNeverTearsDownAClaimedService() {
        val gen = bind()
        state.connected(gen, "svc")
        state.claim()
        assertFalse(state.unbindIfIdle { posted += it })
        assertEquals("svc", state.current()?.service)
        assertEquals(listOf(gen), posted.map { it.gen })

        // Once the command ends, the idle unbind may go ahead.
        assertTrue(state.release())
        assertTrue(state.unbindIfIdle { posted += it })
        assertNull(state.current())
    }

    /** (a) An unbind that ran before the claim leaves nothing to reuse: the command binds afresh. */
    @Test
    fun anUnbindBeforeTheClaimMakesTheCommandBindAfresh() {
        val gen = bind()
        state.connected(gen, "svc")
        assertTrue(state.unbindIfIdle { posted += it })
        state.claim()
        assertNull(state.current())
        val next = bind()
        assertTrue(next > gen)
        assertTrue(state.connected(next, "svc2"))
        assertEquals("svc2", state.current()?.service)
    }

    /** (b) A newer bind always has a binding to tear down, however unbinds and binds interleave. */
    @Test
    fun aNewerBindIsNeverLeftWithoutABindingToTearDown() {
        val first = bind()
        state.connected(first, "old")
        // A stuck command's kill and the idle unbind both aim at the old generation.
        assertTrue(state.unbindGeneration(first) { posted += it })
        val second = bind()
        // The second of the two unbinds arrives after the newer bind was queued: it is a no-op.
        assertFalse(state.unbindGeneration(first) { posted += it })

        val torn = mutableListOf<Bind>()
        assertTrue(state.unbindIfIdle { torn += it })
        assertEquals(listOf(second), torn.map { it.gen })
    }

    /** (b) The same, through the idle check: the stale kill must not null the newer binding. */
    @Test
    fun aStaleKillCannotClearANewerBinding() {
        val first = bind()
        state.connected(first, "old")
        assertTrue(state.unbindIfIdle { posted += it })
        val second = bind()
        assertFalse(state.unbindGeneration(first) { fail() })
        val torn = mutableListOf<Bind>()
        assertTrue(state.unbindGeneration(second) { torn += it })
        assertEquals(second, torn.single().gen)
    }

    /** (c) A late disconnect of an old connection changes nothing about the current one. */
    @Test
    fun aLateDisconnectOfAnOldConnectionAffectsNothingCurrent() {
        val first = bind()
        state.connected(first, "old")
        assertTrue(state.unbindIfIdle { posted += it })
        val second = bind()
        state.connected(second, "new")
        state.claim()
        val call = CompletableFuture<String>()
        state.setInFlight(call)

        state.disconnected(first) // the killed service's callback, arriving late

        assertEquals("new", state.current()?.service)
        assertFalse(call.isCancelled)
        // And a late connect of the old connection cannot replace the new service either.
        assertFalse(state.connected(first, "ghost"))
        assertEquals("new", state.current()?.service)
    }

    /** The current connection's disconnect still forgets the service and cancels the call. */
    @Test
    fun theCurrentDisconnectForgetsTheServiceAndCancelsTheCall() {
        val gen = bind()
        state.connected(gen, "svc")
        state.claim()
        val call = CompletableFuture<String>()
        state.setInFlight(call)

        state.disconnected(gen)

        assertNull(state.current())
        assertTrue(call.isCancelled)
    }

    @Test
    fun aDeadServiceIsDroppedOnlyWhileItsGenerationIsCurrent() {
        val first = bind()
        state.connected(first, "old")
        assertTrue(state.unbindIfIdle { posted += it })
        val second = bind()
        state.connected(second, "new")

        state.dropService(first)
        assertEquals("new", state.current()?.service)

        state.dropService(second)
        assertNull(state.current())
    }

    private fun fail() {
        org.junit.Assert.fail("must not be called")
    }
}
