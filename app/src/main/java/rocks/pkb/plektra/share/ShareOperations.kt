package rocks.pkb.plektra.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import rocks.pkb.plektra.R

/** Why an item could not be handled; the UI maps each reason to a localized message. */
enum class ShareFailure {
    SourceUnreadable,
    FolderAccessRevoked,
    FolderUnavailable,
    NotAnImage,
    TooLargeForClipboard,
    ClipboardRejected,
    WriteFailed
}

sealed interface ShareItemResult {
    data class Saved(val name: String, val location: String) : ShareItemResult
    data object Copied : ShareItemResult
    data class Failed(val reason: ShareFailure) : ShareItemResult
}

private class ShareOperationException(val reason: ShareFailure, cause: Throwable? = null) :
    Exception(reason.name, cause)

internal object ShareOperations {
    private const val BUFFER_SIZE = 64 * 1024

    /** Binder transactions are limited to about 1 MB; larger clips fail in the clipboard service. */
    const val MAX_CLIPBOARD_TEXT_CHARS = 200_000

    fun textFileBaseName(context: Context, item: SharedItem.Text, now: Date = Date()): String =
        item.subject ?: context.getString(
            R.string.share_text_file_name,
            SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(now)
        )

    fun suggestedName(context: Context, item: SharedItem): String = when (item) {
        is SharedItem.Text -> ShareFileNames.sanitize(
            textFileBaseName(context, item),
            "text/plain",
            context.getString(R.string.share_file_fallback_name)
        ).let { if (ShareFileNames.extensionOf(it).equals("txt", ignoreCase = true)) it else "$it.txt" }
        is SharedItem.Stream -> ShareFileNames.sanitize(
            item.displayName,
            item.mimeType,
            context.getString(R.string.share_file_fallback_name)
        )
    }

    private fun mimeOf(item: SharedItem): String = when (item) {
        is SharedItem.Text -> "text/plain"
        is SharedItem.Stream -> item.mimeType
    }

    private fun openSource(resolver: ContentResolver, item: SharedItem): InputStream = when (item) {
        is SharedItem.Text -> item.text.toByteArray(Charsets.UTF_8).inputStream()
        is SharedItem.Stream -> try {
            resolver.openInputStream(item.uri)
        } catch (e: IOException) {
            throw ShareOperationException(ShareFailure.SourceUnreadable, e)
        } catch (e: RuntimeException) {
            // Revoked grants, vanished providers and provider bugs all mean the item is out of reach.
            throw ShareOperationException(ShareFailure.SourceUnreadable, e)
        } ?: throw ShareOperationException(ShareFailure.SourceUnreadable)
    }

