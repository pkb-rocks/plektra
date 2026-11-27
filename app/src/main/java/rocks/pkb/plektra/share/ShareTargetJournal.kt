package rocks.pkb.plektra.share

import android.content.ContentProviderClient
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

    // Values of the hidden DocumentsContract constants used by DocumentsContract.deleteDocument().
    private const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
    private const val EXTRA_URI = "uri"

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
            temporary.writeText(
                listOf(kind.name, target.toString(), now.toString(), temporaryName.orEmpty())
                    .joinToString("\n")
            )
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
        try {
            for (file in claimed) {
                // Provider calls happen outside the lock so a new save is never held up.
                val entry = read(file)
                val outcome = if (entry == null) Outcome.Resolved else resolve(context, entry, now)
                if (outcome == Outcome.Resolved) synchronized(lock) { file.delete() }
            }
        } finally {
            // Every claimed record is released, also when a provider call failed unexpectedly.
            synchronized(lock) { claimed.forEach { recovering -= it.name } }
        }
    }

    /** True while any record exists, including those of saves still running. */
    fun hasRecords(context: Context): Boolean = synchronized(lock) {
        root(context).listFiles()?.any { !it.name.endsWith(TEMPORARY_SUFFIX) } == true
    }

    private fun read(file: File): Entry? = runCatching {
        val lines = file.readLines()
        val kind = Kind.valueOf(lines[0])
        Entry(
            file.name,
            kind,
            Uri.parse(lines[1]),
            lines.getOrNull(3)?.takeIf { it.isNotEmpty() },
            lines[2].toLong()
        )
    }.getOrNull()

    private fun resolve(context: Context, entry: Entry, now: Long): Outcome {
        val outcome = try {
            when (entry.kind) {
                Kind.MediaStore -> resolveMediaStore(context, entry.target)
                Kind.Document -> resolveDocument(context, entry)
            }
        } catch (e: Exception) {
            // A dead provider process (RemoteException) or any other surprise: try again later.
            Log.w(TAG, "Cleanup deferred (${e.javaClass.simpleName})")
            Outcome.Retry
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
    private fun resolveDocument(context: Context, entry: Entry): Outcome {
        val target = entry.target
        val temporaryName = entry.temporaryName
        val authority = target.authority ?: return Outcome.Resolved
        val client = context.contentResolver.acquireUnstableContentProviderClient(authority)
            ?: return Outcome.Retry // Provider not reachable now, e.g. removable storage.
        return try {
            fun lookUp(uri: Uri): Lookup = try {
                client.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) Lookup.Found(it.getString(0)) else Lookup.Missing }
                    ?: Lookup.NoAnswer
            } catch (e: FileNotFoundException) {
                Lookup.Missing
            }
            // No cursor is no answer, even though DocumentsProvider also returns null for a
            // missing file: such a record stays until it ages out rather than risk a wrong guess.
            when (val answer = lookUp(target)) {
                Lookup.NoAnswer -> Outcome.Retry
                Lookup.Missing -> Outcome.Resolved // Gone, for example renamed when complete.
                is Lookup.Found -> when {
                    temporaryName == null || answer.name != temporaryName -> Outcome.Resolved // Not the partial file.
                    deleteConfirmed(client, target) -> Outcome.Resolved
                    else -> Outcome.Retry
                }
            }
        } finally {
            client.close()
        }
    }

    /**
     * Deletes through the provider directly: DocumentsContract.deleteDocument() also reports
     * success when the provider call returned nothing. DocumentsProvider answers a completed
     * delete with a (possibly empty) bundle.
     */
    private fun deleteConfirmed(client: ContentProviderClient, target: Uri): Boolean =
        client.call(METHOD_DELETE_DOCUMENT, null, Bundle().apply { putParcelable(EXTRA_URI, target) }) != null

    private sealed interface Lookup {
        data object NoAnswer : Lookup
        data object Missing : Lookup
        data class Found(val name: String?) : Lookup
    }

    /** Only for tests: makes recorded targets look as if their process had ended. */
    internal fun forgetActiveForTest() {
        synchronized(lock) { active.clear() }
    }
}
