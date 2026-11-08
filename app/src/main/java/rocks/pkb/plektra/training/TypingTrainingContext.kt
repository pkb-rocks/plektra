package rocks.pkb.plektra.training

import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.WindowManager
import kotlinx.serialization.Serializable
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import kotlin.math.hypot

/** Everything needed to attribute a session to a device, keyboard and configuration later. */
@Serializable
data class TypingTrainingContext(
    val capturedAtEpochMs: Long,
    val build: Map<String, String>,
    val app: Map<String, String>,
    val plektraDevice: Map<String, String>,
    val display: Map<String, String>,
    val inputDevices: List<Map<String, String>>,
    /** Plektra settings from [shareableSettings]; values flattened to strings. */
    val settings: Map<String, String>
) {
    companion object {
        private val secretKey = Regex("(api[_-]?key|token|secret|password|credential|auth)", RegexOption.IGNORE_CASE)

        /** Settings that hold the user's own text, words, files or apps rather than configuration. */
        private val personalKeys = setOf(
            "snippets_v1",
            "user_dictionary_entries",
            "launcher_shortcuts",
            "quick_launcher_command_customizations",
            "app_enter_behavior_overrides",
            "app_raw_mode_packages",
            "typing_sound_custom_file_name",
            "typing_sound_custom_display_name",
            "clicks_red_button_binding_output",
            "clicks_keyboard_button_binding_output",
            "clicks_microphone_button_binding_output"
        )
        private val personalPrefixes = listOf("auto_correct_custom_")

        /**
         * Only registered settings (the backup contract) are shared, so new or unknown keys stay
         * private until someone decides otherwise; personal content and secret-like keys never.
         */
        internal fun shareableSettings(all: Map<String, *>): Map<String, String> = all
            .filterKeys { key ->
                BackupPreferenceContract.isExportable(PLEKTRA_PREFS, key) &&
                    key !in personalKeys &&
                    personalPrefixes.none(key::startsWith) &&
                    !secretKey.containsMatchIn(key)
            }
            .mapValues { (_, value) ->
                when (value) {
                    is Set<*> -> value.map { it.toString() }.sorted().joinToString(prefix = "[", postfix = "]")
                    else -> value.toString()
                }
            }
            .toSortedMap()

        private const val PLEKTRA_PREFS = "plektra_prefs"

        fun capture(context: Context): TypingTrainingContext {
            val metrics = context.resources.displayMetrics
            val configuration = context.resources.configuration
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java)?.defaultDisplay
            val identity = DeviceSpecific.detectedDeviceIdentity()
            return TypingTrainingContext(
                capturedAtEpochMs = System.currentTimeMillis(),
                build = buildMap {
                    put("brand", Build.BRAND)
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("device", Build.DEVICE)
                    put("product", Build.PRODUCT)
                    put("hardware", Build.HARDWARE)
                    put("board", Build.BOARD)
                    put("fingerprint", Build.FINGERPRINT)
                    put("display", Build.DISPLAY)
                    put("release", Build.VERSION.RELEASE ?: "")
                    put("sdk", Build.VERSION.SDK_INT.toString())
                    put("securityPatch", Build.VERSION.SECURITY_PATCH ?: "")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        put("socManufacturer", Build.SOC_MANUFACTURER)
                        put("socModel", Build.SOC_MODEL)
                        put("sku", Build.SKU)
                    }
                },
                app = mapOf(
                    "package" to context.packageName,
                    "versionName" to BuildConfig.VERSION_NAME,
                    "versionCode" to BuildConfig.VERSION_CODE.toString(),
                    "buildType" to BuildConfig.BUILD_TYPE,
                    "flavor" to BuildConfig.FLAVOR,
                    "releaseChannel" to BuildConfig.RELEASE_CHANNEL,
                    // Empty when the build had no git checkout.
                    "gitCommit" to BuildConfig.GIT_COMMIT,
                    // "true", "false" or "unknown".
                    "gitDirty" to BuildConfig.GIT_DIRTY
                ),
                plektraDevice = mapOf(
                    "stableId" to (identity.stableId ?: ""),
                    "displayName" to identity.displayName,
                    "physicalKeyboard" to DeviceSpecific.physicalKeyboardName(),
                    "keyboardFamily" to DeviceSpecific.keyboardName(),
                    "profileOverride" to SettingsManager.getPhysicalKeyboardProfileOverride(context).toString()
                ),
                display = buildMap {
                    put("widthPx", metrics.widthPixels.toString())
                    put("heightPx", metrics.heightPixels.toString())
                    put("densityDpi", metrics.densityDpi.toString())
                    put("density", metrics.density.toString())
                    put("xdpi", metrics.xdpi.toString())
                    put("ydpi", metrics.ydpi.toString())
                    put("diagonalInches", "%.2f".format(hypot(metrics.widthPixels / metrics.xdpi, metrics.heightPixels / metrics.ydpi)))
                    put("fontScale", configuration.fontScale.toString())
                    put("screenWidthDp", configuration.screenWidthDp.toString())
                    put("screenHeightDp", configuration.screenHeightDp.toString())
                    put("smallestScreenWidthDp", configuration.smallestScreenWidthDp.toString())
                    put("uiMode", configuration.uiMode.toString())
                    put("hardKeyboardHidden", configuration.hardKeyboardHidden.toString())
                    put("keyboard", configuration.keyboard.toString())
                    display?.let {
                        put("displayId", it.displayId.toString())
                        put("refreshRate", it.refreshRate.toString())
                        put("rotation", it.rotation.toString())
                    }
                },
                inputDevices = InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice)
                    .filter { device ->
                        (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD ||
                            device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE
                    }
                    .map { device ->
                        mapOf(
                            "id" to device.id.toString(),
                            "name" to (device.name ?: ""),
                            "descriptor" to (device.descriptor ?: ""),
                            "vendorId" to device.vendorId.toString(),
                            "productId" to device.productId.toString(),
                            "keyboardType" to device.keyboardType.toString(),
                            "sources" to "0x" + Integer.toHexString(device.sources),
                            "external" to device.isExternal.toString(),
                            "virtual" to device.isVirtual.toString()
                        )
                    },
                settings = shareableSettings(SettingsManager.getPreferences(context).all)
            )
        }
    }
}
