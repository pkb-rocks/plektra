package rocks.pkb.plektra

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import rocks.pkb.plektra.ui.theme.PlektraTheme

data class SettingLinkRequest(
    val id: String,
    val serial: Long
)

class SettingsActivity : LocalizedComponentActivity() {
    companion object {
        const val EXTRA_DESTINATION = "rocks.pkb.plektra.SETTINGS_DESTINATION"
        const val DESTINATION_CUSTOMIZATION = "customization"
        const val DESTINATION_DEVICE_SYM_LAYER_EDITOR = "device_sym_layer_editor"
        const val DESTINATION_MODIFIERS = "modifiers"
        const val EXTRA_CUSTOMIZATION_DESTINATION = "rocks.pkb.plektra.CUSTOMIZATION_DESTINATION"
        const val CUSTOMIZATION_DESTINATION_VARIATIONS = "variations"
        const val CUSTOMIZATION_DESTINATION_LAUNCHER_SHORTCUTS = "launcher_shortcuts"
        const val CUSTOMIZATION_DESTINATION_APP_ENTER_BEHAVIOR = "app_enter_behavior"
        const val CUSTOMIZATION_DESTINATION_STATUS_BAR_BUTTONS = "status_bar_buttons"
        const val CUSTOMIZATION_DESTINATION_KEYBOARD_THEME = "keyboard_theme"
        const val CUSTOMIZATION_DESTINATION_APP_ICON = "app_icon"
        const val CUSTOMIZATION_DESTINATION_MAIN_BACKGROUND = "main_background"
        const val EXTRA_KEYBOARD_THEME_TARGET = "rocks.pkb.plektra.KEYBOARD_THEME_TARGET"
        const val KEYBOARD_THEME_TARGET_SOFTWARE = "software"
    }

    // A request is an event, not persistent screen state: the serial lets the same
    // link fire again via onNewIntent, while restored activities do not replay it.
    private val settingLinkRequest = mutableStateOf<SettingLinkRequest?>(null)
    private var nextSettingLinkSerial = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.putSettingsPage(intent.settingsPage())
        enableEdgeToEdge()
        settingLinkRequest.value = if (savedInstanceState == null) {
            resolveSettingLinkId(intent)?.let(::newSettingLinkRequest)
        } else {
            null
        }
        setContent {
            PlektraTheme(settingsWindow = true) {
                val linkRequest by settingLinkRequest
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    SettingsAdaptiveRoot(
                        initialDestination = intent.getStringExtra(EXTRA_DESTINATION),
                        initialCustomizationDestination = intent.getStringExtra(EXTRA_CUSTOMIZATION_DESTINATION),
                        initialKeyboardThemeTarget = intent.getStringExtra(EXTRA_KEYBOARD_THEME_TARGET),
                        settingLinkRequest = linkRequest
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveSettingLinkId(intent)?.let { linkId ->
            settingLinkRequest.value = newSettingLinkRequest(linkId)
        }
    }

    private fun newSettingLinkRequest(id: String): SettingLinkRequest =
        SettingLinkRequest(id = id, serial = ++nextSettingLinkSerial)

    private fun resolveSettingLinkId(intent: Intent?): String? =
        intent?.data?.let(SettingLinkRegistry::parseSettingLinkUri)

}
