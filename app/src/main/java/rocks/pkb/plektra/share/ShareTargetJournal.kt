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
 * Only targets whose incompleteness can be proven later are recorded:
 * - a MediaStore row, which is deleted only while MediaStore still reports it as pending;
 * - a SAF document created under a unique temporary name and renamed only when complete, which
 *   is deleted only while it still carries exactly that temporary name.
 * A completed or replaced target therefore never matches, even if the process died between
 * finishing it and removing its record. Providers without rename support write the final
 * document directly and are not recorded: after a crash their file stays in place.
 *
 * A `finally` block cannot run when the process dies, and a delete can fail transiently;
 * [recover] handles both on the next share or cleanup run. Records live in `noBackupFilesDir`,
 * outside every backup. Targets of the running process are tracked in memory and never touched.
 */
internal object ShareTargetJournal {
    private const val TAG = "ShareTargetJournal"
    private const val DIRECTORY = "share-pending"
    private const val TEMPORARY_SUFFIX = ".tmp"

    /** Records that still cannot be resolved after this long are dropped. */
    const val MAX_RECORD_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

    enum class Kind { MediaStore, Document }

    /** [temporaryName] identifies a [Kind.Document] target; it is null for MediaStore rows. */
    data class Entry(
        val id: String,
        val kind: Kind,
        val target: Uri,
        val temporaryName: String?,
        val createdAt: Long
    )

    private enum class Outcome { Resolved, Retry }

    /** Guards the record files, the registry of running targets and records under recovery. */
    private val lock = Any()
    private val active = mutableSetOf<String>()
    private val recovering = mutableSetOf<String>()

    fun root(context: Context): File = File(context.noBackupFilesDir, DIRECTORY)

    fun begin(
        context: Context,
        kind: Kind,
        target: Uri,
        temporaryName: String? = null,
        now: Long = System.currentTimeMillis()
    ): Entry {
        require((kind == Kind.Document) == (temporaryName != null)) { "Documents need their temporary name" }
        val entry = Entry(UUID.randomUUID().toString(), kind, target, temporaryName, now)
        synchronized(lock) {
            val directory = root(context)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
            val temporary = File(directory, entry.id + TEMPORARY_SUFFIX)
            temporary.writeText(listOf(kind.name, target.toString(), now.toString(), temporaryName.orEmpty()).joinToString("\n"))
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
        val outcome = resolve(context, entry, System.currentTimeMillis())
        synchronized(lock) {
            if (outcome == Outcome.Resolved) File(root(context), entry.id).delete()
            active -= entry.id
        }
    }

    /** Removes incomplete targets that an earlier process left behind. */
    fun recover(context: Context, now: Long = System.currentTimeMillis()) {
        val claimed = synchronized(lock) {
            val files = root(context).listFiles().orEmpty()
            // Record files are written and renamed under this lock, so any .tmp is an orphan.
            files.filter { it.name.endsWith(TEMPORARY_SUFFIX) }.forEach { it.delete() }
            files.filter { !it.name.endsWith(TEMPORARY_SUFFIX) && it.name !in active && it.name !in recovering }
                .onEach { recovering += it.name }
        }
        for (file in claimed) {
            try {
                // Provider calls happen outside the lock so a new save is never held up.
                val entry = read(file)
                val outcome = if (entry == null) Outcome.Resolved else resolve(context, entry, now)
                if (outcome == Outcome.Resolved) synchronized(lock) { file.delete() }
            } finally {
                synchronized(lock) { recovering -= file.name }
            }
        }
    }

    private fun read(file: File): Entry? = runCatching {
        val lines = file.readLines()
        val kind = Kind.valueOf(lines[0])
        Entry(file.name, kind, Uri.parse(lines[1]), lines.getOrNull(3)?.takeIf { it.isNotEmpty() }, lines[2].toLong())
    }.getOrNull()

    private fun resolve(context: Context, entry: Entry, now: Long): Outcome {
        val outcome = when (entry.kind) {
            Kind.MediaStore -> resolveMediaStore(context, entry.target)
            Kind.Document -> resolveDocument(context, entry.target, entry.temporaryName)
        }
        return if (outcome == Outcome.Retry && now - entry.createdAt >= MAX_RECORD_AGE_MILLIS) {
            Outcome.Resolved
        } else {
            outcome
        }
    }

    /** Deletes the row only on proof that it is still pending. */
    private fun resolveMediaStore(context: Context, target: Uri): Outcome = try {
        val query = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        val pending = context.contentResolver
            .query(target, arrayOf(MediaStore.MediaColumns.IS_PENDING), query, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else false }
        when (pending) {
            null -> Outcome.Retry // MediaStore did not answer.
            false -> Outcome.Resolved // Gone or published.
            true -> if (context.contentResolver.delete(target, null, null) > 0) Outcome.Resolved else Outcome.Retry
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "MediaStore cleanup deferred (${e.javaClass.simpleName})")
        Outcome.Retry
    }

    /** Deletes the document only while it still carries its unique temporary name. */
    private fun resolveDocument(context: Context, target: Uri, temporaryName: String?): Outcome {
        val authority = target.authority ?: return Outcome.Resolved
        val client = context.contentResolver.acquireUnstableContentProviderClient(authority)
            ?: return Outcome.Retry // Provider not reachable now, e.g. removable storage.
        return try {
            val name = try {
                client.query(target, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } catch (e: FileNotFoundException) {
                null
            } catch (e: IllegalArgumentException) {
                null // Document providers report a missing document this way too.
            }
            when {
                name == null -> Outcome.Resolved // Gone, for example renamed when complete.
                name != temporaryName -> Outcome.Resolved // Not the partial file anymore.
                else -> {
                    DocumentsContract.deleteDocument(context.contentResolver, target)
                    Outcome.Resolved
                }
            }
        } catch (e: FileNotFoundException) {
            Outcome.Resolved
        } catch (e: RuntimeException) {
            // Includes a temporarily revoked grant; the record is retried until it ages out.
            Log.w(TAG, "Document cleanup deferred (${e.javaClass.simpleName})")
            Outcome.Retry
        } finally {
            client.close()
        }
    }

    /** Only for tests: makes recorded targets look as if their process had ended. */
    internal fun forgetActiveForTest() {
        synchronized(lock) { active.clear() }
    }
}
