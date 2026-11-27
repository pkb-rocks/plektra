package rocks.pkb.plektra.share

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * Durable record of save targets that exist but are not complete yet.
 *
 * A target (a pending MediaStore row or a new SAF document) is recorded right after it is
 * created and before the first byte is written, and the record is removed once the target is
 * complete or deleted. A `finally` block cannot run when the process dies, and a delete can fail
 * transiently; [recover] then removes what is left on the next share or cleanup run.
 *
 * Records are small files in `noBackupFilesDir`, which neither Android backup nor Plektra's own
 * backup includes. Targets of the running process are tracked in memory and never recovered.
 * For a SAF document the record goes away immediately after its stream is closed; a process
 * that dies in that instant leaves a complete file that recovery would remove. MediaStore rows
 * have no such window because recovery only deletes rows that are still pending.
 */
internal object ShareTargetJournal {
    private const val TAG = "ShareTargetJournal"
    private const val DIRECTORY = "share-pending"

    /** Records that still cannot be resolved after this long are dropped. */
    const val MAX_RECORD_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

    enum class Kind { MediaStore, Document }

    data class Entry(val id: String, val kind: Kind, val target: Uri, val createdAt: Long)

    private val lock = Any()
    private val active = mutableSetOf<String>()

    fun root(context: Context): File = File(context.noBackupFilesDir, DIRECTORY)

    fun begin(context: Context, kind: Kind, target: Uri, now: Long = System.currentTimeMillis()): Entry {
        val entry = Entry(UUID.randomUUID().toString(), kind, target, now)
        val directory = root(context)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val temporary = File(directory, "${entry.id}.tmp")
        temporary.writeText("${kind.name}\n$target\n$now\n")
        synchronized(lock) {
            if (!temporary.renameTo(File(directory, entry.id))) {
                temporary.delete()
                throw IOException("Cannot record pending share target")
            }
            active += entry.id
        }
        return entry
    }

    /** The target is complete; it must survive any later recovery. */
    fun complete(context: Context, entry: Entry) {
        synchronized(lock) {
            File(root(context), entry.id).delete()
            active -= entry.id
        }
    }

    /** Deletes an incomplete target now; if that fails the record stays for [recover]. */
    fun discard(context: Context, entry: Entry) {
        val resolved = deleteTarget(context, entry.kind, entry.target)
        synchronized(lock) {
            if (resolved) File(root(context), entry.id).delete()
            active -= entry.id
        }
    }

    /** Removes incomplete targets that an earlier process left behind. */
    fun recover(context: Context, now: Long = System.currentTimeMillis()) {
        val files = synchronized(lock) {
            root(context).listFiles()?.filter { it.name !in active && !it.name.endsWith(".tmp") }.orEmpty()
        }
        root(context).listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        for (file in files) {
            val entry = read(file)
            val resolved = entry == null ||
                deleteTarget(context, entry.kind, entry.target) ||
                now - entry.createdAt >= MAX_RECORD_AGE_MILLIS
            if (resolved) {
                synchronized(lock) { if (file.name !in active) file.delete() }
            }
        }
    }

    private fun read(file: File): Entry? = runCatching {
        val lines = file.readLines()
        Entry(file.name, Kind.valueOf(lines[0]), Uri.parse(lines[1]), lines[2].toLong())
    }.getOrNull()

    /**
     * True when the incomplete target no longer needs attention: deleted now, already gone,
     * completed in the meantime (MediaStore), or out of Plektra's reach for good.
     */
    fun deleteTarget(context: Context, kind: Kind, target: Uri): Boolean = try {
        when (kind) {
            Kind.Document -> {
                DocumentsContract.deleteDocument(context.contentResolver, target)
                true
            }
            Kind.MediaStore -> when (mediaStorePending(context, target)) {
                null, false -> true
                true -> context.contentResolver.delete(target, null, null) > 0
            }
        }
    } catch (e: FileNotFoundException) {
        true
    } catch (e: SecurityException) {
        // The folder grant was revoked; Plektra can never remove this target.
        Log.w(TAG, "No longer allowed to remove an incomplete share target")
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Removing an incomplete share target failed (${e.javaClass.simpleName})")
        false
    }

    /** Null when the row is gone; pending rows are only visible with an explicit match flag. */
    private fun mediaStorePending(context: Context, target: Uri): Boolean? {
        val query = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return context.contentResolver.query(target, arrayOf(MediaStore.MediaColumns.IS_PENDING), query, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null }
    }

    /** Only for tests: makes recorded targets look as if their process had ended. */
    internal fun forgetActiveForTest() {
        synchronized(lock) { active.clear() }
    }
}
