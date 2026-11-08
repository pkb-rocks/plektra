package rocks.pkb.plektra.clipboard

import android.content.ClipDescription

/** Image formats the clipboard history stores, recognised by their file signature. */
internal object ClipboardImageFormat {
    const val PNG = "image/png"
    const val JPEG = "image/jpeg"
    const val GIF = "image/gif"
    const val WEBP = "image/webp"

    /** Bytes [sniff] needs to recognise every supported format. */
    const val HEADER_BYTES = 12

    /** The supported format of [header], or null; the declared clipboard type is not trusted. */
    fun sniff(header: ByteArray): String? = when {
        header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
        header.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
        header.startsWithAscii("GIF87a") || header.startsWithAscii("GIF89a") -> GIF
        header.startsWithAscii("RIFF") && header.size >= 12 &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> WEBP
        else -> null
    }

    /** Short format name for previews. */
    fun label(mimeType: String): String = when (mimeType) {
        PNG -> "PNG"
        JPEG -> "JPEG"
        GIF -> "GIF"
        WEBP -> "WebP"
        else -> mimeType
    }

    /** Whether a clip announces an image, so its URI is worth reading. */
    fun announcesImage(description: ClipDescription): Boolean =
        (0 until description.mimeTypeCount).any { ClipDescription.compareMimeTypes(description.getMimeType(it), "image/*") }

    /** Whether an editor that lists [accepted] content types takes an image of [mimeType]. */
    fun accepts(accepted: Array<String>, mimeType: String): Boolean =
        accepted.any { ClipDescription.compareMimeTypes(mimeType, it) }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean =
        startsWith(*prefix.map { it.code }.toIntArray())
}

/**
 * Bounds for stored clipboard images; text is not limited.
 * @param maxImageBytes Largest single image that is accepted
 * @param maxImages Most image entries kept at once
 * @param maxTotalBytes Most bytes all image entries may occupy together
 */
internal data class ClipboardImageLimits(
    val maxImageBytes: Long,
    val maxImages: Int,
    val maxTotalBytes: Long
) {
    fun maxImageMegabytes(): Int = (maxImageBytes / MEGABYTE).toInt()

    fun maxTotalMegabytes(): Int = (maxTotalBytes / MEGABYTE).toInt()

    companion object {
        private const val MEGABYTE = 1024L * 1024
        val DEFAULT = ClipboardImageLimits(
            maxImageBytes = 10 * MEGABYTE,
            maxImages = 20,
            maxTotalBytes = 50 * MEGABYTE
        )
    }
}
