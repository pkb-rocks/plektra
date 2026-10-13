package moe.shizuku.manager.adb

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

private const val TAG = "AdbServerPin"

/**
 * Pins of adbd's TLS server certificate.
 *
 * mDNS does not authenticate the `_adb-tls-connect._tcp` advertiser: any local app can advertise
 * its own port. Without a pin the client would run its commands against whatever answered.
 *
 * A pin is the lowercase hex SHA-256 of the leaf certificate's SubjectPublicKeyInfo.
 * 1. The pairing pin is the cert adbd presented on the pairing port. That session is authenticated
 *    by the pairing code (SPAKE2 is bound to the TLS exporter), so this cert is genuinely adbd's.
 * 2. The connect pin is the cert accepted on the connect port. Every connect must match it.
 *
 * The connect port's certificate is adbd's own key, generated at random once per adbd process
 * (daemon/auth.cpp), while the pairing server runs in system_server, not adbd. So the connect cert
 * is not the pairing cert, and it changes at every reboot; see [PreferenceAdbServerPinStore].
 * Requiring the pairing pin on the connect port cannot work.
 *
 * Trust on first use, per boot: while no connect pin is stored for this boot, the first
 * connect that completes the ADB handshake stores whatever cert it saw. Pairing clears the connect
 * pin and runs the privileged setup straight away, so that first connect happens seconds after an
 * authenticated pairing. After a reboot the first user-started connect pins again; that is the
 * window a fake adbd could win, and it is open only while Wireless debugging is on and the user
 * has just tapped something in the broker screen. Within a boot a different cert is refused.
 */
interface AdbServerPinStore {
    val pairingPin: String?
    val connectPin: String?

    /** A pairing just succeeded against a server presenting [pin]; restart trust on first use. */
    fun recordPairing(pin: String)

    fun storeConnectPin(pin: String)

    fun clear()
}

/**
 * @param bootId identifies the current boot (Settings.Global.BOOT_COUNT in production), or null
 *   when unknown. adbd generates its connect-port TLS key at random once per adbd process (AOSP
 *   packages/modules/adb daemon/auth.cpp, `adbd_auth_tls_handshake`: a static `rsa_pkey` made by
 *   `CreateRSA2048Key()` on first use), so every reboot brings a new certificate. A connect pin
 *   stored in an earlier boot is therefore treated as absent and trust on first use runs again;
 *   within one boot a different certificate is refused. Without this, every reboot would read as
 *   "adbd's certificate changed".
 */
class PreferenceAdbServerPinStore(
    private val preference: SharedPreferences,
    private val bootId: () -> String? = { null },
) : AdbServerPinStore {

    override val pairingPin: String? get() = preference.getString(PAIRING_KEY, null)

    override val connectPin: String?
        get() {
            val pin = preference.getString(CONNECT_KEY, null) ?: return null
            val now = bootId() ?: return pin
            val pinnedIn = preference.getString(CONNECT_BOOT_KEY, null)
            if (pinnedIn != null && pinnedIn != now) {
                Log.w(TAG, "connect pin is from boot $pinnedIn, now boot $now: adbd has a new key; pinning again on first use")
                return null
            }
            return pin
        }

    override fun recordPairing(pin: String) {
        preference.edit(commit = true) {
            putString(PAIRING_KEY, pin)
            remove(CONNECT_KEY)
            remove(CONNECT_BOOT_KEY)
        }
    }

    override fun storeConnectPin(pin: String) {
        val boot = bootId()
        preference.edit(commit = true) {
            putString(CONNECT_KEY, pin)
            if (boot != null) putString(CONNECT_BOOT_KEY, boot) else remove(CONNECT_BOOT_KEY)
        }
    }

    override fun clear() {
        preference.edit(commit = true) {
            remove(PAIRING_KEY)
            remove(CONNECT_KEY)
            remove(CONNECT_BOOT_KEY)
        }
    }

    companion object {
        const val PAIRING_KEY = "adb_server_pin_pairing"
        const val CONNECT_KEY = "adb_server_pin_connect"
        const val CONNECT_BOOT_KEY = "adb_server_pin_connect_boot"
    }
}

/**
 * The connect port presented a certificate that does not match the stored pin: either adbd's
 * identity changed (a reset, a reflash, data cleared) or something else answered. Either way the
 * user has to forget the pairing and pair again.
 */
class AdbServerPinMismatchException(cause: Throwable) :
    IOException("adbd's certificate does not match the pinned one", cause)

object AdbServerPin {

    /** Lowercase hex SHA-256 of [cert]'s SubjectPublicKeyInfo. */
    fun of(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Accepts adbd's connect-port certificate only if it matches the stored connect pin, or, while no
 * connect pin is stored, records it for [commitFirstUse] (see [AdbServerPinStore] for why).
 *
 * The check runs inside the TLS handshake, so a peer that fails it never receives our client
 * certificate or a CertificateVerify signature.
 *
 * Lint's CustomX509TrustManager is suppressed on purpose. adbd's certificate is self-signed, so
 * the platform manager would refuse it outright; trust comes from the stored pin instead. With no
 * pin yet (the first connect after pairing) the certificate is remembered and pinned only once the
 * ADB handshake completes (trust on first use, see the file header); a certificate that does not
 * match a stored pin is refused, and client-certificate checks always throw.
 */
@SuppressLint("CustomX509TrustManager")
class PinnedAdbServerTrustManager(private val pins: AdbServerPinStore) : X509ExtendedTrustManager() {

    @Volatile
    private var firstUsePin: String? = null

    /**
     * True once a certificate was refused for not matching the pin. AdbClient reads it after a
     * failed handshake, so the user is told "adbd's certificate changed" rather than a generic
     * refusal, however the TLS stack wraps the exception.
     */
    @Volatile
    var mismatchSeen = false
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("adbd presented no certificate")
        val seen = AdbServerPin.of(leaf)
        val expected = pins.connectPin
        if (expected != null) {
            if (!MessageDigest.isEqual(seen.toByteArray(), expected.toByteArray())) {
                Log.w(TAG, "adbd certificate does not match the pin (saw $seen); refusing")
                mismatchSeen = true
                throw CertificateException("adbd certificate does not match the stored pin")
            }
            return
        }
        firstUsePin = seen
    }

    /**
     * Call once the ADB handshake has completed. If no connect pin was stored, stores the cert
     * this connection accepted, so every later connect must present the same one.
     */
    fun commitFirstUse() {
        val seen = firstUsePin ?: return
        if (pins.connectPin != null) return
        val pairing = pins.pairingPin
        when {
            pairing == null -> Log.w(TAG, "pinning adbd connect cert $seen on first use (no pairing pin recorded)")
            pairing == seen -> Log.w(TAG, "pinning adbd connect cert $seen; it matches the pairing-port cert")
            else -> Log.w(TAG, "pinning adbd connect cert $seen on first use; it differs from the pairing-port cert $pairing")
        }
        pins.storeConnectPin(seen)
        firstUsePin = null
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The pairing port's trust manager. It accepts any server certificate because the pairing session
 * is authenticated afterwards by the pairing code: SPAKE2 is keyed with the TLS exporter, so a
 * man in the middle cannot complete it. The cert seen here becomes the pairing pin only once the
 * pairing succeeds (AdbPairingClient reads it from the session).
 */
@SuppressLint("CustomX509TrustManager")
object PairingAdbServerTrustManager : X509ExtendedTrustManager() {

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
    }

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
    }

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
