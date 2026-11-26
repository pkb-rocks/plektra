package rocks.pkb.plektra.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
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

    /** Copies [item] to [target]; source failures and write failures stay distinguishable. */
    private suspend fun writeItem(resolver: ContentResolver, item: SharedItem, target: Uri) {
        openSource(resolver, item).use { input ->
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
     * Writes a MediaStore row as pending and publishes it only after every byte arrived, so other
     * apps never see a partial file. Failed or cancelled rows are deleted.
     */
    private suspend fun insertIntoMediaStore(
        context: Context,
        collection: Uri,
        relativePath: String,
        item: SharedItem,
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
        var published = false
        try {
            guarded { writeItem(resolver, item, row) }
            guarded {
                resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            published = true
            return displayNameOf(resolver, row, name)
        } finally {
            if (!published) runCatching { resolver.delete(row, null, null) }
        }
    }

    suspend fun saveToDownloads(context: Context, item: SharedItem): ShareItemResult = runOperation {
        val name = suggestedName(context, item)
        val finalName = insertIntoMediaStore(
            context,
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            Environment.DIRECTORY_DOWNLOADS + "/",
            item,
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
            val resolver = context.contentResolver
            val parent = try {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            } catch (e: IllegalArgumentException) {
                throw ShareOperationException(ShareFailure.FolderUnavailable, e)
            }
            val name = suggestedName(context, item)
            // Document providers resolve name collisions themselves, e.g. "name (1).ext".
            val document = try {
                DocumentsContract.createDocument(resolver, parent, mimeOf(item), name)
            } catch (e: SecurityException) {
                throw ShareOperationException(ShareFailure.FolderAccessRevoked, e)
            } catch (e: FileNotFoundException) {
                throw ShareOperationException(ShareFailure.FolderUnavailable, e)
            } catch (e: IllegalArgumentException) {
                throw ShareOperationException(ShareFailure.FolderUnavailable, e)
            } catch (e: IllegalStateException) {
                throw ShareOperationException(ShareFailure.FolderUnavailable, e)
            } ?: throw ShareOperationException(ShareFailure.FolderUnavailable)
            var complete = false
            try {
                guarded { writeItem(resolver, item, document) }
                complete = true
            } finally {
                if (!complete) runCatching { DocumentsContract.deleteDocument(resolver, document) }
            }
            ShareItemResult.Saved(displayNameOf(resolver, document, name), folderLabel)
        }

    /** The decoded format, or null when Android cannot decode [item] as a picture. */
    private suspend fun decodedImageType(context: Context, item: SharedItem.Stream): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        guarded { openSource(context.contentResolver, item).use { BitmapFactory.decodeStream(it, null, options) } }
        return options.outMimeType?.let(ShareMimeTypes::normalize)
            ?.takeIf { it.startsWith("image/") && options.outWidth > 0 && options.outHeight > 0 }
    }

    suspend fun saveToGallery(context: Context, item: SharedItem): ShareItemResult = runOperation {
        if (item !is SharedItem.Stream || !item.isImage) throw ShareOperationException(ShareFailure.NotAnImage)
        val mimeType = decodedImageType(context, item) ?: throw ShareOperationException(ShareFailure.NotAnImage)
        val relativePath = ShareSettings.galleryRelativePath(context)
        val finalName = insertIntoMediaStore(
            context,
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            relativePath,
            item,
            ShareFileNames.sanitize(item.displayName, mimeType, context.getString(R.string.share_image_fallback_name)),
            mimeType
        )
        ShareItemResult.Saved(finalName, relativePath.trimEnd('/'))
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
                setClip(clipboard, clip)
                ShareClipboardCache.prune(context, clip)
                ShareItemResult.Copied
            }
            is SharedItem.Stream -> {
                val file = guarded { ShareClipboardCache.newFile(context, suggestedName(context, item)) }
                var handedOver = false
                try {
                    guarded {
                        openSource(context.contentResolver, item).use { input ->
                            file.outputStream().use { output -> copyStream(input, output) }
                        }
                    }
                    val uri = FileProvider.getUriForFile(context, ShareClipboardCache.authority(context), file)
                    // Readers get a temporary grant from the clipboard service until the clip changes.
                    val clip = ClipData(ClipDescription(label, arrayOf(item.mimeType)), ClipData.Item(uri))
                    setClip(clipboard, clip)
                    handedOver = true
                    ShareClipboardCache.prune(context, clip)
                    ShareItemResult.Copied
                } finally {
                    if (!handedOver) file.parentFile?.deleteRecursively()
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
 * Private copies that back file clips. Each copy lives in its own directory so names never
 * collide. Copies no longer referenced by the primary clip are removed when Plektra places
 * the next clip and whenever the keyboard observes a clipboard change.
 */
internal object ShareClipboardCache {
    private const val DIRECTORY = "share-clipboard"

    /** Copies younger than this survive a prune so one that is just being placed is kept. */
    private const val GRACE_MILLIS = 60_000L

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    fun root(context: Context): File = File(context.cacheDir, DIRECTORY)

    fun newFile(context: Context, name: String): File {
        val directory = File(root(context), UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw IOException("Cannot create clipboard copy directory")
        return File(directory, name)
    }

    fun hasCopies(context: Context): Boolean = root(context).list()?.isNotEmpty() == true

    /** Deletes copies that [currentClip] no longer references. */
    fun prune(context: Context, currentClip: ClipData?, now: Long = System.currentTimeMillis()) {
        val referenced = buildSet {
            if (currentClip != null) {
                val authority = authority(context)
                for (index in 0 until currentClip.itemCount) {
                    val uri = currentClip.getItemAt(index).uri ?: continue
                    if (uri.authority != authority) continue
                    // FileProvider paths are <name>/<directory>/<file>.
                    uri.pathSegments.getOrNull(1)?.let(::add)
                }
            }
        }
        root(context).listFiles()?.forEach { directory ->
            if (directory.name in referenced) return@forEach
            if (now - directory.lastModified() < GRACE_MILLIS) return@forEach
            directory.deleteRecursively()
        }
    }
}
