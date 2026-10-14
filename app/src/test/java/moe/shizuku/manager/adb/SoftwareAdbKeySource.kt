package moe.shizuku.manager.adb

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import java.util.Locale

/**
 * A software stand-in for [AndroidKeyStoreAdbKeySource]: Robolectric has no AndroidKeyStore. Its
 * private key is wrapped in [OpaqueRsaPrivateKey], so a test fails if anything reads the encoding,
 * as it would get null from a Keystore handle.
 */
class SoftwareAdbKeySource : AdbKeySource {
    var material: AdbKeyMaterial? = null
    var generated = 0
    var failLoad = false

    override fun exists(): Boolean = material != null

    override fun load(): AdbKeyMaterial? {
        if (failLoad) throw AdbKeyException("broken", IllegalStateException())
        return material
    }

    override fun generate(): AdbKeyMaterial {
        generated++
        return newMaterial().also { material = it }
    }

    override fun delete() {
        material = null
    }

    companion object {
        fun selfSigned(keyPair: KeyPair, cn: String = "00"): X509Certificate {
            val holder = X509v3CertificateBuilder(
                X500Name("CN=$cn"), BigInteger.ONE, Date(0), Date(4_000_000_000_000L), Locale.ROOT,
                X500Name("CN=$cn"), SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
        }

        fun newMaterial(): AdbKeyMaterial {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return AdbKeyMaterial(OpaqueRsaPrivateKey(keyPair.private as RSAPrivateKey), selfSigned(keyPair))
        }
    }
}

/**
 * An RSA private key with no encoding, like an AndroidKeyStore handle (whose getEncoded() is null).
 * The JDK's RSA signer can still use it through the modulus and exponent; Keystore's cannot be
 * imitated on the JVM, so only a device proves the real handle signs.
 */
class OpaqueRsaPrivateKey(private val inner: RSAPrivateKey) : RSAPrivateKey {
    override fun getAlgorithm(): String = "RSA"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
    override fun getModulus(): BigInteger = inner.modulus
    override fun getPrivateExponent(): BigInteger = inner.privateExponent
}