    private suspend fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                throw ShareOperationException(ShareFailure.SourceUnreadable, e)
            } catch (e: RuntimeException) {
                throw ShareOperationException(ShareFailure.SourceUnreadable, e)
            }
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    /** Copies the stream from [openInput] to [target]; source and write failures stay distinguishable. */
    private suspend fun writeTo(resolver: ContentResolver, openInput: () -> InputStream, target: Uri) {
        openInput().use { input ->
            val output = try {
                resolver.openOutputStream(target, "w")
            } catch (e: FileNotFoundException) {
                throw ShareOperationException(ShareFailure.WriteFailed, e)
            } ?: throw ShareOperationException(ShareFailure.WriteFailed)
            output.use {
                try {
                    copyStream(input, it)
                } catch (e: IOException) {
                    throw ShareOperationException(ShareFailure.WriteFailed, e)
                }
            }
        }
    }

    private fun displayNameOf(resolver: ContentResolver, uri: Uri, fallback: String): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: fallback

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ShareOperationException) {
        throw e
    } catch (e: SecurityException) {
        throw ShareOperationException(ShareFailure.WriteFailed, e)
    } catch (e: IOException) {
        throw ShareOperationException(ShareFailure.WriteFailed, e)
    } catch (e: IllegalStateException) {
        throw ShareOperationException(ShareFailure.WriteFailed, e)
    } catch (e: IllegalArgumentException) {
        throw ShareOperationException(ShareFailure.WriteFailed, e)
    } catch (e: UnsupportedOperationException) {
        throw ShareOperationException(ShareFailure.WriteFailed, e)
    }

    /**
     * Records a freshly created target before any byte is written, so a target abandoned by a
     * killed process can still be removed later (see [ShareTargetJournal.recover]).
     */
    private fun journal(
        context: Context,
        kind: ShareTargetJournal.Kind,
        target: Uri,
        temporaryName: String? = null
    ): ShareTargetJournal.Entry = try {
        ShareTargetJournal.begin(context, kind, target, temporaryName)
    } catch (e: IOException) {
        // Not recorded, so remove it now: this process knows it is incomplete.
        runCatching {
            if (kind == ShareTargetJournal.Kind.Document) DocumentsContract.deleteDocument(context.contentResolver, target)
            else context.contentResolver.delete(target, null, null)
        }
        throw ShareOperationException(ShareFailure.WriteFailed, e)
    }

    /**
     * Writes a MediaStore row as pending and publishes it only after every byte arrived, so other
     * apps never see a partial file. Failed or cancelled rows are deleted, now or on recovery.
     */
    private suspend fun insertIntoMediaStore(
        context: Context,
        collection: Uri,
        relativePath: String,
        openInput: () -> InputStream,
        name: String,
        mimeType: String
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val row = guarded { resolver.insert(collection, values) }
            ?: throw ShareOperationException(ShareFailure.WriteFailed)
        val entry = journal(context, ShareTargetJournal.Kind.MediaStore, row)
        var published = false
        try {
            guarded { writeTo(resolver, openInput, row) }
            guarded {
                resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            published = true
            ShareTargetJournal.complete(context, entry)
            return displayNameOf(resolver, row, name)
        } finally {
            if (!published) ShareTargetJournal.discard(context, entry)
        }
    }

    suspend fun saveToDownloads(context: Context, item: SharedItem): ShareItemResult = runOperation {
        ShareCleanupWorker.scheduleRecovery(context)
        val name = suggestedName(context, item)
        val finalName = insertIntoMediaStore(
            context,
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            Environment.DIRECTORY_DOWNLOADS + "/",
            { openSource(context.contentResolver, item) },
            name,
            mimeOf(item)
        )
        ShareItemResult.Saved(finalName, Environment.DIRECTORY_DOWNLOADS)
    }

    suspend fun saveToFolder(context: Context, item: SharedItem, treeUri: Uri, folderLabel: String): ShareItemResult =
        runOperation {
            if (!ShareSettings.hasPersistedWriteAccess(context, treeUri)) {
                throw ShareOperationException(ShareFailure.FolderAccessRevoked)
            }
            ShareCleanupWorker.scheduleRecovery(context)
            val resolver = context.contentResolver
            val parent = try {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            } catch (e: IllegalArgumentException) {
                throw ShareOperationException(ShareFailure.FolderUnavailable, e)
            }
            val name = suggestedName(context, item)
            val saved = createInFolder(context, parent, mimeOf(item), name) { document ->
                guarded { writeTo(resolver, { openSource(resolver, item) }, document) }
            }
            ShareItemResult.Saved(displayNameOf(resolver, saved, name), folderLabel)
        }

    private fun createDocument(resolver: ContentResolver, parent: Uri, mimeType: String, name: String): Uri = try {
        DocumentsContract.createDocument(resolver, parent, mimeType, name)
    } catch (e: SecurityException) {
        throw ShareOperationException(ShareFailure.FolderAccessRevoked, e)
    } catch (e: FileNotFoundException) {
        throw ShareOperationException(ShareFailure.FolderUnavailable, e)
    } catch (e: IllegalArgumentException) {
        throw ShareOperationException(ShareFailure.FolderUnavailable, e)
    } catch (e: IllegalStateException) {
        throw ShareOperationException(ShareFailure.FolderUnavailable, e)
    } ?: throw ShareOperationException(ShareFailure.FolderUnavailable)

    private fun documentNameAndFlags(resolver: ContentResolver, document: Uri): Pair<String?, Int> = runCatching {
        resolver.query(
            document,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getInt(1) else null }
    }.getOrNull() ?: (null to 0)

    /**
     * Creates a complete document in [parent]. When the provider can rename, the bytes go to a
     * hidden document with a unique temporary name that is recorded in [ShareTargetJournal] and
     * renamed to [name] only once complete, so recovery can tell a partial file apart from any
     * final or replaced one. Without rename support the final document is written directly and
     * not recorded: an in-process failure still deletes it, a crash leaves it in place.
     */
    private suspend fun createInFolder(
        context: Context,
        parent: Uri,
        mimeType: String,
        name: String,
        write: suspend (Uri) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val token = UUID.randomUUID().toString()
        val temporary = createDocument(resolver, parent, mimeType, "$TEMPORARY_PREFIX$token")
        val (temporaryName, flags) = documentNameAndFlags(resolver, temporary)
        // Recovery may only ever delete by this name, so it must carry our random token.
        val identifiable = temporaryName != null && token in temporaryName
        if (!identifiable || flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0) {
            if (identifiable) {
                // Recorded before the delete attempt, so a failed delete is retried by recovery.
                ShareTargetJournal.discard(context, journal(context, ShareTargetJournal.Kind.Document, temporary, temporaryName))
            } else {
                runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            }
            // Document providers resolve name collisions themselves, e.g. "name (1).ext".
            val document = createDocument(resolver, parent, mimeType, name)
            var complete = false
            try {
                write(document)
                complete = true
            } finally {
                if (!complete) runCatching { DocumentsContract.deleteDocument(resolver, document) }
            }
            return document
        }
        val entry = journal(context, ShareTargetJournal.Kind.Document, temporary, temporaryName)
        var complete = false
        try {
            write(temporary)
            // Android returns the (possibly unchanged) document URI on success and null on failure.
            val renamed = guarded { DocumentsContract.renameDocument(resolver, temporary, name) }
                ?: throw ShareOperationException(ShareFailure.WriteFailed)
            val (finalName, _) = documentNameAndFlags(resolver, renamed)
            if (finalName == null || token in finalName) throw ShareOperationException(ShareFailure.WriteFailed)
            complete = true
            ShareTargetJournal.complete(context, entry)
            return renamed
        } finally {
            if (!complete) ShareTargetJournal.discard(context, entry)
        }
    }

    private const val TEMPORARY_PREFIX = ".plektra-partial-"

    /** Largest edge of the bitmap decoded to prove a picture is readable; bounds its memory. */
    private const val VERIFY_MAX_EDGE = 2048

    /**
     * The format of [file] when Android can decode all of its pixels, otherwise null. A valid
     * header proves little, and BitmapFactory returns a partly grey bitmap for a truncated JPEG,
     * so ImageDecoder decodes it for real, subsampled to limit memory, and rejects partial images.
     */
    internal fun decodableImageType(file: File): String? {
        var mimeType: String? = null
        val bitmap = try {
            // A memory-mapped buffer: no copy on the heap, and the same bytes that get published.
            val buffer = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(buffer)) { decoder, info, _ ->
                mimeType = info.mimeType
                var sampleSize = 1
                while (maxOf(info.size.width, info.size.height) / sampleSize > VERIFY_MAX_EDGE) sampleSize *= 2
                decoder.setTargetSampleSize(sampleSize)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
        } catch (e: IOException) {
            return null
        }
        bitmap.recycle()
        return mimeType?.let(ShareMimeTypes::normalize)?.takeIf { it.startsWith("image/") }
    }

    suspend fun saveToGallery(context: Context, item: SharedItem): ShareItemResult = runOperation {
        if (item !is SharedItem.Stream || !item.isImage) throw ShareOperationException(ShareFailure.NotAnImage)
        ShareCleanupWorker.scheduleRecovery(context)
        // The verified bytes are the ones published: a second read of the sender's URI could differ.
        val staged = guarded { ShareStaging.newFile(context) }
        try {
            guarded {
                openSource(context.contentResolver, item).use { input ->
                    staged.outputStream().use { output -> copyStream(input, output) }
                }
            }
            val mimeType = try {
                decodableImageType(staged)
            } catch (e: OutOfMemoryError) {
                throw ShareOperationException(ShareFailure.WriteFailed, e)
            } ?: throw ShareOperationException(ShareFailure.NotAnImage)
            val relativePath = ShareSettings.galleryRelativePath(context)
            val finalName = insertIntoMediaStore(
                context,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath,
                { staged.inputStream() },
                ShareFileNames.sanitize(item.displayName, mimeType, context.getString(R.string.share_image_fallback_name)),
                mimeType
            )
            ShareItemResult.Saved(finalName, relativePath.trimEnd('/'))
        } finally {
            ShareStaging.release(staged)
        }
    }

    suspend fun copyToClipboard(context: Context, item: SharedItem): ShareItemResult = runOperation {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: throw ShareOperationException(ShareFailure.ClipboardRejected)
        val label = context.getString(R.string.share_clip_label)
        when (item) {
            is SharedItem.Text -> {
                if (item.text.length > MAX_CLIPBOARD_TEXT_CHARS) {
                    throw ShareOperationException(ShareFailure.TooLargeForClipboard)
                }
                val clip = item.html?.let { ClipData.newHtmlText(label, item.text, it) }
                    ?: ClipData.newPlainText(label, item.text)
                ShareClipboardCache.place(context, null) { setClip(clipboard, clip); clip }
                ShareItemResult.Copied
            }
            is SharedItem.Stream -> {
                // Before the copy starts, so a copy abandoned by a killed process also expires.
                ShareCleanupWorker.scheduleClipboardExpiry(context)
                val file = guarded { ShareClipboardCache.newCopy(context, suggestedName(context, item), item.mimeType) }
                var placed = false
                try {
                    guarded {
                        openSource(context.contentResolver, item).use { input ->
                            file.outputStream().use { output -> copyStream(input, output) }
                        }
                    }
                    val uri = FileProvider.getUriForFile(context, ShareClipboardCache.authority(context), file)
                    // Readers get a temporary grant from the clipboard service until the clip changes.
                    val clip = ClipData(ClipDescription(label, arrayOf(item.mimeType)), ClipData.Item(uri))
                    ShareClipboardCache.place(context, file) { setClip(clipboard, clip); clip }
                    placed = true
                    ShareCleanupWorker.scheduleClipboardExpiry(context)
                    ShareItemResult.Copied
                } finally {
                    if (!placed) ShareClipboardCache.release(file)
                }
            }
        }
    }

    private fun setClip(clipboard: ClipboardManager, clip: ClipData) {
        try {
            clipboard.setPrimaryClip(clip)
        } catch (e: RuntimeException) {
            throw ShareOperationException(ShareFailure.ClipboardRejected, e)
        }
    }

    private suspend fun runOperation(block: suspend () -> ShareItemResult): ShareItemResult = try {
        block()
    } catch (e: ShareOperationException) {
        ShareItemResult.Failed(e.reason)
    }
}

