
package rocks.pkb.plektra.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

object LearningOperationGate {
    private val pendingWriters = java.util.concurrent.ConcurrentHashMap<Any, suspend () -> Unit>()

    /** *Include writers from a closing keyboard session until their accepted updates are durable.* */
    fun registerPendingWriter(owner: Any, drain: suspend () -> Unit) { pendingWriters[owner] = drain }
    fun unregisterPendingWriter(owner: Any) { pendingWriters.remove(owner) }

    private val admissionLock = Any()
    /** Admission and suspension share a lock, so backup cannot miss an accepted enqueue. */
    fun learnIfAllowed(block: () -> Unit): Boolean = synchronized(admissionLock) {
        if (learningSuspended.get()) return false
        block()
        true
    }

    private val mutex = Mutex()
    private val learningSuspended = AtomicBoolean(false)

    val isLearningSuspended: Boolean
        get() = learningSuspended.get()

    suspend fun <T> withExclusiveOperation(
        block: suspend () -> T
    ): T = mutex.withLock {
        synchronized(admissionLock) { learningSuspended.set(true) }
        try {
            block()
        } finally {
            synchronized(admissionLock) { learningSuspended.set(false) }
        }
    }


    suspend fun awaitPendingLearning() {
        pendingWriters.values.toList().forEach { it.invoke() }
    }
}
