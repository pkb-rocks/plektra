package rocks.pkb.plektra

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounded sRGB preview; source photos are never persisted or backed up. */
internal object ThemePhotoColors {
    const val MAX_EDGE = 1024
    const val MAX_BYTES = 16 * 1024 * 1024

    fun boundedSize(width: Int, height: Int): IntSize {
        require(width > 0 && height > 0)
        val ratio = min(1.0, MAX_EDGE.toDouble() / max(width, height))
        return IntSize(max(1, (width * ratio).roundToInt()), max(1, (height * ratio).roundToInt()))
    }

    fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "Photo exceeds preview limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    ) { decoder, info, _ ->
        val size = boundedSize(info.size.width, info.size.height)
        decoder.setTargetSize(size.width, size.height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    /** Letterboxed margins do not represent pixels in the photo. */
    fun imagePoint(image: IntSize, viewport: IntSize, point: Offset): IntOffset? {
        if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return null
        val scale = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val x = (point.x - (viewport.width - image.width * scale) / 2) / scale
        val y = (point.y - (viewport.height - image.height * scale) / 2) / scale
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= image.width || y >= image.height) return null
        return IntOffset(x.toInt(), y.toInt())
    }

    /** Alpha weighting ignores invisible RGB in transparent pixels. */
    fun sample(bitmap: Bitmap, point: IntOffset): Int? {
        if (point.x !in 0 until bitmap.width || point.y !in 0 until bitmap.height) return null
        var weight = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        for (y in max(0, point.y - 2)..min(bitmap.height - 1, point.y + 2)) {
            for (x in max(0, point.x - 2)..min(bitmap.width - 1, point.x + 2)) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = android.graphics.Color.alpha(pixel).toLong()
                weight += alpha
                red += android.graphics.Color.red(pixel) * alpha
                green += android.graphics.Color.green(pixel) * alpha
                blue += android.graphics.Color.blue(pixel) * alpha
            }
        }
        if (weight == 0L) return null
        return android.graphics.Color.rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt())
    }
}
