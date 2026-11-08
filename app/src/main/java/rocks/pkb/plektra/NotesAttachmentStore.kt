package rocks.pkb.plektra

import android.content.Context
import android.graphics.BitmapFactory
import rocks.pkb.plektra.clipboard.ClipboardImageFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Images that belong to notes, kept in app-private storage and referenced from the Markdown
 * body as `attachments/<name>`. Only names this store generates resolve to a file, so a note
 * cannot point the preview at other private files or at remote sources.
 */
internal class NotesAttachmentStore(
    val directory: File,
    val stagingDirectory: File,
    val maxImageBytes: Long = MAX_IMAGE_BYTES
) {
    enum class Failure { UNSUPPORTED, TOO_LARGE, FAILED }

    class ImportException(val failure: Failure) : IOException(failure.name)

    fun newName(mimeType: String): String {
        val extension = requireNotNull(extensions[mimeType]) { "Unsupported image type" }
        return UUID.randomUUID().toString().replace("-", "") + "." + extension
    }

    /** The file for a [name] this store generates, or null for any other name. */
    fun file(name: String): File? = if (NAME.matches(name)) File(directory, name) else null

    /** The file a Markdown image [destination] refers to, or null when it is not an attachment. */
    fun resolve(destination: String): File? =
        if (destination.startsWith(REFERENCE_PREFIX)) file(destination.removePrefix(REFERENCE_PREFIX)) else null

    /**
     * Copies [input] into the store as [name]. The bytes must be an image of the type the name
     * announces and at most [maxImageBytes] long; on any failure nothing is left behind.
     */
    fun import(input: InputStream, name: String) {
        val target = file(name) ?: throw ImportException(Failure.UNSUPPORTED)
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) throw ImportException(Failure.FAILED)
        if (!directory.isDirectory && !directory.mkdirs()) throw ImportException(Failure.FAILED)
        val staging = File.createTempFile("import-", ".part", stagingDirectory)
        try {
            FileOutputStream(staging).use { output ->
                val header = readHeader(input)
                if (sniff(header) != mimeTypeOf(name)) throw ImportException(Failure.UNSUPPORTED)
                output.write(header)
                var total = header.size.toLong()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxImageBytes) throw ImportException(Failure.TOO_LARGE)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(staging.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImportException(Failure.UNSUPPORTED)
            if (!staging.renameTo(target)) throw ImportException(Failure.FAILED)
        } catch (e: ImportException) {
            staging.delete()
            throw e
        } catch (e: Exception) {
            staging.delete()
            throw ImportException(Failure.FAILED)
        }
    }

    fun delete(name: String) {
        file(name)?.delete()
    }

    companion object {
        const val DIRECTORY = "notes_attachments"
        const val REFERENCE_PREFIX = "attachments/"
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

        /** The image types notes accept; the editor announces them to keyboards. */
        val MIME_TYPES = arrayOf(ClipboardImageFormat.PNG, ClipboardImageFormat.JPEG, ClipboardImageFormat.GIF, ClipboardImageFormat.WEBP)

        private val extensions = mapOf(
            ClipboardImageFormat.PNG to "png",
            ClipboardImageFormat.JPEG to "jpg",
            ClipboardImageFormat.GIF to "gif",
            ClipboardImageFormat.WEBP to "webp"
        )
        private val NAME = Regex("[0-9a-f]{32}\\.(png|jpg|gif|webp)")
        private val REFERENCE = Regex(Regex.escape(REFERENCE_PREFIX) + "([0-9a-f]{32}\\.(?:png|jpg|gif|webp))")

        fun forContext(context: Context) = NotesAttachmentStore(
            File(context.filesDir, DIRECTORY),
            // Next to the attachments, so a finished copy is renamed within app files and is not
            // accounted as cache.
            File(context.filesDir, "notes_attachment_staging")
        )

        fun reference(name: String) = REFERENCE_PREFIX + name

        /** Attachment names that [markdown] refers to. */
        fun referencedNames(markdown: CharSequence): Set<String> =
            REFERENCE.findAll(markdown).map { it.groupValues[1] }.toSet()

        /** The supported image type of [header], recognised by its file signature. */
        fun sniff(header: ByteArray): String? = ClipboardImageFormat.sniff(header)?.takeIf { it in extensions }

        fun mimeTypeOf(name: String): String? = extensions.entries.firstOrNull { name.endsWith("." + it.value) }?.key

        fun readHeader(input: InputStream): ByteArray {
            val header = ByteArray(ClipboardImageFormat.HEADER_BYTES)
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            return header.copyOf(read)
        }

        /** Whether [file] from a backup is an attachment this store could have written. */
        fun isValidBackupFile(file: File): Boolean {
            if (!NAME.matches(file.name) || file.length() > MAX_IMAGE_BYTES) return false
            return file.inputStream().use { sniff(readHeader(it)) } == mimeTypeOf(file.name)
        }
    }
}
