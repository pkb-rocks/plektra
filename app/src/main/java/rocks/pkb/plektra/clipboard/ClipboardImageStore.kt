package rocks.pkb.plektra.clipboard

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Private files of stored clipboard images.
 *
 * Only the [ClipboardHistory] worker creates, renames or deletes files in [directory]; a file
 * never changes after it is referenced by a row. Readers (thumbnails, the content provider)
 * open published files read-only and tolerate a file that disappears with its entry.
 *
 * [stagingDirectory] holds files that [ClipboardImageCapture] is still copying or that wait
 * for the worker. It is on the same file system, so adopting a staged file is a rename.
 */
internal class ClipboardImageStore(val directory: File, val stagingDirectory: File) {

    fun file(asset: String): File = File(directory, asset)

    /** Moves a validated staged file into the store under a fresh asset name. */
    fun adopt(staged: File, mimeType: String): String {
        directory.mkdirs()
        val asset = "${UUID.randomUUID()}.${extension(mimeType)}"
        if (!staged.renameTo(file(asset))) {
            staged.delete()
            throw java.io.IOException("Could not move staged clipboard image")
        }
        return asset
    }

    fun delete(asset: String) {
        val file = file(asset)
        if (file.exists() && !file.delete()) {
            // Left for the orphan sweep at the next start.
            android.util.Log.w(TAG, "Clipboard image could not be deleted")
        }
    }

    /** Deletes every stored file that no row references. */
    fun deleteUnreferenced(referenced: Set<String>) {
        directory.listFiles()?.forEach { file ->
            if (file.name !in referenced) file.delete()
        }
    }

    fun exists(asset: String): Boolean = file(asset).isFile

    companion object {
        private const val TAG = "ClipboardImageStore"

        /** Must match the path published in res/xml/clipboard_image_paths.xml. */
        const val DIRECTORY = "clipboard_images"
        private const val STAGING_DIRECTORY = "clipboard_image_staging"

        fun create(context: Context) = ClipboardImageStore(
            File(context.filesDir, DIRECTORY),
            File(context.noBackupFilesDir, STAGING_DIRECTORY)
        )

        fun extension(mimeType: String): String = when (mimeType) {
            ClipboardImageFormat.PNG -> "png"
            ClipboardImageFormat.JPEG -> "jpg"
            ClipboardImageFormat.GIF -> "gif"
            ClipboardImageFormat.WEBP -> "webp"
            else -> "bin"
        }
    }
}
