package rocks.pkb.plektra.clipboard

/**
 * Represents a single clipboard history entry.
 * @param id Stable handle for this entry: the row id for stored entries, or a unique negative
 *   id from [ClipboardDao] for a new one (it never changes, so a handle held by the UI stays valid)
 * @param timeStamp When the entry was created/last updated (System.currentTimeMillis())
 * @param isPinned Whether the entry is pinned (won't be auto-deleted)
 * @param text The clipboard text content
 */
data class ClipboardHistoryEntry(
    val id: Long,
    var timeStamp: Long,
    var isPinned: Boolean,
    val text: String,
    var isSensitive: Boolean = false
) : Comparable<ClipboardHistoryEntry> {

    /**
     * SQLite row id, [ClipboardDao.PENDING_ID] until the background insert completes. Only read
     * it on the DAO's database thread, which runs the insert before any later update or delete.
     */
    @Volatile
    var rowId: Long = if (id >= 0) id else ClipboardDao.PENDING_ID

    /**
     * Comparator for sorting clipboard entries:
     * - Pinned items come first (or last depending on settings)
     * - Within same pinned state, sort by timestamp (most recent first)
     */
    override fun compareTo(other: ClipboardHistoryEntry): Int {
        val result = other.isPinned.compareTo(isPinned)
        if (result == 0) return other.timeStamp.compareTo(timeStamp)
        // TODO: Add setting for pinnedFirst vs pinnedLast
        // For now, pinned items always come first
        return result
    }
}
