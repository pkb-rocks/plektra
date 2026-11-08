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
 * [publisher] (the main thread in production).
 */
class ClipboardHistory internal constructor(
    private val dao: ClipboardDao,
    private val worker: Executor,
    private val publisher: Executor,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class LoadState { LOADING, READY, ERROR }

    /**
     * Complete, immutable view of the history.
     * @param entries Sorted entries; empty unless [loadState] is [LoadState.READY]
     * @param nextExpiry Earliest wall-clock time an entry expires under the last applied retention
     */
    data class Snapshot(
        val loadState: LoadState,
        val entries: List<ClipboardHistoryEntry>,
        val nextExpiry: Long
    )

    // Worker-owned state.
    private var loadState = LoadState.LOADING
    private var entries: List<ClipboardHistoryEntry> = emptyList()
    private var retention: ClipboardRetention? = null

    /** Latest published snapshot; read on the publisher thread. */
    @Volatile
    var snapshot = Snapshot(LoadState.LOADING, emptyList(), Long.MAX_VALUE)
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    fun addListener(listener: (Snapshot) -> Unit) { listeners += listener }

    fun removeListener(listener: (Snapshot) -> Unit) { listeners -= listener }

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
        mutate("Clipboard insert failed", retention) { current, now ->
            if (sensitive && timestamp <= retention.sensitiveCutoff(now)) return@mutate current
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

    internal fun togglePinned(id: Long, retention: ClipboardRetention) =
        mutate("Clipboard pin failed", retention) { current, now ->
            val entry = current.firstOrNull { it.id == id }
            if (entry == null || entry.isSensitive) return@mutate current
            val updated = entry.copy(isPinned = !entry.isPinned, timeStamp = now)
            dao.updateFlags(updated)
            current.map { if (it.id == id) updated else it }
        }

    /** Deletes by stable id; a pinned entry only with [force]. */
    internal fun delete(id: Long, force: Boolean, retention: ClipboardRetention) =
        mutate("Clipboard delete failed", retention) { current, _ ->
            val entry = current.firstOrNull { it.id == id }
            if (entry == null || (entry.isPinned && !force)) return@mutate current
            dao.delete(id)
            current.filterNot { it.id == id }
        }

    internal fun clearNonPinned(retention: ClipboardRetention) =
        mutate("Clipboard clear failed", retention) { current, _ ->
            if (current.none { !it.isPinned }) return@mutate current
            dao.deleteNonPinned()
            current.filter { it.isPinned }
        }

    /**
     * Removes expired entries. [republish] publishes even without a change, so the
     * expiry timer that triggered this is always scheduled again.
     */
    internal fun purgeExpired(retention: ClipboardRetention, republish: Boolean = false) =
        mutate("Clipboard cleanup failed", retention, publishUnchanged = republish) { current, _ -> current }

    /**
     * Applies [change] to the purged history inside one transaction. State and snapshot
     * change only after the commit; a request that reaches a history that is not loaded
     * fails instead of being replayed later.
     */
    private fun mutate(
        failureMessage: String,
        retention: ClipboardRetention,
        publishUnchanged: Boolean = false,
        change: (List<ClipboardHistoryEntry>, Long) -> List<ClipboardHistoryEntry>
    ) = worker.execute {
        if (loadState != LoadState.READY) {
            Log.w(TAG, "Clipboard history unavailable; request dropped")
            return@execute
        }
        val previousRetention = this.retention
        val updated = try {
            dao.transaction {
                val now = clock()
                val expired = entries.filter { retention.isExpired(it, now) }
                if (expired.isNotEmpty()) {
                    dao.deleteExpired(retention.normalCutoff(now), retention.sensitiveCutoff(now))
                }
                change(entries - expired.toSet(), now)
            }
        } catch (e: Exception) {
            logFailure(failureMessage, e)
            return@execute
        }
        this.retention = retention
        if (!publishUnchanged && updated == entries && retention == previousRetention) return@execute
        entries = updated.sorted()
        publish()
    }

    private fun publish() {
        val retention = retention
        val next = if (retention == null) Long.MAX_VALUE
            else entries.minOfOrNull(retention::expiresAt) ?: Long.MAX_VALUE
        val published = Snapshot(loadState, entries, next)
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
