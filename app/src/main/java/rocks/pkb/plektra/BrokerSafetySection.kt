package rocks.pkb.plektra

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.BrokerAuditLog
import rocks.pkb.plektra.inputmethod.BrokerSettings
import java.util.Date

/**
 * The broker's safety settings and its audit log, below the device setup card.
 *
 * 1. "Turn Wireless debugging off after setup", on by default.
 * 2. Why "Disable adb authorization timeout" should stay off: an unused key then expires after
 *    7 days. Nothing in the app writes that setting.
 * 3. The audit log, newest first, with a clear button.
 */
@Composable
fun BrokerSafetySection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var turnOff by remember { mutableStateOf(BrokerSettings.turnOffWirelessDebuggingAfterSetup(context)) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.broker_wireless_off_after_setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.broker_wireless_off_after_setup_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = turnOff,
                onCheckedChange = { on ->
                    turnOff = on
                    BrokerSettings.setTurnOffWirelessDebuggingAfterSetup(context, on)
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.broker_auth_timeout_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        BrokerAuditLogList()
    }
}

@Composable
private fun BrokerAuditLogList() {
    val context = LocalContext.current
    val version by BrokerAuditLog.version.collectAsState()
    val entries = remember(version) { BrokerAuditLog.entries(context).asReversed() }
    val ok = stringResource(R.string.broker_audit_ok)
    val failed = stringResource(R.string.broker_audit_failed)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.broker_audit_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.broker_audit_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(enabled = entries.isNotEmpty(), onClick = { BrokerAuditLog.clear(context) }) {
            Text(stringResource(R.string.broker_audit_clear))
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.broker_audit_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val dateFormat = remember { DateFormat.getDateFormat(context) }
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    entries.forEach { entry ->
        val at = Date(entry.atMs)
        val line = buildString {
            append(dateFormat.format(at)).append(' ').append(timeFormat.format(at)).append("  ")
            append(entry.kind.name.lowercase()).append(' ').append(entry.id).append("  ")
            append(if (entry.ok) ok else failed)
            if (entry.count > 1) append(" ×").append(entry.count)
            entry.detail?.let { append(" — ").append(it) }
        }
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (entry.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}
