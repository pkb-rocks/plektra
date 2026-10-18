package rocks.pkb.plektra

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private fun terminalAppLabel(context: Context, packageName: String): String =
    runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    }.getOrDefault(packageName)

/**
 * The apps where the screen trackpad also works with no text field in focus (terminals, SSH
 * clients). Same shape as the other per-app lists: an app picker to add, a button to remove.
 */
@Composable
internal fun ScreenTrackpadTerminalAppsSection(enabled: Boolean) {
    val context = LocalContext.current
    var packages by remember { mutableStateOf(SettingsManager.getScreenTrackpadTerminalApps(context)) }
    var showPicker by remember { mutableStateOf(false) }
    val rows = remember(packages) {
        packages.map { it to terminalAppLabel(context, it) }.sortedBy { it.second.lowercase() }
    }

    Column(modifier = Modifier.fillMaxWidth().settingRow(SettingLinkIds.SCREEN_TRACKPAD_TERMINAL_APPS)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.screen_trackpad_terminal_apps_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.screen_trackpad_terminal_apps_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showPicker = true }, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.app_enter_behaviour_add_app)
                )
            }
        }

        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.screen_trackpad_terminal_apps_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        rows.forEach { (packageName, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    enabled = enabled,
                    onClick = {
                        SettingsManager.setScreenTrackpadTerminalApp(context, packageName, false)
                        packages = SettingsManager.getScreenTrackpadTerminalApps(context)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.app_enter_behaviour_remove_app)
                    )
                }
            }
        }

        TextButton(
            enabled = enabled,
            onClick = {
                SettingsManager.resetScreenTrackpadTerminalApps(context)
                packages = SettingsManager.getScreenTrackpadTerminalApps(context)
            },
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(stringResource(R.string.screen_trackpad_terminal_apps_reset))
        }
    }

    if (showPicker) {
        AppPickerDialog(
            onAppSelected = { app ->
                SettingsManager.setScreenTrackpadTerminalApp(context, app.packageName, true)
                packages = SettingsManager.getScreenTrackpadTerminalApps(context)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}
