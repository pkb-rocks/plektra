package rocks.pkb.plektra.update

private val releaseTagUrl = Regex(
    "^((?i:https?://github\\.com)/[^/?#]+/[^/?#]+/releases/tag/)([^?#]+)([?#].*)?$"
)
private const val HEX = "0123456789ABCDEF"

/** Encodes a GitHub release tag as one path segment, preserving existing percent escapes. */
internal fun normalizeGitHubReleaseUrl(url: String): String {
    val match = releaseTagUrl.matchEntire(url) ?: return url
    val tag = match.groupValues[2].toByteArray(Charsets.UTF_8)
    val encodedTag = buildString {
        var index = 0
        while (index < tag.size) {
            val byte = tag[index].toInt() and 0xff
            val char = byte.toChar()
            if (char == '%' && index + 2 < tag.size &&
                tag[index + 1].toInt().toChar().isHexDigit() &&
                tag[index + 2].toInt().toChar().isHexDigit()
            ) {
                append('%')
                append(tag[index + 1].toInt().toChar())
                append(tag[index + 2].toInt().toChar())
                index += 3
                continue
            }
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") {
                append(char)
            } else {
                append('%')
                append(HEX[byte shr 4])
                append(HEX[byte and 0xf])
            }
            index++
        }
    }
    return match.groupValues[1] + encodedTag + match.groupValues[3]
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
