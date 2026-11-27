package rocks.pkb.plektra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import rocks.pkb.plektra.ui.theme.PlektraTheme

class LanguagesActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlektraTheme(settingsWindow = true) {
                LanguagesScreen(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

}
