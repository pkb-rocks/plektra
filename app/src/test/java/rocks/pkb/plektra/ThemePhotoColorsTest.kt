package rocks.pkb.plektra

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemePhotoColorsTest {
    @Test fun previewBoundsLargePortraitLandscapeAndTinyImages() {
        assertEquals(IntSize(1024, 512), ThemePhotoColors.boundedSize(8000, 4000))
        assertEquals(IntSize(512, 1024), ThemePhotoColors.boundedSize(4000, 8000))
        assertEquals(IntSize(1, 1024), ThemePhotoColors.boundedSize(1, Int.MAX_VALUE))
        assertEquals(IntSize(12, 7), ThemePhotoColors.boundedSize(12, 7))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidDimensionsRejected() {
        ThemePhotoColors.boundedSize(0, 100)
    }
    @Test fun centreAndEdgesMapIntoPhotoWithoutSamplingLetterbox() {
        val image = IntSize(100, 50)
        val viewport = IntSize(200, 200)
        assertEquals(IntOffset(50, 25), ThemePhotoColors.imagePoint(image, viewport, Offset(100f, 100f)))
        assertEquals(IntOffset(0, 0), ThemePhotoColors.imagePoint(image, viewport, Offset(0f, 50f)))
        assertNull(ThemePhotoColors.imagePoint(image, viewport, Offset(100f, 49f)))
        assertNull(ThemePhotoColors.imagePoint(image, viewport, Offset(200f, 100f)))
        assertNull(ThemePhotoColors.imagePoint(image, viewport, Offset(100f, 150f)))
        assertNull(ThemePhotoColors.imagePoint(image, viewport, Offset(Float.NaN, 100f)))
        assertNull(ThemePhotoColors.imagePoint(image, IntSize.Zero, Offset.Zero))
    }
    @Test fun portraitMarginsAndBottomRightPixelMapCorrectly() {
        val image = IntSize(50, 100)
        val viewport = IntSize(200, 200)
        assertNull(ThemePhotoColors.imagePoint(image, viewport, Offset(49f, 100f)))
        assertEquals(IntOffset(49, 99), ThemePhotoColors.imagePoint(image, viewport, Offset(149f, 199f)))
    }
    @Test fun sampledPatchIsAveragedAndOpaque() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.RED)
        bitmap.setPixel(1, 0, Color.BLUE)
        assertEquals(Color.rgb(127, 0, 127), ThemePhotoColors.sample(bitmap, IntOffset.Zero))
        assertNull(ThemePhotoColors.sample(bitmap, IntOffset(-1, 0)))
    }
    @Test fun transparentPixelsCannotIntroduceInvisibleColours() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.GREEN)
        bitmap.setPixel(1, 0, Color.TRANSPARENT)
        assertEquals(Color.GREEN, ThemePhotoColors.sample(bitmap, IntOffset.Zero))
        bitmap.eraseColor(Color.TRANSPARENT)
        assertNull(ThemePhotoColors.sample(bitmap, IntOffset.Zero))
    }
    @Test fun boundedReaderKeepsOriginalBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, ThemePhotoColors.readBounded(ByteArrayInputStream(bytes)))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedStreamRejectedBeforeDecoding() {
        val input = object : InputStream() {
            var remaining = ThemePhotoColors.MAX_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                bytes.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        ThemePhotoColors.readBounded(input)
    }
    @Test fun sampledColourSurvivesActualThemeZipWithoutSourceImage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().clear().commit()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(31, 92, 143)) }
        val color = requireNotNull(ThemePhotoColors.sample(bitmap, IntOffset.Zero))
        val theme = SettingsManager.defaultKeyboardTheme().copy(normalKey = color, suggestion = color)
        SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE, theme)
        SettingsManager.saveKeyboardTheme(context, "Photo colour", theme)
        val zip = File(context.cacheDir, "photo-colour.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(zip)) is BackupResult.Success)
        java.util.zip.ZipFile(zip).use { archive ->
            assertFalse(archive.entries().asSequence().any { it.name.startsWith("theme-images/") || it.name.endsWith(".png") })
        }
        prefs.edit().clear().commit()
        assertTrue(RestoreManager.restore(context, Uri.fromFile(zip)) is RestoreResult.Success)
        assertEquals(theme, SettingsManager.getKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE))
        assertEquals(theme, SettingsManager.getSavedKeyboardThemes(context).single().theme)
    }
}