/**
 * Private copies that back file clips. Each copy lives in its own directory with a sidecar that
 * holds its MIME type, so names never collide and [PlektraFileProvider] reports the type the clip
 * announces. A copy is deleted as soon as Plektra knows the clipboard no longer references it, and
 * in any case after [MAX_AGE_MILLIS]. Copies still being written are registered and never pruned.
 */
internal object ShareClipboardCache {
    private const val DIRECTORY = "share-clipboard"
    private const val MIME_SIDECAR = ".mime"
    const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Guards the copy registry and makes "place the clip" and "prune against the clip" atomic. */
    private val lock = Any()
    private val inFlight = mutableSetOf<String>()

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    fun root(context: Context): File = File(context.cacheDir, DIRECTORY)

    /** Creates the file for a new copy; it stays protected from pruning until placed or released. */
    fun newCopy(context: Context, name: String, mimeType: String): File {
        val directory = File(root(context), UUID.randomUUID().toString())
        synchronized(lock) {
            if (!directory.mkdirs()) throw IOException("Cannot create clipboard copy directory")
            inFlight += directory.name
        }
        try {
            File(directory, MIME_SIDECAR).writeText(mimeType)
        } catch (e: IOException) {
            release(File(directory, name))
            throw e
        }
        return File(directory, name)
    }

