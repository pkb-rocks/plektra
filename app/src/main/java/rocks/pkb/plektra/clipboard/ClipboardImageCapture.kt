package rocks.pkb.plektra.clipboard

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Copies a copied image into a private staging file before [ClipboardHistory] stores it.
 *
 * The read grant the clipboard gives the IME for a clip's URI ends when the next clip
 * replaces it, and the history worker may still be loading. Capture therefore runs on its
 * own thread independently of the history queue. It opens the source as soon as its capture
 * task runs; a grant that has already ended is reported as a read failure. The copy is bounded, its format is recognised from the file signature, and
 * only image bounds are decoded. A result that passes goes to [onCaptured] on the capture
 * thread; failures are reported on [publisher].
 */
internal class ClipboardImageCapture(
    private val open: (Uri) -> InputStream?,
    private val stagingDirectory: File,
    private val executor: Executor,
    private val publisher: Executor
) {
    enum class Failure { TOO_LARGE, UNSUPPORTED, UNREADABLE }

    /** A validated image waiting in [file] until the history adopts or discards it. */
    data class Staged(val file: File, val mimeType: String, val byteSize: Long, val width: Int, val height: Int, val digest: String)

    init {
        // Leftovers of an earlier process; this instance is the only writer from now on.
        executor.execute { stagingDirectory.listFiles()?.forEach(File::delete) }
    }

    fun capture(
        uri: Uri,
        limits: ClipboardImageLimits,
        onCaptured: (Staged) -> Unit,
        onFailure: (Failure) -> Unit
    ) = executor.execute {
        val result = try {
            copy(uri, limits)
        } catch (e: Exception) {
            // Exception messages can carry the source URI; log only the type.
            Log.w(TAG, "Clipboard image could not be read (${e.javaClass.simpleName})")
            Result.Failed(Failure.UNREADABLE)
        }
        when (result) {
            is Result.Ok -> onCaptured(result.staged)
            is Result.Failed -> publisher.execute { onFailure(result.failure) }
        }
    }

    private fun copy(uri: Uri, limits: ClipboardImageLimits): Result {
        stagingDirectory.mkdirs()
        val file = File(stagingDirectory, "${UUID.randomUUID()}.tmp")
        var keep = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(ClipboardImageFormat.HEADER_BYTES)
            var headerSize = 0
            var total = 0L
            val source = open(uri) ?: return Result.Failed(Failure.UNREADABLE)
            source.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limits.maxImageBytes) return Result.Failed(Failure.TOO_LARGE)
                        if (headerSize < header.size) {
                            val n = minOf(read, header.size - headerSize)
                            System.arraycopy(buffer, 0, header, headerSize, n)
                            headerSize += n
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) return Result.Failed(Failure.UNREADABLE)
            val mimeType = ClipboardImageFormat.sniff(header.copyOf(headerSize))
                ?: return Result.Failed(Failure.UNSUPPORTED)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result.Failed(Failure.UNREADABLE)
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            keep = true
            return Result.Ok(Staged(file, mimeType, total, bounds.outWidth, bounds.outHeight, hex))
        } finally {
            if (!keep) file.delete()
        }
    }

    private sealed class Result {
        class Ok(val staged: Staged) : Result()
        class Failed(val failure: Failure) : Result()
    }

    companion object {
        private const val TAG = "ClipboardImageCapture"

        @Volatile
        private var instance: ClipboardImageCapture? = null

        /** One per process, because it owns the staging directory. */
        fun getInstance(context: Context): ClipboardImageCapture {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(context: Context): ClipboardImageCapture {
            val resolver: ContentResolver = context.contentResolver
            val mainHandler = Handler(Looper.getMainLooper())
            return ClipboardImageCapture(
                resolver::openInputStream,
                ClipboardImageStore.create(context).stagingDirectory,
                ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
                    Thread(runnable, "ClipboardImageCapture").apply { isDaemon = true }
                }.apply { allowCoreThreadTimeOut(true) },
                Executor { mainHandler.post(it) }
            )
        }
    }
}
