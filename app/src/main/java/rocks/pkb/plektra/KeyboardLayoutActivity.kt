package rocks.pkb.plektra

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import rocks.pkb.plektra.ui.theme.PlektraTheme

class KeyboardLayoutActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val locale = intent.getStringExtra("locale") ?: return finish()
        setContent {
            PlektraTheme(settingsWindow = true) {
                KeyboardLayoutSettingsScreen(
                    locale = locale,
                    initialLayout = intent.getStringExtra("layout"),
                    pickerMode = true,
                    onBack = { finish() },
                    onLayoutSelected = { language, layout ->
                        setResult(Activity.RESULT_OK, Intent().putExtra("locale", language).putExtra("layout", layout))
                    }
                )
            }
        }
    }
}
