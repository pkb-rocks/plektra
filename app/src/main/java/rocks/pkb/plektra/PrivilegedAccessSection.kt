package rocks.pkb.plektra

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rocks.pkb.plektra.inputmethod.PrivilegedAccessMode
import rocks.pkb.plektra.inputmethod.PrivilegedBackend
import rocks.pkb.plektra.inputmethod.PrivilegedBackends
import rocks.pkb.plektra.inputmethod.ShizukuState
import rocks.pkb.plektra.shizuku.ShizukuUserServiceShell
import rocks.pkb.plektra.ui.BrokerStatusMonitor

/**
 * Shizuku echoes this back with the user's answer; any constant of ours will do. Shared with
 * [PrivilegedBlockedNotice], whose Allow button asks the same question.
 */
internal const val SHIZUKU_PERMISSION_REQUEST = 4_212

/**
 * "Privileged access via" on the Titan 2 Elite tools screen, under the
 * device setup card. Automatic (Shizuku when it is running and Plektra is allowed, otherwise the
 * built-in broker), Shizuku only, or Built-in broker only, with Shizuku's status and, when
 * Shizuku runs but has not allowed Plektra, an Allow button that shows Shizuku's own prompt.
 *
 * Choosing a route changes nothing on the phone; the commands still go through the broker's door
 * (EmbeddedAdbShell.execute) and its checks whichever route is chosen.
 */
@Composable
fun PrivilegedAccessSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(PrivilegedBackends.mode(context)) }
    var shizukuState by remember { mutableStateOf(PrivilegedBackends.shizukuState(context)) }

    // Shizuku can start, stop or allow Plektra while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            shizukuState = PrivilegedBackends.shizukuState(context)
        }
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
                shizukuState = PrivilegedBackends.shizukuState(context)
                BrokerStatusMonitor.invalidate()
            }
        }
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(listener) } }
    }

    val backend = PrivilegedBackends.choose(mode, shizukuState)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.privileged_access_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.privileged_access_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        Column(modifier = Modifier.selectableGroup()) {
            PrivilegedAccessMode.entries.forEach { option ->
                ModeRow(
                    label = stringResource(option.labelRes()),
                    selected = option == mode,
                    onSelect = {
                        if (option != mode) {
                            mode = option
                            SettingsManager.setPrivilegedAccessBackend(context, option.prefValue)
                            // The shared verdict was about the old route; re-check through the new one.
                            BrokerStatusMonitor.invalidate()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(shizukuState.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = if (shizukuState == ShizukuState.READY) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (shizukuState == ShizukuState.NOT_ALLOWED) {
                TextButton(onClick = {
                    // A tap only: Shizuku shows its own prompt and reports back to the listener.
                    ShizukuUserServiceShell.requestPermission(SHIZUKU_PERMISSION_REQUEST)
                }) {
                    Text(stringResource(R.string.privileged_access_shizuku_allow))
                }
            }
        }
        Text(
            text = stringResource(
                if (backend == PrivilegedBackend.SHIZUKU) R.string.privileged_access_using_shizuku
                else R.string.privileged_access_using_broker
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (backend == PrivilegedBackend.SHIZUKU && shizukuState != ShizukuState.READY) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun PrivilegedAccessMode.labelRes(): Int = when (this) {
    PrivilegedAccessMode.AUTOMATIC -> R.string.privileged_access_mode_auto
    PrivilegedAccessMode.SHIZUKU_ONLY -> R.string.privileged_access_mode_shizuku
    PrivilegedAccessMode.BROKER_ONLY -> R.string.privileged_access_mode_broker
}

private fun ShizukuState.labelRes(): Int = when (this) {
    ShizukuState.READY -> R.string.privileged_access_shizuku_ready
    ShizukuState.NOT_ALLOWED -> R.string.privileged_access_shizuku_not_allowed
    ShizukuState.NOT_RUNNING -> R.string.privileged_access_shizuku_not_running
    ShizukuState.NOT_INSTALLED -> R.string.privileged_access_shizuku_not_installed
    ShizukuState.TOO_OLD -> R.string.privileged_access_shizuku_too_old
    ShizukuState.DENIED_PERMANENTLY -> R.string.privileged_access_shizuku_denied_permanently
}
