package rocks.pkb.plektra

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import rocks.pkb.plektra.clipboard.ClipboardImageLimits

/** Everything the clipboard history and Plektra's paste shortcuts can be configured with. */
@Composable
fun ClipboardSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var historyEnabled by remember { mutableStateOf(SettingsManager.getClipboardHistoryEnabled(context)) }
    var imageHistoryEnabled by remember { mutableStateOf(SettingsManager.getClipboardImageHistoryEnabled(context)) }
    var retentionTime by remember { mutableStateOf(SettingsManager.getClipboardRetentionTime(context)) }
    var sensitiveRetentionTime by remember { mutableStateOf(SettingsManager.getSensitiveClipboardRetentionTime(context)) }
    var pastePlainText by remember { mutableStateOf(SettingsManager.getClipboardPastePlainText(context)) }
    var ctrlShiftVPlainText by remember { mutableStateOf(SettingsManager.getClipboardCtrlShiftVPlainText(context)) }

    // The clipboard panel changes some of these while this screen may be open.
    DisposableEffect(context) {
        val preferences = SettingsManager.getPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            historyEnabled = SettingsManager.getClipboardHistoryEnabled(context)
            imageHistoryEnabled = SettingsManager.getClipboardImageHistoryEnabled(context)
            retentionTime = SettingsManager.getClipboardRetentionTime(context)
            sensitiveRetentionTime = SettingsManager.getSensitiveClipboardRetentionTime(context)
            pastePlainText = SettingsManager.getClipboardPastePlainText(context)
            ctrlShiftVPlainText = SettingsManager.getClipboardCtrlShiftVPlainText(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
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
                        text = stringResource(R.string.clipboard_settings_title),
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
            ClipboardSwitchSetting(
                linkId = SettingLinkIds.CLIPBOARD_HISTORY_ENABLED,
                title = stringResource(R.string.clipboard_history_enabled_title),
                description = stringResource(R.string.clipboard_history_enabled_description),
                checked = historyEnabled,
                onCheckedChange = {
                    historyEnabled = it
                    SettingsManager.setClipboardHistoryEnabled(context, it)
                }
            )
            val limits = ClipboardImageLimits.DEFAULT
            ClipboardSwitchSetting(
                linkId = SettingLinkIds.CLIPBOARD_IMAGE_HISTORY,
                title = stringResource(R.string.clipboard_image_history_title),
                description = stringResource(
                    R.string.clipboard_image_history_description,
                    limits.maxImages,
                    limits.maxImageMegabytes(),
                    limits.maxTotalMegabytes()
                ),
                checked = imageHistoryEnabled,
                onCheckedChange = {
                    imageHistoryEnabled = it
                    SettingsManager.setClipboardImageHistoryEnabled(context, it)
                }
            )
            ClipboardRetentionSetting(
                title = stringResource(R.string.clipboard_retention_time_title),
                description = stringResource(R.string.clipboard_retention_time_description),
                value = retentionTime,
                options = listOf(
                    0L to stringResource(R.string.clipboard_retention_forever),
                    1440L to stringResource(R.string.clipboard_retention_24_hours),
                    10080L to stringResource(R.string.clipboard_retention_days, 7),
                    43200L to stringResource(R.string.clipboard_retention_days, 30),
                    129600L to stringResource(R.string.clipboard_retention_days, 90)
                ),
                onSelected = { SettingsManager.setClipboardRetentionTime(context, it) },
                modifier = Modifier.settingRow(SettingLinkIds.CLIPBOARD_RETENTION_TIME)
            )
            ClipboardRetentionSetting(
                title = stringResource(R.string.clipboard_sensitive_retention_title),
                description = stringResource(R.string.clipboard_sensitive_retention_description),
                value = sensitiveRetentionTime,
                options = listOf(5L, 15L, 30L, 60L, 120L).map {
                    it to stringResource(R.string.clipboard_retention_time_minutes, it)
                },
                onSelected = { SettingsManager.setSensitiveClipboardRetentionTime(context, it) },
                modifier = Modifier.settingRow(SettingLinkIds.CLIPBOARD_SENSITIVE_RETENTION_TIME)
            )
            ClipboardSwitchSetting(
                linkId = SettingLinkIds.CLIPBOARD_PASTE_PLAIN_TEXT,
                title = stringResource(R.string.clipboard_paste_plain_text_setting_title),
                description = stringResource(R.string.clipboard_paste_plain_text_setting_description),
                checked = pastePlainText,
                onCheckedChange = {
                    pastePlainText = it
                    SettingsManager.setClipboardPastePlainText(context, it)
                }
            )
            ClipboardSwitchSetting(
                linkId = SettingLinkIds.CLIPBOARD_CTRL_SHIFT_V_PLAIN_TEXT,
                title = stringResource(R.string.clipboard_ctrl_shift_v_title),
                description = stringResource(R.string.clipboard_ctrl_shift_v_description),
                checked = ctrlShiftVPlainText,
                onCheckedChange = {
                    ctrlShiftVPlainText = it
                    SettingsManager.setClipboardCtrlShiftVPlainText(context, it)
                }
            )
        }
    }
}

@Composable
private fun ClipboardSwitchSetting(
    linkId: String,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().settingRow(linkId)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ClipboardRetentionSetting(
    title: String,
    description: String,
    value: Long,
    options: List<Pair<Long, String>>,
    onSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second
        ?: stringResource(R.string.clipboard_retention_time_minutes, value)
    Surface(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(selectedLabel) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (minutes, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            onSelected(minutes)
                            expanded = false
                        })
                    }
                }
            }
        }
    }
}
