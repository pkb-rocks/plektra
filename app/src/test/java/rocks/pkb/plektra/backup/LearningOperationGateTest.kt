
package rocks.pkb.plektra.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LearningOperationGateTest {

    @Test
    fun suspensionIsActiveInsideOperationAndReleasedAfter() = runBlocking {
        assertFalse(LearningOperationGate.isLearningSuspended)

        LearningOperationGate.withExclusiveOperation {
            assertTrue(LearningOperationGate.isLearningSuspended)
        }

        assertFalse(LearningOperationGate.isLearningSuspended)
    }

    @Test
    fun concurrentOperationsAreSerialized() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            LearningOperationGate.withExclusiveOperation {
                entered.complete(Unit)
                release.await()
            }
        }

        entered.await()

        val second = async {
            LearningOperationGate.withExclusiveOperation {
                secondEntered.complete(Unit)
            }
        }

        yield()

        assertFalse(
            "Second operation must wait for the first",
            secondEntered.isCompleted
        )

        release.complete(Unit)
        first.await()
        second.await()

        assertTrue(secondEntered.isCompleted)
        assertFalse(LearningOperationGate.isLearningSuspended)
    }
    @Test fun exclusiveOperationBlocksAdmissionAndReleasesItAfterFailure() = runBlocking {
        var accepted=0
        assertTrue(LearningOperationGate.learnIfAllowed {accepted++})
        try {
            LearningOperationGate.withExclusiveOperation {
                assertFalse(LearningOperationGate.learnIfAllowed {accepted++})
                throw IllegalStateException("synthetic reset failure")
            }
        } catch(_:IllegalStateException) {}
        assertFalse(LearningOperationGate.isLearningSuspended)
        assertTrue(LearningOperationGate.learnIfAllowed {accepted++})
        assertEquals(2,accepted)
    }
}
