package rocks.pkb.plektra.clipboard

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import org.robolectric.Shadows.shadowOf

/** Holds worker tasks until a test runs them, so tests choose every interleaving. */
internal class QueueExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()
    val pending: Int get() = tasks.size

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

/** A clipboard history on its own database file, with a queued worker and main-thread publication. */
internal class ClipboardTestStore(private val context: Context, private val name: String) {
    var now = System.currentTimeMillis()
    val worker = QueueExecutor()
    var database = ClipboardDatabase(context, name)
        private set
    var history = newHistory()
        private set

    private fun newHistory(chunkBytes: Int = ClipboardDao.TEXT_CHUNK_BYTES): ClipboardHistory {
        val mainHandler = Handler(Looper.getMainLooper())
        return ClipboardHistory(ClipboardDao(database, chunkBytes), worker, Executor { mainHandler.post(it) }) { now }
    }

    /** Simulates a process restart: a new database helper and history on the same file. */
    fun restart(chunkBytes: Int = ClipboardDao.TEXT_CHUNK_BYTES): ClipboardHistory {
        settle()
        database.close()
        database = ClipboardDatabase(context, name)
        history = newHistory(chunkBytes)
        return history
    }

    fun settle() {
        do {
            worker.runAll()
            shadowOf(Looper.getMainLooper()).idle()
        } while (worker.pending > 0)
    }

    fun store(text: String?, timestamp: Long, pinned: Boolean = false, sensitive: Boolean = false): Long =
        database.writableDatabase.insertOrThrow(ClipboardDao.TABLE, null, ContentValues().apply {
            put("TIMESTAMP", timestamp)
            put("PINNED", pinned)
            if (text == null) putNull("TEXT") else put("TEXT", text)
            put("SENSITIVE", sensitive)
        })

    data class Row(val id: Long, val text: String, val pinned: Boolean, val sensitive: Boolean)

    fun rows(): List<Row> = database.readableDatabase.rawQuery(
        "SELECT ID, TEXT, PINNED, SENSITIVE FROM CLIPBOARD WHERE TEXT IS NOT NULL ORDER BY ID", null
    ).use {
        buildList {
            while (it.moveToNext()) add(Row(it.getLong(0), it.getString(1), it.getInt(2) != 0, it.getInt(3) != 0))
        }
    }

    fun close() {
        database.close()
        context.deleteDatabase(name)
    }
}
