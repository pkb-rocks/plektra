package rocks.pkb.plektra.backlight

/**
 * Reads the reply parcels that `service call` prints for the Unihertz vendor service.
 *
 * Adapted from the parcel parser in pkb-rocks/plektra#124 by Daniel JB Clark, which was ported
 * from PhysiBoard/Pastiera (brobata, freecost).
 */
internal object UnihertzVendorParcel {

    private const val MAX_STRING_LENGTH = 64
    private val wordPattern = Regex("\\b[0-9a-fA-F]{8}\\b")

    /**
     * Extracts the String16 of a successful reply such as
     * `Result: Parcel(00000000 00000002 0031002d 00000000 '........-.1.....')`.
     *
     * Word 0 is the exception code, word 1 the character count and the following words pack two
     * little-endian UTF-16 units each. The hex words are parsed instead of the quoted rendering,
     * which prints some characters as dots. A reply with an exception, a null or empty string or a
     * truncated payload yields null.
     */
    fun parseString(raw: String?): String? {
        val words = hexWords(raw) ?: return null
        if (words.size < 2 || words[0] != 0L) return null
        val length = words[1].toInt()
        if (length <= 0 || length > MAX_STRING_LENGTH) return null

        val out = StringBuilder(length)
        var index = 2
        while (out.length < length && index < words.size) {
            val word = words[index]
            out.append((word and 0xFFFF).toInt().toChar())
            if (out.length < length) {
                out.append(((word shr 16) and 0xFFFF).toInt().toChar())
            }
            index++
        }
        return out.toString().takeIf { it.length == length }
    }

    /** True for a reply whose exception code is zero, which is how a void call reports success. */
    fun isSuccessfulVoidReply(raw: String?): Boolean {
        val words = hexWords(raw) ?: return false
        return words.firstOrNull() == 0L
    }

    /**
     * Longer replies span several lines, each prefixed with a `0x00000010:` offset and followed by
     * its own quoted rendering, so offsets and renderings are removed line by line.
     */
    private fun hexWords(raw: String?): List<Long>? {
        val parcel = raw?.substringAfter("Result: Parcel(", "")?.takeIf { it.isNotEmpty() } ?: return null
        return parcel.lineSequence()
            .map { line -> line.substringBefore("'").substringBefore(")").replace(offsetPattern, "") }
            .flatMap { line -> wordPattern.findAll(line).map { it.value.toLong(16) } }
            .toList()
    }

    private val offsetPattern = Regex("0x[0-9a-fA-F]+:")
}
