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
        val rows = ArrayList<Row>()
        // Text is read as bytes: length() and substr() on BLOBs count bytes and keep NUL characters.
        db.rawQuery(
            """
            SELECT $COLUMN_ID, $COLUMN_TIMESTAMP, $COLUMN_PINNED, $COLUMN_SENSITIVE,
                length(CAST($COLUMN_TEXT AS BLOB)),
                CASE WHEN length(CAST($COLUMN_TEXT AS BLOB)) <= $textChunkBytes
                    THEN CAST($COLUMN_TEXT AS BLOB) END,
                length(CAST($COLUMN_HTML AS BLOB)),
                CASE WHEN length(CAST($COLUMN_HTML AS BLOB)) <= $textChunkBytes
                    THEN CAST($COLUMN_HTML AS BLOB) END,
                $COLUMN_IMAGE_ASSET, $COLUMN_IMAGE_MIME, $COLUMN_IMAGE_BYTES,
                $COLUMN_IMAGE_WIDTH, $COLUMN_IMAGE_HEIGHT, $COLUMN_IMAGE_DIGEST
            FROM $TABLE WHERE $COLUMN_TEXT IS NOT NULL
            """.trimIndent(),
            null
        ).use {
            while (it.moveToNext()) {
                val image = if (it.isNull(8)) null else ClipboardImage(
                    asset = it.getString(8),
                    mimeType = it.getString(9),
                    byteSize = it.getLong(10),
                    width = it.getInt(11),
                    height = it.getInt(12),
                    digest = it.getString(13)
                )
                rows += Row(
                    id = it.getLong(0),
                    timestamp = it.getLong(1),
                    pinned = it.getInt(2) != 0,
                    sensitive = it.getInt(3) != 0,
                    textBytes = it.getLong(4),
                    text = if (it.isNull(5)) null else String(it.getBlob(5), charset),
                    htmlBytes = if (it.isNull(6)) null else it.getLong(6),
                    html = if (it.isNull(7)) null else String(it.getBlob(7), charset),
                    image = image
                )
            }
        }
        return rows.map { row ->
            ClipboardHistoryEntry(
                row.id,
                row.timestamp,
                row.pinned,
                row.text ?: readLarge(db, row.id, COLUMN_TEXT, row.textBytes, charset),
                row.sensitive,
                html = row.html ?: row.htmlBytes?.let { readLarge(db, row.id, COLUMN_HTML, it, charset) },
                image = row.image
            )
        }
    }

    private fun readLarge(db: SQLiteDatabase, id: Long, column: String, byteLength: Long, charset: Charset): String {
        val bytes = ByteArrayOutputStream(byteLength.toInt())
        var offset = 1L // substr() is 1-based
        while (offset <= byteLength) {
            db.rawQuery(
                "SELECT substr(CAST($column AS BLOB), $offset, $textChunkBytes) FROM $TABLE WHERE $COLUMN_ID = $id",
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

    fun insert(
        timestamp: Long,
        pinned: Boolean,
        text: String,
        sensitive: Boolean,
        html: String? = null,
        image: ClipboardImage? = null
    ): Long {
        val cv = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_PINNED, pinned)
            put(COLUMN_TEXT, text)
            put(COLUMN_SENSITIVE, sensitive)
            put(COLUMN_HTML, html)
            if (image != null) {
                put(COLUMN_IMAGE_ASSET, image.asset)
                put(COLUMN_IMAGE_MIME, image.mimeType)
                put(COLUMN_IMAGE_BYTES, image.byteSize)
                put(COLUMN_IMAGE_WIDTH, image.width)
                put(COLUMN_IMAGE_HEIGHT, image.height)
                put(COLUMN_IMAGE_DIGEST, image.digest)
            }
        }
        return db.insertOrThrow(TABLE, null, cv)
    }

    fun updateHtml(id: Long, html: String?) {
        val cv = ContentValues().apply { put(COLUMN_HTML, html) }
        db.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(id.toString()))
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

    private class Row(
        val id: Long,
        val timestamp: Long,
        val pinned: Boolean,
        val sensitive: Boolean,
        val textBytes: Long,
        val text: String?,
        val htmlBytes: Long?,
        val html: String?,
        val image: ClipboardImage?
    )

    companion object {
        const val TABLE = "CLIPBOARD"
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT"
        private const val COLUMN_SENSITIVE = "SENSITIVE"
        private const val COLUMN_HTML = "HTML"
        private const val COLUMN_IMAGE_ASSET = "IMAGE_ASSET"
        private const val COLUMN_IMAGE_MIME = "IMAGE_MIME"
        private const val COLUMN_IMAGE_BYTES = "IMAGE_BYTES"
        private const val COLUMN_IMAGE_WIDTH = "IMAGE_WIDTH"
        private const val COLUMN_IMAGE_HEIGHT = "IMAGE_HEIGHT"
        private const val COLUMN_IMAGE_DIGEST = "IMAGE_DIGEST"

        // Well below the default 2 MB CursorWindow, also when several rows share a window.
        const val TEXT_CHUNK_BYTES = 256 * 1024

        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_SENSITIVE TINYINT NOT NULL DEFAULT 0,
                $COLUMN_HTML TEXT,
                $COLUMN_IMAGE_ASSET TEXT,
                $COLUMN_IMAGE_MIME TEXT,
                $COLUMN_IMAGE_BYTES INTEGER,
                $COLUMN_IMAGE_WIDTH INTEGER,
                $COLUMN_IMAGE_HEIGHT INTEGER,
                $COLUMN_IMAGE_DIGEST TEXT
            )
        """

        /** Columns added in schema version 3: formatted text and image metadata. */
        val VERSION_3_COLUMNS = listOf(
            "$COLUMN_HTML TEXT",
            "$COLUMN_IMAGE_ASSET TEXT",
            "$COLUMN_IMAGE_MIME TEXT",
            "$COLUMN_IMAGE_BYTES INTEGER",
            "$COLUMN_IMAGE_WIDTH INTEGER",
            "$COLUMN_IMAGE_HEIGHT INTEGER",
            "$COLUMN_IMAGE_DIGEST TEXT"
        )
    }
}
