package rocks.pkb.plektra.inputmethod.otp

import java.security.MessageDigest

/** Session-memory fingerprints only. A code pasted in the middle of a word protects that word too. */
internal class InsertedCodes : (String) -> Boolean {
    private val fingerprints = mutableMapOf<Int, MutableSet<List<Byte>>>()
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private var wholeEditor = false

    fun protectWholeEditor() { wholeEditor = true }

    fun remember(code: String) {
        fingerprints.getOrPut(code.length) { mutableSetOf() }.add(digest(code))
    }

    override fun invoke(word: String): Boolean = wholeEditor || fingerprints.any { (length, hashes) ->
        (0..word.length - length).any { offset -> digest(word.substring(offset, offset + length)) in hashes }
    }

    private fun digest(word: String) = sha256.digest(word.toByteArray()).toList()
}
