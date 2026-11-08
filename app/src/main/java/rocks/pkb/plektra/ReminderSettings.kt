package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import android.content.Intent
import rocks.pkb.plektra.training.TypingTrainingActivity
import rocks.pkb.plektra.training.TypingTrainingReminderPolicy
import rocks.pkb.plektra.training.TypingTrainingReminderWorker
import rocks.pkb.plektra.update.UpdateReminderInterval
import rocks.pkb.plektra.update.shouldUseGithubUpdateChecks

@Composable
internal fun updateReminderLabel(interval: UpdateReminderInterval): String = stringResource(
    when (interval) {
        UpdateReminderInterval.ONCE -> R.string.update_reminder_once
        UpdateReminderInterval.DAILY -> R.string.update_reminder_daily
        UpdateReminderInterval.EVERY_THREE_DAYS -> R.string.update_reminder_every_3_days
        UpdateReminderInterval.WEEKLY -> R.string.update_reminder_weekly
        UpdateReminderInterval.OFF -> R.string.update_reminder_off
    }
)

/** How often an available update is announced; only where Plektra checks GitHub itself. */
@Composable
internal fun UpdateReminderSetting() {
    val context = LocalContext.current
    if (!shouldUseGithubUpdateChecks(context)) return
    var interval by remember { mutableStateOf(SettingsManager.getUpdateReminderInterval(context)) }
    var choosing by remember { mutableStateOf(false) }
    ReminderRow(
        id = "advanced.update_reminders",
        title = stringResource(R.string.update_reminder_title),
        summary = updateReminderLabel(interval),
        onClick = { choosing = true }
    )
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.update_reminder_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.update_reminder_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UpdateReminderInterval.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                interval = option
                                SettingsManager.setUpdateReminderInterval(context, option)
                                choosing = false
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == interval, onClick = null)
                            Text(updateReminderLabel(option), modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

/** Weekly typing-training reminder on nightly builds, with an immediate off switch. */
@Composable
internal fun TypingTrainingReminderSetting() {
    val context = LocalContext.current
    if (!TypingTrainingReminderPolicy.isAvailable()) return
    var enabled by remember { mutableStateOf(SettingsManager.getTypingTrainingReminderEnabled(context)) }
    fun toggle(value: Boolean) {
        enabled = value
        SettingsManager.setTypingTrainingReminderEnabled(context, value)
        TypingTrainingReminderWorker.schedule(context)
    }
    ReminderRow(
        id = "advanced.typing_training_reminder",
        title = stringResource(R.string.typing_training_reminder_setting_title),
        summary = stringResource(R.string.typing_training_reminder_setting_description),
        onClick = { toggle(!enabled) },
        trailing = { Switch(checked = enabled, onCheckedChange = ::toggle) }
    )
}

/** Invitation to practise and share on the What's new page of nightly builds. */
@Composable
internal fun TypingTrainingWhatsNewCard() {
    val context = LocalContext.current
    if (!TypingTrainingReminderPolicy.isAvailable()) return
    var enabled by remember { mutableStateOf(SettingsManager.getTypingTrainingReminderEnabled(context)) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.typing_training_whats_new_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.typing_training_whats_new_text), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { context.startActivity(Intent(context, TypingTrainingActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.typing_training_whats_new_open)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.typing_training_reminder_setting_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = enabled, onCheckedChange = { value ->
                    enabled = value
                    SettingsManager.setTypingTrainingReminderEnabled(context, value)
                    TypingTrainingReminderWorker.schedule(context)
                })
            }
        }
    }
}

@Composable
private fun ReminderRow(
    id: String,
    title: String,
    summary: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Surface(modifier = Modifier.fillMaxWidth().settingRow(id, onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}