    /** Drops a copy that will not be placed. */
    fun release(file: File) {
        val directory = file.parentFile ?: return
        synchronized(lock) {
            inFlight -= directory.name
            directory.deleteRecursively()
        }
    }

    /**
     * Runs [setClip] and prunes against the clip it placed, both under the lock, so no concurrent
     * prune can see the new copy as unreferenced. [file] is the copy the clip refers to, if any.
     */
    fun place(context: Context, file: File?, setClip: () -> ClipData) {
        synchronized(lock) {
            try {
                val clip = setClip()
                pruneLocked(context, clip, now = System.currentTimeMillis())
            } finally {
                file?.parentFile?.let { inFlight -= it.name }
            }
        }
    }

    fun hasCopies(context: Context): Boolean = root(context).list()?.isNotEmpty() == true

    /**
     * Deletes copies that are expired or no longer referenced by the clip that [readClip]
     * returns. The clip is read under the lock so it cannot be stale against [place]. A null
     * clip is unknown, not empty: Android also returns null without read access or while the
     * device is locked, so then only expired copies go.
     */
    fun prune(context: Context, now: Long = System.currentTimeMillis(), readClip: () -> ClipData?) {
        synchronized(lock) {
            pruneLocked(context, readClip(), now)
        }
    }

    private fun pruneLocked(context: Context, currentClip: ClipData?, now: Long) {
        val referenced = buildSet {
            if (currentClip != null) {
                val authority = authority(context)
                for (index in 0 until currentClip.itemCount) {
                    val uri = currentClip.getItemAt(index).uri ?: continue
                    if (uri.authority?.let(ShareIntentParser::authorityWithoutUserId) != authority) continue
                    // FileProvider paths are <name>/<directory>/<file>.
                    uri.pathSegments.getOrNull(1)?.let(::add)
                }
            }
        }
        root(context).listFiles()?.forEach { directory ->
            if (directory.name in inFlight) return@forEach
            val expired = now - directory.lastModified() >= MAX_AGE_MILLIS
            val unreferenced = currentClip != null && directory.name !in referenced
            if (expired || unreferenced) directory.deleteRecursively()
        }
    }

