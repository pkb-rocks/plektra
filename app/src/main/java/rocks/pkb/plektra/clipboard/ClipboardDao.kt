package rocks.pkb.plektra.clipboard

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * SQL access for clipboard history. Holds no history state; [ClipboardHistory] calls it
 * exclusively from its worker thread.
 *
 * @param textChunkBytes Largest text slice read through one cursor row. Keeps big clips
 *   below Android's CursorWindow limit without limiting how much text a clip may hold.
 */
internal class ClipboardDao(
    private val helper: SQLiteOpenHelper,
    private val textChunkBytes: Int = TEXT_CHUNK_BYTES
) {
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Reads every row with text, unsorted. Rows without text are skipped. */
    fun loadAll(): List<ClipboardHistoryEntry> {
        val db = db
        val charset = textCharset(db)
        val entries = ArrayList<ClipboardHistoryEntry>()
        val large = ArrayList<LargeRow>()
        // Text is read as bytes: length() and substr() on BLOBs count bytes and keep NUL characters.
        db.rawQuery(
            """
            SELECT $COLUMN_ID, $COLUMN_TIMESTAMP, $COLUMN_PINNED, $COLUMN_SENSITIVE,
                length(CAST($COLUMN_TEXT AS BLOB)),
                CASE WHEN length(CAST($COLUMN_TEXT AS BLOB)) <= $textChunkBytes
                    THEN CAST($COLUMN_TEXT AS BLOB) END
            FROM $TABLE WHERE $COLUMN_TEXT IS NOT NULL
            """.trimIndent(),
            null
        ).use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val timestamp = it.getLong(1)
                val pinned = it.getInt(2) != 0
                val sensitive = it.getInt(3) != 0
                if (it.isNull(5)) {
                    large += LargeRow(id, timestamp, pinned, sensitive, it.getLong(4))
                } else {
                    entries += ClipboardHistoryEntry(id, timestamp, pinned, String(it.getBlob(5), charset), sensitive)
                }
            }
        }
        large.forEach { row ->
            entries += ClipboardHistoryEntry(row.id, row.timestamp, row.pinned, readLargeText(db, row, charset), row.sensitive)
        }
        return entries
    }

    private fun readLargeText(db: SQLiteDatabase, row: LargeRow, charset: Charset): String {
        val bytes = ByteArrayOutputStream(row.byteLength.toInt())
        var offset = 1L // substr() is 1-based
        while (offset <= row.byteLength) {
            db.rawQuery(
                "SELECT substr(CAST($COLUMN_TEXT AS BLOB), $offset, $textChunkBytes) FROM $TABLE WHERE $COLUMN_ID = ${row.id}",
                null
            ).use {
                check(it.moveToFirst()) { "Clipboard row disappeared during load" }
                bytes.write(it.getBlob(0))
            }
            offset += textChunkBytes
        }
        // Decode once so multi-byte characters split across chunks stay intact.
        return String(bytes.toByteArray(), charset)
    }

    fun <T> transaction(block: () -> T): T {
        val db = db
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun insert(timestamp: Long, pinned: Boolean, text: String, sensitive: Boolean): Long {
        val cv = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_PINNED, pinned)
            put(COLUMN_TEXT, text)
            put(COLUMN_SENSITIVE, sensitive)
        }
        return db.insertOrThrow(TABLE, null, cv)
    }

    fun updateFlags(entry: ClipboardHistoryEntry) {
        val cv = ContentValues().apply {
            put(COLUMN_TIMESTAMP, entry.timeStamp)
            put(COLUMN_PINNED, entry.isPinned)
            put(COLUMN_SENSITIVE, entry.isSensitive)
        }
        db.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(entry.id.toString()))
    }

    fun delete(id: Long) {
        db.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun deleteNonPinned() {
        db.delete(TABLE, "$COLUMN_PINNED = 0", null)
    }

    fun deleteExpired(normalCutoff: Long, sensitiveCutoff: Long) {
        db.delete(
            TABLE,
            "($COLUMN_SENSITIVE != 0 AND $COLUMN_TIMESTAMP <= ?) OR " +
                "($COLUMN_SENSITIVE = 0 AND $COLUMN_PINNED = 0 AND $COLUMN_TIMESTAMP <= ?)",
            arrayOf(sensitiveCutoff.toString(), normalCutoff.toString())
        )
    }

    private fun textCharset(db: SQLiteDatabase): Charset {
        val encoding = db.rawQuery("PRAGMA encoding", null).use { if (it.moveToFirst()) it.getString(0) else null }
        return when (encoding?.uppercase()) {
            "UTF-16LE" -> Charsets.UTF_16LE
            "UTF-16BE" -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
    }

    private class LargeRow(val id: Long, val timestamp: Long, val pinned: Boolean, val sensitive: Boolean, val byteLength: Long)

    companion object {
        const val TABLE = "CLIPBOARD"
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT"
        private const val COLUMN_SENSITIVE = "SENSITIVE"

        // Well below the default 2 MB CursorWindow, also when several rows share a window.
        const val TEXT_CHUNK_BYTES = 256 * 1024

        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_SENSITIVE TINYINT NOT NULL DEFAULT 0
            )
        """
    }
}
