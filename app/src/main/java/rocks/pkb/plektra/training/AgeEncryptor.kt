package rocks.pkb.plektra.training

import com.google.crypto.tink.subtle.X25519
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal encrypt-only implementation of the age v1 format (https://age-encryption.org/v1)
 * for a single X25519 recipient. Output can be decrypted with the reference `age` CLI.
 */
class AgeEncryptor(recipient: String, private val random: SecureRandom = SecureRandom()) {

    private val recipientKey: ByteArray = decodeRecipient(recipient)

    fun encrypt(plaintext: ByteArray): ByteArray {
        val fileKey = ByteArray(FILE_KEY_SIZE).also(random::nextBytes)
        val ephemeralSecret = X25519.generatePrivateKey()
        val ephemeralShare = X25519.publicFromPrivate(ephemeralSecret)
        val sharedSecret = X25519.computeSharedSecret(ephemeralSecret, recipientKey)
        if (sharedSecret.all { it == 0.toByte() }) throw GeneralSecurityException("Invalid X25519 recipient")
        val wrapKey = hkdf(sharedSecret, ephemeralShare + recipientKey, X25519_INFO, 32)
        val wrappedFileKey = chacha20Poly1305(wrapKey, ByteArray(NONCE_SIZE), fileKey)

        val header = buildString {
            append("age-encryption.org/v1\n")
            append("-> X25519 ").append(base64(ephemeralShare)).append('\n')
            append(wrapBody(base64(wrappedFileKey)))
            append("---")
        }
        val macKey = hkdf(fileKey, ByteArray(0), "header".toByteArray(), 32)
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(macKey, "HmacSHA256"))
            doFinal(header.toByteArray(Charsets.US_ASCII))
        }

        val payloadNonce = ByteArray(PAYLOAD_NONCE_SIZE).also(random::nextBytes)
        val payloadKey = hkdf(fileKey, payloadNonce, "payload".toByteArray(), 32)
        val out = ByteArrayOutputStream(plaintext.size + plaintext.size / CHUNK_SIZE * TAG_SIZE + 256)
        out.write("$header ${base64(mac)}\n".toByteArray(Charsets.US_ASCII))
        out.write(payloadNonce)
        val chunkCount = maxOf(1, (plaintext.size + CHUNK_SIZE - 1) / CHUNK_SIZE)
        for (index in 0 until chunkCount) {
            val start = index * CHUNK_SIZE
            val end = minOf(plaintext.size, start + CHUNK_SIZE)
            val nonce = ByteArray(NONCE_SIZE)
            var counter = index.toLong()
            for (position in 10 downTo 0) {
                nonce[position] = (counter and 0xFF).toByte()
                counter = counter ushr 8
            }
            if (index == chunkCount - 1) nonce[11] = 1
            out.write(chacha20Poly1305(payloadKey, nonce, plaintext.copyOfRange(start, end)))
        }
        return out.toByteArray()
    }

    companion object {
        private const val FILE_KEY_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val PAYLOAD_NONCE_SIZE = 16
        private const val TAG_SIZE = 16
        private const val CHUNK_SIZE = 64 * 1024
        private val X25519_INFO = "age-encryption.org/v1/X25519".toByteArray()
        private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        private fun base64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

        /** Stanza bodies are wrapped at 64 columns; the final line is always shorter than 64. */
        private fun wrapBody(encoded: String): String = buildString {
            encoded.chunked(64).forEach { append(it).append('\n') }
            if (encoded.length % 64 == 0) append('\n')
        }

        private fun chacha20Poly1305(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305").firstNotNullOfOrNull { name ->
                runCatching { Cipher.getInstance(name) }.getOrNull()
            } ?: throw GeneralSecurityException("ChaCha20-Poly1305 unavailable")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            return cipher.doFinal(plaintext)
        }

        internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val result = ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (result.size() < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                result.write(previous)
                counter++
            }
            return result.toByteArray().copyOf(length)
        }

        internal fun decodeRecipient(recipient: String): ByteArray {
            val lower = recipient.trim().lowercase()
            val separator = lower.lastIndexOf('1')
            require(separator > 0 && lower.substring(0, separator) == "age") { "Not an age X25519 recipient" }
            val values = lower.substring(separator + 1).map { char ->
                BECH32_CHARSET.indexOf(char).also { require(it >= 0) { "Invalid bech32 character" } }
            }
            require(values.size > 6) { "Recipient too short" }
            val hrp = lower.substring(0, separator)
            val checksumInput = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + values
            require(bech32Polymod(checksumInput) == 1) { "Invalid bech32 checksum" }
            var accumulator = 0
            var bits = 0
            val bytes = ByteArrayOutputStream()
            for (value in values.dropLast(6)) {
                accumulator = (accumulator shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    bytes.write((accumulator shr bits) and 0xFF)
                }
            }
            require(bits < 5 && (accumulator and ((1 shl bits) - 1)) == 0) { "Invalid bech32 padding" }
            return bytes.toByteArray().also { require(it.size == 32) { "Recipient must hold 32 bytes" } }
        }

        private fun bech32Polymod(values: List<Int>): Int {
            val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
            var checksum = 1
            for (value in values) {
                val top = checksum ushr 25
                checksum = ((checksum and 0x1ffffff) shl 5) xor value
                for (index in 0 until 5) {
                    if ((top ushr index) and 1 == 1) checksum = checksum xor generators[index]
                }
            }
            return checksum
        }
    }
}
