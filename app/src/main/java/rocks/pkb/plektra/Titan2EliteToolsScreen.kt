package rocks.pkb.plektra

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Tools that only make sense on the Titan 2 Elite and that reach into the phone itself.
 *
 * Reached from the main settings list, where the row is shown only on that device
 * ([titan2EliteToolsSettingEntries] registers it with a fallback to Advanced).
 *
 * The screen opens with the device setup card: the one-time wireless-debugging pairing that the
 * embedded ADB broker needs, and the broker's verified state. Anything below it can assume that
 * card is the place to send a user whose privileged step cannot run.
 */
@Composable
fun Titan2EliteToolsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    // a toolbox page opens as a child settings activity (ToolboxSection.kt) and
    // replaces this list while it is showing.
    val toolboxPage = ToolboxPage.fromChild(settingsChild(LocalContext.current, ToolboxPage.SECTION))
    if (toolboxPage != null) {
        ToolboxPageHost(page = toolboxPage, modifier = modifier, onBack = onBack)
        return
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description)
                        )
                    }
                    Text(
                        text = stringResource(R.string.titan2_elite_tools_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.titan2_elite_tools_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            DeviceSetupCard(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_DEVICE_SETUP))

            // Broker safety: Wireless debugging off after setup, the key-expiry advice, the audit log.
            BrokerSafetySection()

            // bloat remover, density, system tweaks, key mapping, Fn -> Ctrl, status bar, reset
            ToolboxSection()

            // add one section per feature below, each a composable in its own file
            // (backlight, toolbox, ...). Register its settings in
            // Titan2EliteToolsSettingEntries.kt so search and deep links reach it.

            // smart keyboard backlight
            SmartBacklightSection(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_SMART_BACKLIGHT))
        }
    }
}
