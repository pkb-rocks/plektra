package moe.shizuku.manager.adb

import android.content.Context
import android.os.Build
import android.security.keystore.KeyProperties
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import java.security.MessageDigest
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64

/** The Keystore key and the key name, on the JVM with a software key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdbKeyTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val source = SoftwareAdbKeySource()
    private val savedFactory = EmbeddedAdbInit.keySourceFactory

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        EmbeddedAdbInit.keySourceFactory = { source }
    }

    @After
    fun tearDown() {
        EmbeddedAdbInit.keySourceFactory = savedFactory
    }

    /** AOSP AdbDebuggingManager.getFingerprints, ported verbatim so the two can be compared. */
    private fun aospFingerprint(key: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(Base64.getMimeDecoder().decode(key.split(Regex("\\s+"))[0]))
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder()
        for (i in digest.indices) {
            sb.append(hex[(digest[i].toInt() shr 4) and 0xf])
            sb.append(hex[digest[i].toInt() and 0xf])
            if (i < digest.size - 1) sb.append(":")
        }
        return sb.toString()
    }

    @Test
    fun keyNameIsDerivedFromTheApplicationIdAndIsOneWord() {
        assertEquals("plektra", EmbeddedAdbInit.keyName("rocks.pkb.plektra"))
        assertEquals("plektra-nightly", EmbeddedAdbInit.keyName("rocks.pkb.plektra.nightly"))
        val name = EmbeddedAdbInit.keyName("rocks.pkb.plektra")
        assertFalse(name.any { it.isWhitespace() })

        val key = AdbKey(SoftwareAdbKeySource.newMaterial(), name)
        val text = String(key.adbPublicKey)
        assertTrue(text, text.endsWith(" plektra\u0000"))
        // What AdbDebuggingManager.getPairedDevices shows: args[1] after splitting on whitespace.
        assertEquals("plektra\u0000", text.split(Regex("\\s+"))[1])
    }

    @Test
    fun publicKeyIsTheMincryptBlobOfTheCertificatesKey() {
        val material = SoftwareAdbKeySource.newMaterial()
        val key = AdbKey(material, "n")
        val blob = Base64.getDecoder().decode(String(key.adbPublicKey).substringBefore(' '))
        assertEquals(RSAPublicKey_Size, blob.size)
        // The exponent is the last word, little-endian.
        val exponent = java.nio.ByteBuffer.wrap(blob, blob.size - 4, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(material.publicKey.publicExponent.toInt(), exponent)
    }

    @Test
    fun fingerprintMatchesWhatAdbdComputes() {
        val material = SoftwareAdbKeySource.newMaterial()
        val key = AdbKey(material, KEY_NAME)
        val adbdKey = String(key.adbPublicKey).trimEnd('\u0000')
        assertEquals(aospFingerprint(adbdKey), key.adbFingerprint)
        assertTrue(key.adbFingerprint, Regex("^([0-9A-F]{2}:){15}[0-9A-F]{2}$").matches(key.adbFingerprint))
        // The name is not part of the fingerprint.
        assertEquals(key.adbFingerprint, AdbKey(material, "other").adbFingerprint)
    }

    @Test
    fun differentKeysHaveDifferentFingerprints() {
        val a = AdbKey(SoftwareAdbKeySource.newMaterial(), "n").adbFingerprint
        val b = AdbKey(SoftwareAdbKeySource.newMaterial(), "n").adbFingerprint
        assertNotEquals(a, b)
    }

    @Test
    fun existingNeverGenerates() {
        try {
            AdbKey.existing(source, KEY_NAME)
            fail("existing() must not mint a key")
        } catch (_: AdbKeyException) {
        }
        assertEquals(0, source.generated)
        assertFalse(source.exists())
    }

    @Test
    fun loadOrGenerateMintsOnceThenReusesTheKey() {
        val first = AdbKey.loadOrGenerate(source, "n").adbPublicKey
        val second = AdbKey.loadOrGenerate(source, "n").adbPublicKey
        assertEquals(1, source.generated)
        assertArrayEquals(first, second)
    }

    @Test
    fun theKeyIsNeverReadAsBytes() {
        // A Keystore handle's encoding is null; the software stand-in returns null too.
        val material = SoftwareAdbKeySource.newMaterial()
        assertEquals(null, material.privateKey.encoded)
        AdbKey(material, "n").adbFingerprint
    }

    @Test
    fun legacyWrappedKeyIsDiscardedAndTheInstallReadsAsNotPaired() {
        val prefs = EmbeddedAdbInit.prefs(context)
        prefs.edit()
            .putString(LegacyAdbKey.LEGACY_KEY_PREF, "AAAA")
            .putString(PreferenceAdbServerPinStore.PAIRING_KEY, "aa")
            .putString(PreferenceAdbServerPinStore.CONNECT_KEY, "bb")
            .commit()

        assertFalse(EmbeddedAdbShell.isPaired(context))
        assertFalse(prefs.contains(LegacyAdbKey.LEGACY_KEY_PREF))
        assertFalse(prefs.contains(PreferenceAdbServerPinStore.PAIRING_KEY))
        assertFalse(prefs.contains(PreferenceAdbServerPinStore.CONNECT_KEY))
        assertFalse(LegacyAdbKey.discard(prefs))
    }

    @Test
    fun pairedMeansTheSourceHoldsAKeyAndForgetDeletesIt() {
        source.generate()
        assertTrue(EmbeddedAdbShell.isPaired(context))
        EmbeddedAdbShell.forgetPairing(context)
        assertFalse(EmbeddedAdbShell.isPaired(context))
        assertFalse(source.exists())
    }

    @Test
    fun keystoreSpecHasTheFlagsTlsThirteenNeeds() {
        val spec = AndroidKeyStoreAdbKeySource.keySpec(strongBox = true, sdkInt = 36)
        assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
        assertEquals(setOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256), spec.digests.toSet())
        assertEquals(
            setOf(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS),
            spec.signaturePaddings.toSet()
        )
        // conscrypt #718: TLS 1.3 RSA-PSS reaches Keystore as raw RSA with no padding.
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings.toList())
        val rsa = spec.algorithmParameterSpec as RSAKeyGenParameterSpec
        assertEquals(2048, rsa.keysize)
        assertEquals(RSAKeyGenParameterSpec.F4, rsa.publicExponent)
        assertTrue(spec.isStrongBoxBacked)
        assertTrue(spec.isUnlockedDeviceRequired)
        assertEquals(AndroidKeyStoreAdbKeySource.ALIAS, spec.keystoreAlias)

        assertFalse(AndroidKeyStoreAdbKeySource.keySpec(strongBox = false, sdkInt = 36).isStrongBoxBacked)
        // Before Android 15 the flag breaks phones without a secure lock screen.
        assertFalse(
            AndroidKeyStoreAdbKeySource.keySpec(strongBox = false, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                .isUnlockedDeviceRequired
        )
    }

    private companion object {
        const val KEY_NAME = "plektra"
    }
}
