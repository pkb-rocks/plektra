package moe.shizuku.manager.adb

import android.content.Context
import android.content.SharedPreferences
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Small self-contained glue for the vendored Shizuku ADB pairing/connect classes.
 *
 * Replaces Shizuku's `ShizukuSettings` key store with a plain SharedPreferences file and
 * installs the (Conscrypt-only) hidden-API exemption required to reflectively reach
 * `com.android.org.conscrypt.Conscrypt.exportKeyingMaterial` on API 28+.
 */
object EmbeddedAdbInit {

    /**
     * SharedPreferences file for the broker's state: the key marker ([AndroidKeyStoreAdbKeySource]),
     * adbd's pins and the last verdict. MODE_PRIVATE and excluded from every backup.
     */
    const val PREFS_NAME = "embedded_adb"

    /**
     * The name adbd stores after the public key, which Settings > Wireless debugging shows under
     * Paired devices. One word: AdbDebuggingManager splits the stored key on whitespace and shows
     * only the first word after the base64 (adversary review 2026-10-12, finding 1.3a). It is sent
     * at pairing time, so a new name takes effect on the next pairing.
     *
     * Derived from the application id so builds installed side by side stay apart in that list:
     * `plektra` for rocks.pkb.plektra, `plektra-nightly` for its nightly, and so on.
     */
    fun keyName(applicationId: String): String =
        applicationId.removePrefix("rocks.pkb.").replace('.', '-')

    /**
     * The only hidden class the vendored code reflects on (AdbPairingClient.exportKeyingMaterial).
     * Keep the exemption this narrow: it applies to the whole process for its lifetime.
     */
    private const val CONSCRYPT_SIGNATURE_PREFIX = "Lcom/android/org/conscrypt/Conscrypt;"

    @Volatile
    private var exempted = false

    /** Idempotently lift the hidden-API restriction for the Conscrypt class only (safe on API 28+). */
    @JvmStatic
    fun ensure() {
        if (exempted) return
        synchronized(this) {
            if (exempted) return
            try {
                HiddenApiBypass.addHiddenApiExemptions(CONSCRYPT_SIGNATURE_PREFIX)
            } catch (_: Throwable) {
                // Best effort: on some ROMs / API levels this is a no-op or already open.
            }
            exempted = true
        }
    }

    @JvmStatic
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Where the broker's ADB key lives: AndroidKeyStore (finding 1.3d). Tests replace it with a
     * software key, because Robolectric has no AndroidKeyStore.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var keySourceFactory: (Context) -> AdbKeySource = { AndroidKeyStoreAdbKeySource(prefs(it)) }

    @JvmStatic
    fun keySource(context: Context): AdbKeySource = keySourceFactory(context.applicationContext)

    /** adbd's certificate pins, the connect pin scoped to the current boot (adbd's key is per boot). */
    @JvmStatic
    fun pinStore(context: Context): PreferenceAdbServerPinStore {
        val app = context.applicationContext
        return PreferenceAdbServerPinStore(prefs(app)) {
            runCatching {
                android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
                    .toString()
            }.getOrNull()
        }
    }
}
