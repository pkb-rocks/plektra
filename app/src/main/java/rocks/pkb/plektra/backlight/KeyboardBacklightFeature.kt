package rocks.pkb.plektra.backlight

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/** Process-wide entry point, so every screen shares one controller and its lock. */
internal object KeyboardBacklightFeature {
    @Volatile
    private var controller: KeyboardBacklightController? = null

    fun controller(context: Context): KeyboardBacklightController {
        controller?.let { return it }
        return synchronized(this) {
            controller ?: run {
                val appContext = context.applicationContext
                KeyboardBacklightController(
                    shell = ShizukuBacklightShell,
                    store = FileBacklightRecoveryStore(File(appContext.noBackupFilesDir, "keyboard_backlight")),
                    binding = { deviceBinding(appContext) }
                ).also { controller = it }
            }
        }
    }

    fun isOffered(context: Context): Boolean = controller(context).isOffered()

    /**
     * ANDROID_ID differs per device, user, signing key and factory reset; the build properties
     * separate devices that would share it. Only the hash is stored.
     */
    @SuppressLint("HardwareIds")
    private fun deviceBinding(context: Context): BacklightDeviceBinding {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val source = listOf(androidId, Build.BRAND, Build.MANUFACTURER, Build.DEVICE, Build.MODEL, Build.HARDWARE)
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return BacklightDeviceBinding(
            deviceKey = digest.joinToString("") { "%02x".format(it) },
            buildFingerprint = Build.FINGERPRINT.orEmpty()
        )
    }
}
