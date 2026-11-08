package rocks.pkb.plektra.clipboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Owns the clipboard history state.
 *
 * A single FIFO worker holds the mutable history and performs every database access. Each
 * request runs to completion before the next one starts, so copies that arrive while the
 * history is still loading simply wait behind the load. A change is published only after
 * its database transaction committed, as an immutable [Snapshot] delivered through
 * [publisher] (the main thread in production). The one exception is protection: a text
 * recognised as sensitive is hidden at once, even when storing that fact fails.
 */
class ClipboardHistory internal constructor(
    private val dao: ClipboardDao,
    private val worker: Executor,
    private val publisher: Executor,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class LoadState { LOADING, READY, ERROR }

    /** Change whose transaction failed; reported through [addFailureListener]. */
    enum class Operation { SAVE, PIN, DELETE, CLEAR, CLEANUP }

    /**
     * Complete, immutable view of the history.
     * @param entries Sorted entries; empty unless [loadState] is [LoadState.READY]. Includes
     *   protection that is not yet stored, see [unsavedProtection].
     * @param nextExpiry Wall-clock time of the next cleanup: the earliest expiry under the last
     *   applied retention, postponed by the retry delay while writes keep failing
     */
    data class Snapshot(
        val loadState: LoadState,
        val entries: List<ClipboardHistoryEntry>,
        val nextExpiry: Long
    )

    // Worker-owned state.
    private var loadState = LoadState.LOADING
    /** Mirrors the committed database rows. */
    private var entries: List<ClipboardHistoryEntry> = emptyList()
    private var retention: ClipboardRetention? = null

    /**
     * Sensitive marking learned from a copy whose transaction failed, by entry id. It applies to
     * every snapshot and to the input of every later change, and is written first in the next
     * transaction; only that commit clears it. It is held in memory only: while no write
     * succeeds, a process restart loses it and the row keeps its weaker flags on disk.
     */
    private val unsavedProtection = HashMap<Long, ClipboardHistoryEntry>()
    private var failedWrites = 0
    private var retryAt = Long.MIN_VALUE

    /** Latest published snapshot; read on the publisher thread. */
    @Volatile
    var snapshot = Snapshot(LoadState.LOADING, emptyList(), Long.MAX_VALUE)
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val failureListeners = CopyOnWriteArraySet<(Operation) -> Unit>()

    fun addListener(listener: (Snapshot) -> Unit) { listeners += listener }

    fun removeListener(listener: (Snapshot) -> Unit) { listeners -= listener }

    /**
     * Failed changes, delivered on the publisher thread after the snapshot that follows them.
     * Requests dropped because the history is not loaded are not reported; the snapshot's
     * [LoadState] already shows that.
     */
    fun addFailureListener(listener: (Operation) -> Unit) { failureListeners += listener }

    fun removeFailureListener(listener: (Operation) -> Unit) { failureListeners -= listener }

    /**
     * Opens and loads the history unless it is already loaded. After a failed load this is
     * the explicit retry; nothing retries on its own.
     */
    internal fun start(retention: ClipboardRetention) = worker.execute {
        if (loadState == LoadState.READY) return@execute
        if (loadState == LoadState.ERROR) {
            loadState = LoadState.LOADING
            publish()
        }
        this.retention = retention
        try {
            val now = clock()
            dao.transaction { dao.deleteExpired(retention.normalCutoff(now), retention.sensitiveCutoff(now)) }
            entries = dao.loadAll().filterNot { retention.isExpired(it, now) }.sorted()
            loadState = LoadState.READY
        } catch (e: Exception) {
            entries = emptyList()
            loadState = LoadState.ERROR
            logFailure("Clipboard history load failed", e)
        }
        publish()
    }

    /** Adds a copied text, or refreshes the entry with the same text without weakening its protection. */
    internal fun addClip(text: String, timestamp: Long, sensitive: Boolean, retention: ClipboardRetention) =
        worker.execute {
            if (!isReady()) return@execute
            if (sensitive) protectStored(text)
            applyChange(Operation.SAVE, retention) { current, now ->
                if (sensitive && timestamp <= retention.sensitiveCutoff(now)) return@applyChange current
                val existing = current.firstOrNull { it.text == text }
                if (existing != null) {
                    // Copying the same secret without metadata must not downgrade its protection.
                    val isSensitive = existing.isSensitive || sensitive
                    val updated = existing.copy(
                        timeStamp = timestamp,
                        isSensitive = isSensitive,
                        isPinned = existing.isPinned && !isSensitive
                    )
                    dao.updateFlags(updated)
                    current.map { if (it.id == updated.id) updated else it }
                } else {
                    val id = dao.insert(timestamp, false, text, sensitive)
                    current + ClipboardHistoryEntry(id, timestamp, false, text, sensitive)
                }
            }
        }

    /**
     * Marks a stored, so far normal entry with [text] as sensitive before anything is written,
     * so a failed transaction cannot leave it visible or pinnable. The entry keeps its own age:
     * sensitive retention decides whether it stays, and a sensitive copy that is itself already
     * expired never extends it.
     */
    private fun protectStored(text: String) {
        val existing = withProtection(entries).firstOrNull { it.text == text && !it.isSensitive } ?: return
        unsavedProtection[existing.id] = existing.copy(isSensitive = true, isPinned = false)
    }

    internal fun togglePinned(id: Long, retention: ClipboardRetention) =
        mutate(Operation.PIN, retention) { current, now ->
            val entry = current.firstOrNull { it.id == id }
            if (entry == null || entry.isSensitive) return@mutate current
            val updated = entry.copy(isPinned = !entry.isPinned, timeStamp = now)
            dao.updateFlags(updated)
            current.map { if (it.id == id) updated else it }
        }

    /** Deletes by stable id; a pinned entry only with [force]. */
    internal fun delete(id: Long, force: Boolean, retention: ClipboardRetention) =
        mutate(Operation.DELETE, retention) { current, _ ->
            val entry = current.firstOrNull { it.id == id }
            if (entry == null || (entry.isPinned && !force)) return@mutate current
            dao.delete(id)
            current.filterNot { it.id == id }
        }

    internal fun clearNonPinned(retention: ClipboardRetention) =
        mutate(Operation.CLEAR, retention) { current, _ ->
            if (current.none { !it.isPinned }) return@mutate current
            dao.deleteNonPinned()
            current.filter { it.isPinned }
        }

    /**
     * Removes expired entries and stores pending protection. [republish] publishes even
     * without a change, so the expiry timer that triggered this is always scheduled again.
     */
    internal fun purgeExpired(retention: ClipboardRetention, republish: Boolean = false) =
        mutate(Operation.CLEANUP, retention, publishUnchanged = republish) { current, _ -> current }

    /** Runs [change] on the worker; a request that reaches a history that is not loaded is dropped. */
    private fun mutate(
        operation: Operation,
        retention: ClipboardRetention,
        publishUnchanged: Boolean = false,
        change: (List<ClipboardHistoryEntry>, Long) -> List<ClipboardHistoryEntry>
    ) = worker.execute {
        if (isReady()) applyChange(operation, retention, publishUnchanged, change)
    }

    private fun isReady(): Boolean {
        if (loadState == LoadState.READY) return true
        Log.w(TAG, "Clipboard history unavailable; request dropped")
        return false
    }

    /**
     * Applies [change] to the protected, purged history inside one transaction. The stored
     * state changes only after the commit. A failure keeps the previous state apart from
     * [unsavedProtection], publishes it with a postponed cleanup time and reports [operation];
     * the failed change itself is never repeated.
     */
    private fun applyChange(
        operation: Operation,
        retention: ClipboardRetention,
        publishUnchanged: Boolean = false,
        change: (List<ClipboardHistoryEntry>, Long) -> List<ClipboardHistoryEntry>
    ) {
        val previousRetention = this.retention
        val previous = withProtection(entries)
        val updated = try {
            dao.transaction {
                // Stored first, so expiry below deletes protected rows by their sensitive flags.
                unsavedProtection.values.forEach(dao::updateFlags)
                val now = clock()
                val expired = previous.filter { retention.isExpired(it, now) }
                if (expired.isNotEmpty()) {
                    dao.deleteExpired(retention.normalCutoff(now), retention.sensitiveCutoff(now))
                }
                change(previous - expired.toSet(), now)
            }
        } catch (e: Exception) {
            logFailure("Clipboard ${operation.name.lowercase()} failed", e)
            failedWrites++
            retryAt = clock() + retryDelay(failedWrites)
            // The next cleanup follows the requested retention even though this one failed.
            this.retention = retention
            publish()
            publisher.execute { failureListeners.forEach { it(operation) } }
            return
        }
        val recovered = failedWrites > 0
        unsavedProtection.clear()
        failedWrites = 0
        retryAt = Long.MIN_VALUE
        this.retention = retention
        val sorted = updated.sorted()
        val changed = sorted != entries
        entries = sorted
        if (publishUnchanged || recovered || changed || retention != previousRetention) publish()
    }

    private fun withProtection(list: List<ClipboardHistoryEntry>): List<ClipboardHistoryEntry> =
        if (unsavedProtection.isEmpty()) list else list.map { unsavedProtection[it.id] ?: it }

    private fun publish() {
        val retention = retention
        val visible = withProtection(entries).sorted()
        val expiry = if (retention == null) Long.MAX_VALUE
            else visible.minOfOrNull(retention::expiresAt) ?: Long.MAX_VALUE
        val next = if (expiry == Long.MAX_VALUE) expiry else maxOf(expiry, retryAt)
        val published = Snapshot(loadState, visible, next)
        publisher.execute {
            snapshot = published
            listeners.forEach { it(published) }
        }
    }

    // Exception messages can carry SQL or clipboard values; log only the type.
    private fun logFailure(message: String, e: Exception) {
        Log.w(TAG, "$message (${e.javaClass.simpleName})")
    }

    companion object {
        private const val TAG = "ClipboardHistory"
        private const val RETRY_DELAY_MS = 60_000L
        private const val MAX_RETRY_DELAY_MS = 60 * 60_000L

        /** Doubles from one minute up to one hour, so a broken database is not hammered. */
        private fun retryDelay(failures: Int): Long =
            (RETRY_DELAY_MS shl (failures - 1).coerceIn(0, 6)).coerceAtMost(MAX_RETRY_DELAY_MS)

        @Volatile
        private var instance: ClipboardHistory? = null

        fun getInstance(context: Context): ClipboardHistory {
            return instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }
        }

        private fun create(context: Context): ClipboardHistory {
            val mainHandler = Handler(Looper.getMainLooper())
            return ClipboardHistory(
                ClipboardDao(ClipboardDatabase.getInstance(context)),
                // One FIFO thread that ends when idle, so it does not linger in the IME process.
                ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
                    Thread(runnable, "ClipboardHistory").apply { isDaemon = true }
                }.apply { allowCoreThreadTimeOut(true) },
                Executor { mainHandler.post(it) }
            )
        }
    }
}
