package rocks.pkb.plektra.clipboard

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Data Access Object for clipboard history.
 *
 * The in-memory [cache] is the read model and is updated synchronously on the calling
 * thread (guarded by its own lock); every SQLite touch, including the initial load, runs
 * on [dbExecutor] so the clipboard listener never blocks the IME main thread.
 */
class ClipboardDao @VisibleForTesting internal constructor(
    private val db: ClipboardDatabase,
    private val beforeLoadMerge: (() -> Unit)? = null
) {

    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
        /** Called on the main thread once the stored history has been merged into the cache. */
        fun onHistoryLoaded() {}
    }

    @Volatile
    var listener: Listener? = null

    // Guarded by the cache lock: main-thread expiry sweeps share this state with the load.
    private var lastClearOldClips = 0L
    private var nextExpiry = 0L
    private var lastRetention: Pair<Long, Long>? = null

    private val cache = mutableListOf<ClipboardHistoryEntry>()

    // Whether the stored history has been merged into the cache, and what a clear that ran
    // before that point still owes the rows the load brings in.
    private enum class ClearedBeforeLoad { NONE, NON_PINNED, ALL }
    private var loaded = false
    private var loadQueued = false
    private val deletedBeforeLoad = mutableSetOf<String>()
    private val pinnedAtClear = mutableSetOf<Long>()
    private var clearedBeforeLoad = ClearedBeforeLoad.NONE

    init {
        requestLoad()
    }

    private fun requestLoad() {
        synchronized(cache) {
            if (loaded || loadQueued) return
            loadQueued = true
        }
        dbExecutor.execute {
            try {
                loadFromDb()
            } finally {
                // A failed load can be retried on the next read or copy.
                synchronized(cache) { loadQueued = false }
            }
        }
    }

    private fun loadFromDb() {
        val stored = mutableListOf<ClipboardHistoryEntry>()
        val superseded = mutableSetOf<Long>()
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT, COLUMN_SENSITIVE),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED DESC, $COLUMN_TIMESTAMP DESC"
        ).use {
            while (it.moveToNext()) {
                if (it.isNull(3)) {
                    superseded.add(it.getLong(0))
                    continue
                }
                stored.add(
                    ClipboardHistoryEntry(
                        it.getLong(0),
                        it.getLong(1),
                        it.getInt(2) != 0,
                        it.getString(3),
                        it.getInt(4) != 0
                    )
                )
            }
        }
        beforeLoadMerge?.invoke()
        val merged = mutableMapOf<Long, ClipboardHistoryEntry>()
        var changed = false
        val addedNow = mutableSetOf<Long>()
        synchronized(cache) {
            for (entry in stored) {
                val current = cache.firstOrNull { it.text == entry.text }
                // A retry can see a row already inserted or merged by this DAO.
                if (current?.rowId == entry.id) {
                    merged[current.rowId] = current
                    continue
                }
                if (entry.text in deletedBeforeLoad || clearedBeforeLoad == ClearedBeforeLoad.ALL) {
                    superseded.add(entry.id)
                    continue
                }
                if (current != null) {
                    // Merge protection before applying a pre-load clear, even for skipped rows.
                    val before = Triple(current.isSensitive, current.isPinned, current.timeStamp)
                    current.isSensitive = current.isSensitive || entry.isSensitive
                    current.isPinned = (current.isPinned || entry.isPinned) && !current.isSensitive
                    current.timeStamp = maxOf(current.timeStamp, entry.timeStamp)
                    if (current.rowId !in addedNow &&
                        before != Triple(current.isSensitive, current.isPinned, current.timeStamp)
                    ) {
                        changed = true
                    }
                    superseded.add(entry.id)
                    if (current.rowId != PENDING_ID) merged[current.rowId] = current
                } else if (clearedBeforeLoad == ClearedBeforeLoad.NON_PINNED && !entry.isPinned) {
                    superseded.add(entry.id)
                } else {
                    cache.add(entry)
                    addedNow.add(entry.id)
                    if (clearedBeforeLoad == ClearedBeforeLoad.NON_PINNED) pinnedAtClear.add(entry.id)
                }
            }
            // Clear all kept these entries only because their stored sensitivity was unknown.
            cache.removeAll { entry ->
                (entry.id in pinnedAtClear && !entry.isPinned).also { remove ->
                    if (remove && entry.rowId !in addedNow) changed = true
                    if (remove && entry.rowId != PENDING_ID) superseded.add(entry.rowId)
                }
            }
            cache.sort()
        }
        try {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                merged.values.forEach { writeState(it) }
                superseded.forEach { database.delete(TABLE, "$COLUMN_ID = ?", arrayOf(it.toString())) }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (e: RuntimeException) {
            // The merged state is already in the cache (a flag may have just turned sensitive),
            // so open views must re-read it even though the write failed. Only a change to an
            // entry that was already visible counts: rows this attempt re-adds (a sweep may have
            // dropped them since the last one) stay silent, so a persistent failure cannot loop
            // through the views.
            if (changed) mainHandler.post { listener?.onHistoryLoaded() }
            throw e
        }
        synchronized(cache) {
            loaded = true
            deletedBeforeLoad.clear()
            pinnedAtClear.clear()
            clearedBeforeLoad = ClearedBeforeLoad.NONE
            // Expiry bookkeeping computed while the cache was incomplete is void.
            nextExpiry = 0L
            lastRetention = null
            lastClearOldClips = 0L
        }
        mainHandler.post { listener?.onHistoryLoaded() }
    }

    /**
     * Add a new clipboard entry or update existing one with same text.
     * @param retentionMinutes Normal retention in minutes; zero keeps entries until deleted.
     * @param sensitiveRetentionMinutes Sensitive entries expire independently and cannot be pinned.
     */
    fun addClip(
        timestamp: Long,
        pinned: Boolean,
        text: String,
        retentionMinutes: Long = 0,
        sensitive: Boolean = false,
        sensitiveRetentionMinutes: Long = 30
    ) {
        clearOldClips(
            now = true,
            retentionMinutes = retentionMinutes,
            sensitiveRetentionMinutes = sensitiveRetentionMinutes
        )
        if (sensitive && timestamp <= System.currentTimeMillis() - sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L) {
            return
        }

        val existing = synchronized(cache) {
            nextExpiry = 0L
            cache.firstOrNull { it.text == text }
        }
        if (existing != null) {
            val (oldPos, newPos) = synchronized(cache) {
                val oldPos = cache.indexOfFirst { it === existing }
                existing.timeStamp = timestamp
                existing.isSensitive = existing.isSensitive || sensitive
                if (existing.isSensitive) existing.isPinned = false
                cache.sort()
                oldPos to cache.indexOfFirst { it === existing }
            }
            listener?.onClipMoved(oldPos, newPos)
            persistState(existing)
            return
        }

        insertNewEntry(timestamp, pinned, text, sensitive)
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String, sensitive: Boolean) {
        val effectivePinned = pinned && !sensitive
        val entry = ClipboardHistoryEntry(nextPendingId.getAndDecrement(), timestamp, effectivePinned, text, sensitive)
        val position = synchronized(cache) {
            cache.add(entry)
            cache.sort()
            cache.indexOfFirst { it === entry }
        }
        listener?.onClipInserted(position)

        dbExecutor.execute {
            // The load may have merged stronger flags from a stored duplicate into the entry
            // since it was queued, so write its current state.
            val cv = synchronized(cache) {
                ContentValues().apply {
                    put(COLUMN_TIMESTAMP, entry.timeStamp)
                    put(COLUMN_PINNED, entry.isPinned)
                    put(COLUMN_TEXT, entry.text)
                    put(COLUMN_SENSITIVE, entry.isSensitive)
                }
            }
            try {
                entry.rowId = db.writableDatabase.insertOrThrow(TABLE, null, cv)
            } catch (e: Exception) {
                // SQLite's insert() logs ContentValues, including the clipboard text.
                Log.e(TAG, "Clipboard insert failed")
            }
        }
    }

    fun isPinned(index: Int) = synchronized(cache) { cache.getOrNull(index)?.isPinned ?: false }

    fun getAt(index: Int) = synchronized(cache) { cache.getOrNull(index) }

    fun get(id: Long) = synchronized(cache) { cache.firstOrNull { it.id == id } }

    fun count() = synchronized(cache) { cache.size }

    fun sort() = synchronized(cache) { cache.sort() }

    fun togglePinned(id: Long) {
        val entry: ClipboardHistoryEntry
        val oldPos: Int
        val newPos: Int
        synchronized(cache) {
            entry = cache.firstOrNull { it.id == id } ?: return
            if (entry.isSensitive) return
            nextExpiry = 0L
            entry.isPinned = !entry.isPinned
            entry.timeStamp = System.currentTimeMillis()
            oldPos = cache.indexOfFirst { it === entry }
            cache.sort()
            newPos = cache.indexOfFirst { it === entry }
        }
        listener?.onClipMoved(oldPos, newPos)
        persistState(entry)
    }

    /** Queues a write of the entry's state as it is when the task runs, not when it was queued. */
    private fun persistState(entry: ClipboardHistoryEntry) {
        dbExecutor.execute { writeState(entry) }
    }

    private fun writeState(entry: ClipboardHistoryEntry) {
        val rowId = entry.rowId
        if (rowId == PENDING_ID) return
        val cv = synchronized(cache) {
            ContentValues(3).apply {
                put(COLUMN_TIMESTAMP, entry.timeStamp)
                put(COLUMN_SENSITIVE, entry.isSensitive)
                put(COLUMN_PINNED, entry.isPinned)
            }
        }
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(rowId.toString()))
    }

    /** Deletes by entry id, which stays valid when a background merge shifts positions. */
    fun deleteClip(id: Long) {
        val removedIndex: Int
        val entry = synchronized(cache) {
            removedIndex = cache.indexOfFirst { it.id == id }
            if (removedIndex < 0) return
            cache.removeAt(removedIndex).also {
                if (!loaded) deletedBeforeLoad.add(it.text)
            }
        }
        listener?.onClipsRemoved(removedIndex, 1)
        dbExecutor.execute {
            val rowId = entry.rowId
            if (rowId == PENDING_ID) return@execute
            db.writableDatabase.delete(TABLE, "$COLUMN_ID = $rowId", null)
        }
    }

    /**
     * Remove old clipboard entries based on retention time setting.
     * @param now If true, force clear immediately; otherwise respect debounce
     * @param retentionMinutes Normal retention in minutes; zero keeps normal entries indefinitely.
     * @param sensitiveRetentionMinutes Sensitive retention, clamped to 1–120 minutes.
     */
    fun clearOldClips(
        now: Boolean = false,
        retentionMinutes: Long = 0,
        sensitiveRetentionMinutes: Long = 30
    ) {
        requestLoad()
        val removed: List<Pair<Int, ClipboardHistoryEntry>>
        val normalCutoff: Long
        val sensitiveCutoff: Long
        synchronized(cache) {
            if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5_000) return
            lastClearOldClips = SystemClock.elapsedRealtime()
            val currentTime = System.currentTimeMillis()
            val retention = retentionMinutes to sensitiveRetentionMinutes
            if (lastRetention == retention && currentTime < nextExpiry) return
            lastRetention = retention
            normalCutoff = if (retentionMinutes > 0) currentTime - retentionMinutes * 60_000L else Long.MIN_VALUE
            sensitiveCutoff = currentTime - sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L

            val expired = cache.indices.filter { index ->
                val clip = cache[index]
                if (clip.isSensitive) clip.timeStamp <= sensitiveCutoff
                else !clip.isPinned && clip.timeStamp <= normalCutoff
            }
            removed = expired.asReversed().map { index ->
                index to cache.removeAt(index)
            }
            // Until the stored history is merged in, the cache is incomplete and says nothing
            // about when the next entry expires; the load resets this.
            nextExpiry = if (!loaded) 0L else cache.minOfOrNull { clip ->
                when {
                    clip.isSensitive -> clip.timeStamp + sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L
                    !clip.isPinned && retentionMinutes > 0 -> clip.timeStamp + retentionMinutes * 60_000L
                    else -> Long.MAX_VALUE
                }
            } ?: Long.MAX_VALUE
        }
        removed.forEach { (index, _) -> listener?.onClipsRemoved(index, 1) }
        dbExecutor.execute {
            // Purge even when a failed load left the cache empty or incomplete.
            db.writableDatabase.delete(
                TABLE,
                "($COLUMN_SENSITIVE = 1 AND $COLUMN_TIMESTAMP <= ?) OR " +
                    "($COLUMN_SENSITIVE = 0 AND $COLUMN_PINNED = 0 AND $COLUMN_TIMESTAMP <= ?)",
                arrayOf(sensitiveCutoff.toString(), normalCutoff.toString())
            )
            removed.forEach { (_, entry) ->
                val rowId = entry.rowId
                if (rowId != PENDING_ID) {
                    db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(rowId.toString()))
                }
            }
        }
    }

    fun clearNonPinned() {
        var firstRemoved = -1
        var removedCount = 0
        synchronized(cache) {
            if (!loaded) {
                if (clearedBeforeLoad == ClearedBeforeLoad.NONE) {
                    clearedBeforeLoad = ClearedBeforeLoad.NON_PINNED
                }
                pinnedAtClear.addAll(cache.filter { it.isPinned }.map { it.id })
            }
            firstRemoved = cache.indexOfFirst { !it.isPinned }
            if (firstRemoved >= 0) {
                removedCount = cache.count { !it.isPinned }
                cache.removeAll { !it.isPinned }
            }
        }
        if (firstRemoved >= 0) listener?.onClipsRemoved(firstRemoved, removedCount)
        // Unconditional: rows of the stored history may not be in the cache yet.
        dbExecutor.execute {
            db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
        }
    }

    fun clear() {
        val count = synchronized(cache) {
            if (!loaded) clearedBeforeLoad = ClearedBeforeLoad.ALL
            cache.size.also { cache.clear() }
        }
        if (count > 0) listener?.onClipsRemoved(0, count)
        // Unconditional: rows of the stored history may not be in the cache yet.
        dbExecutor.execute {
            db.writableDatabase.delete(TABLE, null, null)
        }
    }

    companion object {
        private const val TAG = "ClipboardDao"
        const val PENDING_ID = -1L

        // Handles for entries not yet stored. Each is unique, so a lookup by id can never
        // reach a different pending entry (paste or pin the wrong, possibly sensitive, clip).
        private val nextPendingId = AtomicLong(-2L)

        @VisibleForTesting
        internal val dbWorker = Executors.newSingleThreadExecutor()

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Blocks until every database task queued before this call has run. For tests. */
        @VisibleForTesting
        internal fun awaitDatabaseIdle() {
            dbWorker.submit {}.get()
        }

        // An exception escaping the worker thread would kill the IME process, and the
        // database is first opened here rather than inside getInstance's try/catch.
        private val dbExecutor = Executor { task ->
            dbWorker.execute {
                try {
                    task.run()
                } catch (e: Exception) {
                    Log.e(TAG, "Clipboard database operation failed", e)
                }
            }
        }

        const val TABLE = "CLIPBOARD"
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT"
        private const val COLUMN_SENSITIVE = "SENSITIVE"

        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_SENSITIVE TINYINT NOT NULL DEFAULT 0
            )
        """

        @Volatile
        private var instance: ClipboardDao? = null

        /**
         * Get the singleton instance, or create it if needed.
         * Returns null if instance can't be created (e.g. device locked).
         */
        fun getInstance(context: Context): ClipboardDao? {
            return instance ?: synchronized(this) {
                instance ?: try {
                    ClipboardDao(ClipboardDatabase.getInstance(context)).also {
                        instance = it
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to create ClipboardDao", e)
                    null
                }
            }
        }
    }
}
