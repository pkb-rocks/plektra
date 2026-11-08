package rocks.pkb.plektra.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Decodes small previews of stored clipboard images off the main thread.
 *
 * Images are sampled down while decoding, so a preview never holds the full resolution.
 * Previews are cached by asset name; stored files never change, so a cached preview stays
 * valid until its entry is deleted. [shutdown] ends the thread and drops the cache and every
 * pending callback.
 */
internal class ClipboardThumbnailLoader(
    private val publisher: Executor,
    private val decoder: ExecutorService = newDecoderThread()
) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Volatile
    private var closed = false

    fun cached(asset: String): Bitmap? = cache.get(asset)

    /** Calls [onLoaded] on the publisher with the preview, or null when the file is gone or unreadable. */
    fun load(asset: String, file: File, maxSidePx: Int, onLoaded: (Bitmap?) -> Unit) {
        cache.get(asset)?.let { bitmap -> onLoaded(bitmap); return }
        if (closed) return
        decoder.execute {
            val bitmap = if (closed) null else decode(file, maxSidePx)
            if (bitmap != null) cache.put(asset, bitmap)
            publisher.execute { if (!closed) onLoaded(bitmap) }
        }
    }

    fun shutdown() {
        closed = true
        decoder.shutdownNow()
        cache.evictAll()
    }

    companion object {
        private const val TAG = "ClipboardThumbnails"
        private const val CACHE_BYTES = 4 * 1024 * 1024

        private fun newDecoderThread(): ExecutorService =
            ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
                Thread(runnable, "ClipboardThumbnails").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }

        /**
         * Power-of-two step that brings the longer side to at most twice [maxSidePx], which
         * bounds a preview to a few hundred kilobytes whatever the image size.
         */
        fun sampleSize(width: Int, height: Int, maxSidePx: Int): Int {
            val longer = maxOf(width, height)
            var sample = 1
            while (longer / sample > maxSidePx * 2) sample *= 2
            return sample
        }

        fun decode(file: File, maxSidePx: Int): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard preview failed (${e.javaClass.simpleName})")
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Clipboard preview failed (OutOfMemoryError)")
            null
        }
    }
}
