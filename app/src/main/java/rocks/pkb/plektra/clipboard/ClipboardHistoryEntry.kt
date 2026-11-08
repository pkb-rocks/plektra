package rocks.pkb.plektra.clipboard

/**
 * Immutable clipboard history entry as published to the UI.
 * @param id SQLite row id; assigned before the entry is ever published
 * @param timeStamp When the entry was created/last updated (System.currentTimeMillis())
 * @param isPinned Whether the entry is pinned (won't be auto-deleted)
 * @param text The clipboard text content
 * @param isSensitive Sensitive entries are masked, expire separately and cannot be pinned
 */
data class ClipboardHistoryEntry(
    val id: Long,
    val timeStamp: Long,
    val isPinned: Boolean,
    val text: String,
    val isSensitive: Boolean = false
) : Comparable<ClipboardHistoryEntry> {

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
