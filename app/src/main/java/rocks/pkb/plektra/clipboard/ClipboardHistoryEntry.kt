package rocks.pkb.plektra.clipboard

/**
 * Immutable clipboard history entry as published to the UI.
 * @param id SQLite row id; assigned before the entry is ever published
 * @param timeStamp When the entry was created/last updated (System.currentTimeMillis())
 * @param isPinned Whether the entry is pinned (won't be auto-deleted)
 * @param text The clipboard text content; the plain-text form of a formatted clip and empty for images
 * @param isSensitive Sensitive entries are masked, expire separately and cannot be pinned
 * @param html Formatting copied with [text] as HTML, or null for plain text
 * @param image Metadata of a stored image; the pixels stay in the private image file
 */
data class ClipboardHistoryEntry(
    val id: Long,
    val timeStamp: Long,
    val isPinned: Boolean,
    val text: String,
    val isSensitive: Boolean = false,
    val html: String? = null,
    val image: ClipboardImage? = null
) : Comparable<ClipboardHistoryEntry> {

    val isImage: Boolean get() = image != null

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

/**
 * A stored clipboard image.
 * @param asset File name inside the private image directory, see [ClipboardImageStore]
 * @param mimeType Type detected from the file contents
 * @param digest SHA-256 of the file; identifies repeated copies of the same image
 */
data class ClipboardImage(
    val asset: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val digest: String
)