    /** The MIME type recorded for a clipboard copy URI, or null for any other URI. */
    fun mimeTypeFor(context: Context, uri: Uri): String? {
        if (uri.authority != authority(context)) return null
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != "share_clipboard" || !uuidPattern.matches(segments[1])) return null
        return runCatching { File(File(root(context), segments[1]), MIME_SIDECAR).readText() }
            .getOrNull()
            ?.let(ShareMimeTypes::normalize)
    }
}

/** Private copies of shared pictures while they are verified and added to the gallery. */
internal object ShareStaging {
    private const val DIRECTORY = "share-staging"
    private val lock = Any()
    private val inFlight = mutableSetOf<String>()

    fun root(context: Context): File = File(context.cacheDir, DIRECTORY)

    fun newFile(context: Context): File {
        val directory = File(root(context), UUID.randomUUID().toString())
        synchronized(lock) {
            if (!directory.mkdirs()) throw IOException("Cannot create staging directory")
            inFlight += directory.name
        }
        return File(directory, "picture")
    }

    fun release(file: File) {
        val directory = file.parentFile ?: return
        synchronized(lock) {
            inFlight -= directory.name
            directory.deleteRecursively()
        }
    }

    /** Removes staging copies left behind by a process that ended during a gallery save. */
    fun recover(context: Context) {
        synchronized(lock) {
            root(context).listFiles()?.forEach { if (it.name !in inFlight) it.deleteRecursively() }
        }
    }
}
