package rocks.pkb.plektra.core.suggestions

import rocks.pkb.plektra.backup.LearningOperationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException

/** *Serial, lazy IO worker. Failed operations remain queued; backup barriers retry or report failure.* */
internal class OrderedPersistenceQueue(
    name: String,
    private val executor: ExecutorService = java.util.concurrent.ThreadPoolExecutor(0, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(), java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        })
) {
    private val lock = Any()
    private val pending = ArrayDeque<() -> Unit>()
    private var scheduled = false
    private var closing = false
    private var closed = false
    private var failure: Throwable? = null

    init {
        LearningOperationGate.registerPendingWriter(this) { withContext(Dispatchers.IO) { flush() } }
    }

    fun enqueue(operation: () -> Unit) = synchronized(lock) {
        check(!closing) { "Persistence queue is closing" }
        pending.addLast(operation)
        scheduleLocked()
    }

    private fun scheduleLocked() {
        if (scheduled || closed) return
        scheduled = true
        executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val operation: () -> Unit = synchronized(lock) {
                if (pending.isEmpty()) {
                    scheduled = false
                    failure = null
                    if (closing) closeLocked()
                    return
                }
                pending.peekFirst()!!
            }
            try {
                operation()
            } catch (error: Exception) {
                synchronized(lock) { failure = error; scheduled = false }
                android.util.Log.e("LearningPersistence", "Learning write retained for retry")
                return
            }
            synchronized(lock) { pending.removeFirst(); failure = null }
        }
    }

    /** *Call off the input thread, or for an explicit destructive/settings operation.* */
    fun flush() {
        val barrier = synchronized(lock) {
            if (closed) return
            executor.submit {
                drain()
                synchronized(lock) { failure?.let { throw it } }
            }
        }
        try {
            barrier.get()
        } catch (error: ExecutionException) {
            throw IllegalStateException("Pending learning could not be persisted", error.cause)
        }
    }

    /** *Finish accepted operations on normal shutdown; never cancel queued learning.* */
    fun closeWhenDrained() = synchronized(lock) {
        closing = true
        if (pending.isEmpty() && !scheduled) closeLocked() else scheduleLocked()
    }

    private fun closeLocked() {
        closed = true
        LearningOperationGate.unregisterPendingWriter(this)
        executor.shutdown()
    }
}
