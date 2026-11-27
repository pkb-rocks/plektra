package rocks.pkb.plektra.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun galleryRefusesABrokenPictureBeforeWritingAnything() = runBlocking {
        val source = Uri.parse("content://org.example.files/broken.png")
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream(corruptedPng()))
        val result = ShareOperations.saveToGallery(context, SharedItem.Stream(0, source, "image/png", "broken.png", null))
        assertEquals(ShareItemResult.Failed(ShareFailure.NotAnImage), result)
        assertTrue(ShareStaging.root(context).listFiles().orEmpty().isEmpty())
    }
}
