/*
 * Vendored from Shizuku (https://github.com/RikkaApps/Shizuku) by RikkaApps.
 * Licensed under the Apache License, Version 2.0. See
 * app/src/main/java/moe/shizuku/manager/adb/NOTICE and third_party/licenses/Apache-2.0.txt.
 *
 * Plektra: the key no longer lives here as a heap RSA key wrapped by an AES Keystore key. It
 * comes from an [AdbKeySource], in production AndroidKeyStore (AdbKeyStoreSource.kt), so the
 * private key is a non-exportable handle (adversary review 2026-10-12, finding 1.3d).
 */
package moe.shizuku.manager.adb

import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

private const val TAG = "AdbKey"

/**
 * The ADB identity: an RSA-2048 private key and the self-signed certificate that carries its
 * public half. [privateKey] may be an opaque handle (AndroidKeyStore); nothing here reads its
 * encoding. adbd checks only the public key in the certificate, not the certificate itself.
 */
class AdbKeyMaterial(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val publicKey: RSAPublicKey = certificate.publicKey as? RSAPublicKey
        ?: throw AdbKeyException("ADB key is not RSA", IllegalArgumentException(certificate.publicKey.algorithm))
}

/**
 * Where the ADB key lives. Production uses [AndroidKeyStoreAdbKeySource]: the key is generated
 * inside AndroidKeyStore and never leaves it. The interface lets the TLS and adb-format code be
 * tested on the JVM with a software key, because Robolectric has no AndroidKeyStore.
 */
interface AdbKeySource {

    /** True when a key is held. Must be cheap: screens poll it. */
    fun exists(): Boolean

    /** The held key, or null when none is held. Throws [AdbKeyException] when it cannot be read. */
    fun load(): AdbKeyMaterial?

    /** Generates and stores a new key, replacing any held one. */
    fun generate(): AdbKeyMaterial

    /** Deletes the held key, if any. Never throws. */
    fun delete()
}

class AdbKey(private val material: AdbKeyMaterial, name: String) {

    companion object {

        /** The held key, generating one when none is held. Only pairing may generate. */
        fun loadOrGenerate(source: AdbKeySource, name: String): AdbKey =
            AdbKey(source.load() ?: source.generate(), name)

        /** The held key. Throws [AdbKeyException] when none is held or it cannot be read. */
        fun existing(source: AdbKeySource, name: String): AdbKey =
            AdbKey(
                source.load() ?: throw AdbKeyException("No ADB key is held; pair first", IllegalStateException()),
                name
            )
    }

    /** The public key in adbd's format (`<base64> <name>\0`), as sent at pairing. */
    val adbPublicKey: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        material.publicKey.adbEncoded(name)
    }

    /**
     * The fingerprint adbd and Settings use for this key: MD5 of the decoded key blob, uppercase
     * hex pairs joined by colons (AOSP AdbDebuggingManager.getFingerprints). It names this key in
     * `IAdbManager.unpairDevice`.
     */
    val adbFingerprint: String by lazy(LazyThreadSafetyMode.NONE) {
        adbFingerprintOf(material.publicKey.adbStruct())
    }

    private val keyManager
        get() = object : X509ExtendedKeyManager() {
            private val alias = "key"

            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? {
                Log.d(TAG, "chooseClientAlias: keyType=${keyTypes.contentToString()}, issuers=${issuers?.contentToString()}")
                for (keyType in keyTypes) {
                    if (keyType == "RSA") return alias
                }
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                Log.d(TAG, "getCertificateChain: alias=$alias")
                return if (alias == this.alias) arrayOf(material.certificate) else null
            }

            // An AndroidKeyStore key is a handle with no encoding; it is handed over as is. Conscrypt
            // signs with it through CryptoUpcalls, and TLS 1.3 RSA-PSS reaches Keystore as raw
            // RSA/ECB/NoPadding, which is why the key allows ENCRYPTION_PADDING_NONE (conscrypt #718).
            override fun getPrivateKey(alias: String?): PrivateKey? {
                Log.d(TAG, "getPrivateKey: alias=$alias")
                return if (alias == this.alias) material.privateKey else null
            }

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? {
                return null
            }
        }


    /**
     * A TLS 1.3 context that presents this key as the client certificate and judges adbd's server
     * certificate with [trustManager]: [PairingAdbServerTrustManager] on the pairing port (the
     * pairing code authenticates that session), [PinnedAdbServerTrustManager] on the connect port.
     * Never install it as a process default.
     *
     * The legacy non-TLS `A_AUTH` signer that used to live here is gone (adversary review
     * 2026-10-12, finding 1.1): it signed any token a peer sent, which made the app a signing
     * oracle for a key adbd treats as shell.
     */
    fun sslContext(trustManager: X509TrustManager): SSLContext {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        return sslContext
    }
}

/** The fingerprint of a decoded key blob, computed exactly as AOSP AdbDebuggingManager does. */
internal fun adbFingerprintOf(keyBlob: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(keyBlob)
        .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
const val RSAPublicKey_Size = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    // little-endian integer with padding zeros in the end

    val endcoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var tmp = this.add(BigInteger.ZERO)
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        endcoded[i] = out[1].toInt()
    }
    return endcoded
}

/** The key in Android's mincrypt `RSAPublicKey` layout, before base64. */
private fun RSAPublicKey.adbStruct(): ByteArray {
    // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r30:system/core/libcrypto_utils/android_pubkey.c

    /*
    typedef struct RSAPublicKey {
        uint32_t modulus_size_words; // ANDROID_PUBKEY_MODULUS_SIZE
        uint32_t n0inv; // n0inv = -1 / N[0] mod 2^32
        uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE];
        uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE]; // rr = (2^(rsa_size)) ^ 2 mod N
        uint32_t exponent;
    } RSAPublicKey;
    */

    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())
    return buffer.array()
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    val base64Bytes = Base64.encode(adbStruct(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    val bytes = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(bytes)
    nameBytes.copyInto(bytes, base64Bytes.size)
    return bytes
}
