package rocks.pkb.plektra.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerStatusMonitorTest {

    @Test
    fun invalidateBumpsTheCounterAndClearsTheVerdict() {
        val before = BrokerStatusMonitor.invalidations.value
        BrokerStatusMonitor.invalidate()
        BrokerStatusMonitor.invalidate()
        // Screens key their re-check on this value; it must change every time.
        assertEquals(before + 2, BrokerStatusMonitor.invalidations.value)
        assertNull(BrokerStatusMonitor.status.value)
        assertNull(BrokerStatusMonitor.backend.value)
    }

    @Test
    fun exclusiveHoldsOffACheckUntilTheBlockIsDone() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val holder = async {
            BrokerStatusMonitor.exclusive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        // A second exclusive section stands in for refresh(): both take the same lock.
        var secondRan = false
        val second = async { BrokerStatusMonitor.exclusive { secondRan = true } }
        repeat(5) { yield() }
        assertFalse(secondRan)
        release.complete(Unit)
        holder.await()
        second.await()
        assertTrue(secondRan)
    }
}
