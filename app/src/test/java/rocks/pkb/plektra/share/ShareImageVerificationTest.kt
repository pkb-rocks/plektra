package rocks.pkb.plektra.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 5. A gallery picture must decode, not merely have a valid header. Uses Android's real decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareImageVerificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** Keeps the header and dimensions, breaks the compressed pixel data. */
    private fun corruptedPng(): ByteArray {
        val bytes = png()
        val idat = bytes.asList().windowed(4).indexOfFirst { it == "IDAT".toByteArray().asList() }
        assertTrue(idat >= 0)
        for (i in idat + 4 until minOf(idat + 12, bytes.size)) bytes[i] = 0xFF.toByte()
        return bytes
    }

    private fun file(bytes: ByteArray) = File.createTempFile("picture", ".png", context.cacheDir).apply { writeBytes(bytes) }

    @Test
    fun decodablePictureIsAccepted() {
        assertEquals("image/png", ShareOperations.decodableImageType(file(png())))
    }

    @Test
    fun pictureWithBrokenPixelDataIsRejected() {
        assertNull(ShareOperations.decodableImageType(file(corruptedPng())))
    }

    /** A complete JPEG cut off halfway; BitmapFactory still returns a partly grey bitmap for it. */
    private fun truncatedJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (x in 0 until 256) for (y in 0 until 256) bitmap.setPixel(x, y, android.graphics.Color.rgb(x, y, (x * y) % 256))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        return bytes.copyOf(bytes.size / 2)
    }

    @Test
    fun completeJpegIsAccepted() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        assertEquals("image/jpeg", ShareOperations.decodableImageType(file(bytes)))
    }

    @Test
    fun truncatedJpegIsRejectedEvenThoughAPartialBitmapExists() {
        val bytes = truncatedJpeg()
        // The premise: the old check accepts this file.
        assertNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertNull(ShareOperations.decodableImageType(file(bytes)))
    }

    @Test
    fun galleryRejectsNonPicturesBeforeWriting() = runBlocking {
        val source = Uri.parse("content://org.example.files/fake.png")
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream("not a picture".toByteArray()))
        val item = SharedItem.Stream(0, source, "image/png", "fake.png", null)
        assertEquals(ShareItemResult.Failed(ShareFailure.NotAnImage), ShareOperations.saveToGallery(context, item))
        val text = SharedItem.Text(1, "Synthetic", null, null)
        assertEquals(ShareItemResult.Failed(ShareFailure.NotAnImage), ShareOperations.saveToGallery(context, text))
    }

    @Test
    fun galleryRefusesABrokenPictureBeforeWritingAnything() = runBlocking {
        val source = Uri.parse("content://org.example.files/broken.png")
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream(corruptedPng()))
        val result = ShareOperations.saveToGallery(context, SharedItem.Stream(0, source, "image/png", "broken.png", null))
        assertEquals(ShareItemResult.Failed(ShareFailure.NotAnImage), result)
        assertTrue(ShareStaging.root(context).listFiles().orEmpty().isEmpty())
    }
}
