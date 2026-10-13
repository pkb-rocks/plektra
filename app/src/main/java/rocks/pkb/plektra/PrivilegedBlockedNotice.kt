package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import rocks.pkb.plektra.inputmethod.PrivilegedAccessMode
import rocks.pkb.plektra.inputmethod.PrivilegedBackend
import rocks.pkb.plektra.inputmethod.PrivilegedBackends
import rocks.pkb.plektra.inputmethod.ShizukuState
import rocks.pkb.plektra.shizuku.ShizukuUserServiceShell

/** Which blocked-state message a toolbox screen shows for the chosen route and Shizuku's state. */
internal enum class PrivilegedBlock {
    /** The built-in broker is the route and nothing else is on offer. */
    PAIRING,

    /** Automatic with the broker unusable and Shizuku not ready: pairing, and Shizuku as the other way. */
    PAIRING_OR_SHIZUKU,

    SHIZUKU_NOT_ALLOWED,
    SHIZUKU_DENIED_PERMANENTLY,
    SHIZUKU_NOT_RUNNING,
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_TOO_OLD,

    /** Shizuku is the route and is ready, yet the screen could not use it. Pairing would be wrong. */
    SHIZUKU_FAILED;

    /** The text for a block; [pairingRes] is the screen's own line for the pairing cases. */
    @StringRes
    fun bodyRes(@StringRes pairingRes: Int): Int = when (this) {
        PAIRING, PAIRING_OR_SHIZUKU -> pairingRes
        SHIZUKU_NOT_ALLOWED -> R.string.shizuku_blocked_not_allowed
        SHIZUKU_DENIED_PERMANENTLY -> R.string.shizuku_blocked_denied_permanently
        SHIZUKU_NOT_RUNNING -> R.string.shizuku_blocked_not_running
        SHIZUKU_NOT_INSTALLED -> R.string.shizuku_blocked_not_installed
        SHIZUKU_TOO_OLD -> R.string.shizuku_blocked_too_old
        SHIZUKU_FAILED -> R.string.shizuku_blocked_failed
    }

    companion object {
        /**
         * Pairing is the broker's remedy, so it is offered only when the broker is the route. A
         * Shizuku-only user is told about Shizuku however it fails.
         */
        fun choose(mode: PrivilegedAccessMode, state: ShizukuState): PrivilegedBlock {
            if (PrivilegedBackends.choose(mode, state) == PrivilegedBackend.BROKER) {
                return if (mode == PrivilegedAccessMode.AUTOMATIC && state != ShizukuState.READY) {
                    PAIRING_OR_SHIZUKU
                } else {
                    PAIRING
                }
            }
            return when (state) {
                ShizukuState.NOT_ALLOWED -> SHIZUKU_NOT_ALLOWED
                ShizukuState.DENIED_PERMANENTLY -> SHIZUKU_DENIED_PERMANENTLY
                ShizukuState.NOT_RUNNING -> SHIZUKU_NOT_RUNNING
                ShizukuState.NOT_INSTALLED -> SHIZUKU_NOT_INSTALLED
                ShizukuState.TOO_OLD -> SHIZUKU_TOO_OLD
                ShizukuState.READY -> SHIZUKU_FAILED
            }
        }
    }
}

/**
 * What a toolbox screen shows in place of its tools when the chosen route cannot run commands.
 * One place reads the "Privileged access via" setting and [ShizukuState], so no screen tells a
 * Shizuku user to pair.
 *
 * [onOpenPairing] null means the caller has its own way to the pairing steps, so only the text is
 * shown. [pairingRes] is the broker line shown when pairing is the remedy.
 */
@Composable
fun PrivilegedBlockedNotice(
    onOpenPairing: (() -> Unit)?,
    modifier: Modifier = Modifier,
    @StringRes pairingRes: Int = R.string.bloat_not_paired,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(PrivilegedBackends.mode(context)) }
    var shizukuState by remember { mutableStateOf(PrivilegedBackends.shizukuState(context)) }
    // The user can allow Plektra, start Shizuku or change the setting while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            mode = PrivilegedBackends.mode(context)
            shizukuState = PrivilegedBackends.shizukuState(context)
        }
    }

    val block = PrivilegedBlock.choose(mode, shizukuState)
    val body = stringResource(block.bodyRes(pairingRes))

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = body, style = MaterialTheme.typography.bodySmall)
        when (block) {
            PrivilegedBlock.PAIRING, PrivilegedBlock.PAIRING_OR_SHIZUKU -> {
                if (onOpenPairing != null) {
                    Button(
                        onClick = onOpenPairing,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) { Text(stringResource(R.string.bloat_set_up_pairing)) }
                }
                if (block == PrivilegedBlock.PAIRING_OR_SHIZUKU) {
                    Text(
                        text = stringResource(R.string.shizuku_blocked_or_shizuku),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            PrivilegedBlock.SHIZUKU_NOT_ALLOWED -> {
                Button(
                    onClick = {
                        // A tap only: Shizuku shows its own prompt. The poll above picks up the
                        // answer, and the shared broker status re-checks when the route changes.
                        ShizukuUserServiceShell.requestPermission(SHIZUKU_PERMISSION_REQUEST)
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) { Text(stringResource(R.string.shizuku_blocked_allow)) }
                SwitchToBrokerHint()
            }
            PrivilegedBlock.SHIZUKU_DENIED_PERMANENTLY -> {
                // Sui has no launcher icon; opening Developer options makes Sui offer its
                // management screen (Sui 12+). Only offered when something can handle it.
                val developerOptions = remember {
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .takeIf { it.resolveActivity(context.packageManager) != null }
                }
                if (developerOptions != null) {
                    Button(
                        onClick = { runCatching { context.startActivity(developerOptions) } },
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) { Text(stringResource(R.string.shizuku_blocked_open_sui)) }
                }
                SwitchToBrokerHint()
            }
            PrivilegedBlock.SHIZUKU_NOT_RUNNING,
            PrivilegedBlock.SHIZUKU_NOT_INSTALLED,
            PrivilegedBlock.SHIZUKU_TOO_OLD,
            PrivilegedBlock.SHIZUKU_FAILED -> SwitchToBrokerHint()
        }
    }
}

@Composable
private fun SwitchToBrokerHint() {
    Text(
        text = stringResource(R.string.shizuku_blocked_use_broker),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The same message as a string, for a failed tap (the status turned bad between the screen's
 * check and the tap). A Shizuku user is never told to pair.
 */
internal fun privilegedBlockedMessage(context: Context): String {
    val block = PrivilegedBlock.choose(PrivilegedBackends.mode(context), PrivilegedBackends.shizukuState(context))
    return context.getString(block.bodyRes(R.string.bloat_not_paired))
}
