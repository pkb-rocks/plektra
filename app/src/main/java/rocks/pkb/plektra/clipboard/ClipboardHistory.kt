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
 * [publisher] (the main thread in production). The one exception is protection: a clip
 * recognised as sensitive is hidden at once, even when storing that fact fails.
 *
 * Image files belong to the worker as well: it adopts staged files into [images], deletes
 * the files of removed rows after their deletion committed, and removes files without rows
 * and rows without files when it loads.
 */
class ClipboardHistory internal constructor(
    private val dao: ClipboardDao,
    private val worker: Executor,
    private val publisher: Executor,
    private val images: ClipboardImageStore,
    private val imageLimits: ClipboardImageLimits = ClipboardImageLimits.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class LoadState { LOADING, READY, ERROR }

    /** Change whose transaction failed; reported through [addFailureListener]. */
    enum class Operation { SAVE, PIN, DELETE, CLEAR, CLEANUP }

    /** A copy that was stored without a failure but not as requested; see [addNoticeListener]. */
    enum class Notice {
        /** Pinned images alone fill the image limits, so a new image was not stored. */
        IMAGE_LIMIT_REACHED
    }

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
    private val noticeListeners = CopyOnWriteArraySet<(Notice) -> Unit>()

    fun addListener(listener: (Snapshot) -> Unit) { listeners += listener }

    fun removeListener(listener: (Snapshot) -> Unit) { listeners -= listener }

    /**
     * Failed changes, delivered on the publisher thread after the snapshot that follows them.
     * Requests dropped because the history is not loaded are not reported; the snapshot's
     * [LoadState] already shows that.
     */
    fun addFailureListener(listener: (Operation) -> Unit) { failureListeners += listener }

    fun removeFailureListener(listener: (Operation) -> Unit) { failureListeners -= listener }

    /** Notices, delivered on the publisher thread. */
    fun addNoticeListener(listener: (Notice) -> Unit) { noticeListeners += listener }

    fun removeNoticeListener(listener: (Notice) -> Unit) { noticeListeners -= listener }

    /** The private file of a stored image; files of published entries never change. */
    internal fun imageFile(asset: String): java.io.File = images.file(asset)

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
            val loaded = dao.loadAll()
            // A row whose image file is gone cannot be pasted; a file without a row is never shown.
            val missing = loaded.filter { entry -> entry.image?.let { !images.exists(it.asset) } == true }
            if (missing.isNotEmpty()) dao.transaction { missing.forEach { dao.delete(it.id) } }
            val stored = loaded - missing.toSet()
            images.deleteUnreferenced(stored.mapNotNullTo(HashSet()) { it.image?.asset })
            entries = stored.filterNot { retention.isExpired(it, now) }.sorted()
            loadState = LoadState.READY
        } catch (e: Exception) {
            entries = emptyList()
            loadState = LoadState.ERROR
            logFailure("Clipboard history load failed", e)
        }
        publish()
    }

    /**
     * Adds a copied text, or refreshes the entry with the same text without weakening its
     * protection. The formatting of the latest copy replaces the stored one; null stores the
     * text as plain.
     */
    internal fun addClip(text: String, timestamp: Long, sensitive: Boolean, retention: ClipboardRetention, html: String? = null) =
        worker.execute {
            if (!isReady()) return@execute
            val matches: (ClipboardHistoryEntry) -> Boolean = { !it.isImage && it.text == text }
            if (sensitive) protectStored(matches)
            applyChange(Operation.SAVE, retention) { current, now ->
                if (sensitive && timestamp <= retention.sensitiveCutoff(now)) return@applyChange current
                val existing = current.firstOrNull(matches)
                if (existing != null) {
                    val updated = refreshed(existing, timestamp, sensitive).copy(html = html)
                    dao.updateFlags(updated)
                    if (existing.html != html) dao.updateHtml(updated.id, html)
                    current.map { if (it.id == updated.id) updated else it }
                } else {
                    val id = dao.insert(timestamp, false, text, sensitive, html)
                    current + ClipboardHistoryEntry(id, timestamp, false, text, sensitive, html)
                }
            }
        }

    /**
     * Stores a captured image, or refreshes the entry with the same image. The staged file is
     * adopted into [images] only for a new entry and is deleted in every other case. Making
     * room removes the oldest unpinned images; when pinned images alone fill the limits, the
     * image is not stored and [Notice.IMAGE_LIMIT_REACHED] is reported.
     */
    internal fun addImage(
        staged: ClipboardImageCapture.Staged,
        timestamp: Long,
        sensitive: Boolean,
        retention: ClipboardRetention
    ) = worker.execute {
        var adopted: String? = null
        var limitReached = false
        try {
            if (!isReady()) return@execute
            val matches: (ClipboardHistoryEntry) -> Boolean = { it.image?.digest == staged.digest }
            if (sensitive) protectStored(matches)
            val committed = applyChange(Operation.SAVE, retention) { current, now ->
                if (sensitive && timestamp <= retention.sensitiveCutoff(now)) return@applyChange current
                val existing = current.firstOrNull(matches)
                if (existing != null) {
                    val updated = refreshed(existing, timestamp, sensitive)
                    dao.updateFlags(updated)
                    return@applyChange current.map { if (it.id == updated.id) updated else it }
                }
                val evicted = imagesToEvict(current, staged.byteSize)
                if (evicted == null) {
                    limitReached = true
                    return@applyChange current
                }
                val asset = images.adopt(staged.file, staged.mimeType).also { adopted = it }
                val image = ClipboardImage(asset, staged.mimeType, staged.byteSize, staged.width, staged.height, staged.digest)
                evicted.forEach { dao.delete(it.id) }
                val id = dao.insert(timestamp, false, "", sensitive, image = image)
                current - evicted.toSet() + ClipboardHistoryEntry(id, timestamp, false, "", sensitive, image = image)
            }
            if (committed) adopted = null
            if (limitReached) publisher.execute { noticeListeners.forEach { it(Notice.IMAGE_LIMIT_REACHED) } }
        } finally {
            adopted?.let(images::delete)
            staged.file.delete()
        }
    }

    /**
     * Unpinned images, oldest first, whose removal makes room for another image of [byteSize];
     * null when not even removing all of them is enough.
     */
    private fun imagesToEvict(current: List<ClipboardHistoryEntry>, byteSize: Long): List<ClipboardHistoryEntry>? {
        val stored = current.filter { it.isImage }
        var count = stored.size + 1
        var bytes = stored.sumOf { it.image!!.byteSize } + byteSize
        val evicted = ArrayList<ClipboardHistoryEntry>()
        for (candidate in stored.filterNot { it.isPinned }.sortedBy { it.timeStamp }) {
            if (count <= imageLimits.maxImages && bytes <= imageLimits.maxTotalBytes) break
            evicted += candidate
            count--
            bytes -= candidate.image!!.byteSize
        }
        return if (count <= imageLimits.maxImages && bytes <= imageLimits.maxTotalBytes) evicted else null
    }

    /** A repeated copy: newer time, and the same secret copied without metadata stays protected. */
    private fun refreshed(existing: ClipboardHistoryEntry, timestamp: Long, sensitive: Boolean): ClipboardHistoryEntry {
        val isSensitive = existing.isSensitive || sensitive
        return existing.copy(
            timeStamp = timestamp,
            isSensitive = isSensitive,
            isPinned = existing.isPinned && !isSensitive
        )
    }

    /**
     * Marks a stored, so far normal entry that [matches] the copy as sensitive before anything
     * is written, so a failed transaction cannot leave it visible or pinnable. The entry keeps
     * its own age: sensitive retention decides whether it stays, and a sensitive copy that is
     * itself already expired never extends it.
     */
    private fun protectStored(matches: (ClipboardHistoryEntry) -> Boolean) {
        val existing = withProtection(entries).firstOrNull { matches(it) && !it.isSensitive } ?: return
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
     * the failed change itself is never repeated. Image files of removed rows are deleted
     * after the commit. Returns whether the transaction committed.
     */
    private fun applyChange(
        operation: Operation,
        retention: ClipboardRetention,
        publishUnchanged: Boolean = false,
        change: (List<ClipboardHistoryEntry>, Long) -> List<ClipboardHistoryEntry>
    ): Boolean {
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
            return false
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
        val kept = sorted.mapNotNullTo(HashSet()) { it.image?.asset }
        previous.forEach { entry -> entry.image?.asset?.takeIf { it !in kept }?.let(images::delete) }
        return true
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
                Executor { mainHandler.post(it) },
                ClipboardImageStore.create(context)
            )
        }
    }
}
