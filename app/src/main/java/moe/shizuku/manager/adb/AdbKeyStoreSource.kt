package moe.shizuku.manager.adb

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.core.content.edit
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.cert.X509Certificate
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

private const val TAG = "AdbKeyStoreSource"

/**
 * The ADB key, generated inside AndroidKeyStore and never exported.
 *
 * Shizuku generates the RSA key in the heap and keeps it AES-GCM-wrapped in preferences, so every
 * connect decrypts it into the Java heap and a compromised process can copy it off the phone and
 * use it from any machine that can reach adbd. A Keystore key is a handle: a compromised process
 * can still use it on this phone, but cannot take it away.
 *
 * Key parameters:
 * 1. RSA-2048, F4, PURPOSE_SIGN; digests NONE and SHA256; signature paddings PKCS1 and PSS.
 * 2. Encryption padding NONE as well. Conscrypt signs TLS 1.3's RSA-PSS CertificateVerify through
 *    CryptoUpcalls, which reaches Keystore as raw `RSA/ECB/NoPadding` with the private key (mapped
 *    to a raw SIGN by AndroidKeyStoreRSACipherSpi). Without PADDING_NONE in the key's
 *    authorisations the handshake fails (conscrypt #718). The key has no ENCRYPT/DECRYPT purpose.
 * 3. StrongBox first, the TEE when StrongBox is unavailable or refuses the parameters.
 * 4. Unlocked-device-required from Android 15 on. Before 15, such keys cannot be generated or used
 *    without a secure lock screen and are deleted when the lock screen is removed (documented in
 *    KeyGenParameterSpec.Builder#setUnlockedDeviceRequired), which would silently break pairing.
 * 5. The self-signed certificate is Keystore's own (subject CN=00, as before). adbd checks only the
 *    public key it carries.
 *
 * A marker in the broker's preferences records that a key is held and its backing, so the cheap
 * [exists] (polled by screens) needs no Keystore binder call.
 */
class AndroidKeyStoreAdbKeySource(private val prefs: SharedPreferences) : AdbKeySource {

    companion object {
        const val ALIAS = "plektra_adb_key"

        /** Present only while a key is held; its value is the key's backing. */
        const val MARKER_PREF = "adb_keystore_key"

        const val BACKING_STRONGBOX = "strongbox"
        const val BACKING_TEE = "tee"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** 2048-01-01, the same end date the Shizuku certificate used. */
        private const val NOT_AFTER_MS = 2461449600L * 1000

        /** The generation spec, kept apart so its flags can be read back in a test. */
        fun keySpec(strongBox: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    KeyProperties.SIGNATURE_PADDING_RSA_PSS
                )
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setCertificateSubject(X500Principal("CN=00"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0))
                .setCertificateNotAfter(Date(NOT_AFTER_MS))
                .setUnlockedDeviceRequired(sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
                .setIsStrongBoxBacked(strongBox)
                .build()
    }

    /** "strongbox" or "tee" while a key is held, else null. */
    val backing: String? get() = prefs.getString(MARKER_PREF, null)

    override fun exists(): Boolean = prefs.contains(MARKER_PREF)

    override fun load(): AdbKeyMaterial? {
        if (!exists()) return null
        try {
            val entry = keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: throw AdbKeyException("ADB key is missing from Keystore; pair again", IllegalStateException())
            val cert = entry.certificate as? X509Certificate
                ?: throw AdbKeyException("ADB key has no certificate; pair again", IllegalStateException())
            return AdbKeyMaterial(entry.privateKey, cert)
        } catch (e: GeneralSecurityException) {
            throw AdbKeyException("Stored ADB key could not be read; pair again", e)
        } catch (e: IOException) {
            throw AdbKeyException("Stored ADB key could not be read; pair again", e)
        }
    }

    override fun generate(): AdbKeyMaterial {
        delete()
        val backing = try {
            generateKeyPair(strongBox = true)
            BACKING_STRONGBOX
        } catch (e: StrongBoxUnavailableException) {
            Log.w(TAG, "StrongBox unavailable; generating the ADB key in the TEE")
            generateKeyPair(strongBox = false)
            BACKING_TEE
        } catch (e: ProviderException) {
            // Some StrongBox implementations refuse a parameter (PSS, DIGEST_NONE) rather than
            // reporting themselves unavailable. The TEE is still hardware-backed and non-exportable.
            Log.w(TAG, "StrongBox refused the ADB key; generating it in the TEE", e)
            generateKeyPair(strongBox = false)
            BACKING_TEE
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "StrongBox refused the ADB key; generating it in the TEE", e)
            generateKeyPair(strongBox = false)
            BACKING_TEE
        }
        prefs.edit(commit = true) { putString(MARKER_PREF, backing) }
        return load() ?: throw AdbKeyException("Generated ADB key could not be read", IllegalStateException())
    }

    override fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
        prefs.edit(commit = true) { remove(MARKER_PREF) }
    }

    private fun generateKeyPair(strongBox: Boolean) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(keySpec(strongBox))
        generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}

/**
 * Before the key moved into AndroidKeyStore it was a heap RSA key, AES-GCM-wrapped under the
 * Keystore AES key [LEGACY_WRAPPING_ALIAS] and stored as [LEGACY_KEY_PREF]. Such a key cannot be moved into
 * Keystore as non-exportable without first being decrypted in the heap, so it is discarded and the
 * user pairs again. Its pins describe that pairing and go with it. The old key stays in adbd's
 * list until it expires (7 days unused) or the user forgets it in Settings.
 */
object LegacyAdbKey {
    const val LEGACY_KEY_PREF = "adbkey"
    private const val LEGACY_WRAPPING_ALIAS = "_adbkey_encryption_key_"

    /** Drops a legacy wrapped key, if present. Returns true when one was dropped. Never throws. */
    fun discard(prefs: SharedPreferences): Boolean {
        if (!prefs.contains(LEGACY_KEY_PREF)) return false
        prefs.edit(commit = true) {
            remove(LEGACY_KEY_PREF)
            remove(PreferenceAdbServerPinStore.PAIRING_KEY)
            remove(PreferenceAdbServerPinStore.CONNECT_KEY)
            remove(PreferenceAdbServerPinStore.CONNECT_BOOT_KEY)
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LEGACY_WRAPPING_ALIAS)
        }
        Log.w(TAG, "Discarded the legacy wrapped ADB key; pair again")
        return true
    }
}
