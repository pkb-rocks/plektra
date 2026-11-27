package rocks.pkb.plektra

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AppLanguageSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            PlektraSettingsHeader(title = stringResource(R.string.app_language_title), onBack = onBack)
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AppLanguageSelectorCard()
        }
    }
}

fun currentAppLanguageLabel(context: Context): String {
    val tag = getCurrentAppLanguageTag(context)
    // The language in its own words; System default stays as the plain choice name.
    return tag?.let { appLanguageChoiceLabels(context, it, context.resources.configuration.locales[0]).first }
        ?: context.getString(R.string.app_language_system_default)
}
