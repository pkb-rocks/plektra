package rocks.pkb.plektra

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.KeyboardBacklightManager
import rocks.pkb.plektra.inputmethod.PrivilegedDiagnostics
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.inputmethod.WriteSecureSettingsHolders
import rocks.pkb.plektra.ui.BrokerStatusMonitor
import rocks.pkb.plektra.ui.awaitScreenTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Smart keyboard backlight, as a section of the Titan 2 Elite tools screen
 * ([Titan2EliteToolsScreen]), below the device setup card.
 *
 * One switch keeps the keyboard lit past the vendor's 30 s limit by writing the vendor timeout
 * once ([KeyboardBacklightManager]); the value is stored on the phone and survives reboots. The
 * pairing it needs is the device setup card above, so this section does not repeat the pairing
 * walkthrough. It reads the broker verdict the card already keeps current
 * ([BrokerStatusMonitor]) and does not start a check of its own.
 *
 * What it shows is the truth about the phone, not the toggle: "configured" is a one-way latch, so
 * once the broker is reachable the phone is asked what it holds (a read, so the open screen's
 * trigger is enough), and a lost setting is reported with a button to apply it again.
 *
 * Every write goes through a tap: the switch, and the "Apply again" button, which
 * also covers a switch turned on before the pairing was ready. The switch is the
 * [WriteSecureSettingsHolders.BACKLIGHT] holder, so the Quick Settings tile can write the vendor
 * keyboard-light row while it is on. A second switch, "Quick Settings tile", is the tile's own
 * [WriteSecureSettingsHolders.BACKLIGHT_TILE] holder, for the tile with Smart backlight off.
 */
@Composable
fun SmartBacklightSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(SettingsManager.getSmartBacklightEnabled(context)) }
    // Read (and so settled, see SettingsManager.getBacklightTileEnabled) before either switch can
    // be flipped, so turning Smart backlight off never revokes from under an upgraded tile.
    var tileEnabled by remember { mutableStateOf(SettingsManager.getBacklightTileEnabled(context)) }

    // Stored state is cheap to read, so it is polled to follow a write that lands while the screen
    // is open (the write itself is queued off-thread).
    var statusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            statusTick++
        }
    }
    // Readiness keys off the persisted "configured once" flag, not live Wireless debugging: the
    // value outlives Wireless debugging, so once written the feature is set up.
    val configured = remember(statusTick) { PrivilegedDiagnostics.backlightApplied(context) }
    val lastFailure = remember(statusTick) {
        PrivilegedDiagnostics.last(context, PrivilegedDiagnostics.Step.BACKLIGHT)
            ?.takeIf { !it.ok }
            ?.reason
    }

    val brokerStatus by BrokerStatusMonitor.status.collectAsState()

    // Null until the phone has been asked.
    var verifyTick by remember { mutableIntStateOf(0) }
    var alwaysOnOnPhone by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(enabled, brokerStatus, verifyTick) {
        alwaysOnOnPhone = if (enabled && brokerStatus == EmbeddedAdbShell.BrokerStatus.OK) {
            // A read-only command: the open screen's trigger allows it, nothing else.
            val trigger = awaitScreenTrigger(context, "backlight_section")
            if (trigger == null) {
                null
            } else {
                withContext(Dispatchers.IO) { KeyboardBacklightManager.isAlwaysOnOnDevice(context, trigger) }
            }
        } else {
            null
        }
    }

    // No write runs on screen open. A switch turned on before the pairing or
    // Wireless debugging was ready shows "Apply again" below once the broker is reachable and
    // the phone does not hold the setting.

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.smart_backlight_enable_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.smart_backlight_enable_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    SettingsManager.setSmartBacklightEnabled(context, on)
                    val trigger = BrokerTrigger.fromTap(context, "backlight_toggle")
                    // The tile writes through WRITE_SECURE_SETTINGS; held while this is on.
                    PrivilegedSetup.setWriteSecureSettingsNeeded(
                        context, WriteSecureSettingsHolders.BACKLIGHT, on, trigger
                    )
                    // One-time write of the persistent vendor setting (survives reboots). Both
                    // paths are no-ops, with the reason recorded, if the broker cannot connect,
                    // and both end with Wireless debugging off when the user keeps that setting.
                    if (on) {
                        PrivilegedSetup.applyAll(context, reason = "backlight_toggle", trigger = trigger)
                    } else {
                        KeyboardBacklightManager.revertToDefault(context, trigger)
                        PrivilegedSetup.queueFinishSetup(context, trigger)
                    }
                }
            )
        }

        if (enabled) {
            // A verified failure outranks the stored-state verdict: it is the only signal that can
            // catch a pairing the phone never accepted.
            // Once the setting is stored on the phone, Wireless debugging being off (which the
            // broker does itself after setup) is not a problem for this feature.
            val problem = brokerStatus?.takeIf { it != EmbeddedAdbShell.BrokerStatus.OK }
                ?.takeUnless { configured && it == EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF }
            if (problem != null) {
                StatusLine(
                    text = "! " + stringResource(
                        when (problem) {
                            EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF ->
                                R.string.smart_backlight_blocked_wireless_debugging
                            EmbeddedAdbShell.BrokerStatus.NOT_PAIRED ->
                                R.string.broker_blocked_not_paired
                            EmbeddedAdbShell.BrokerStatus.NO_SERVICE ->
                                R.string.broker_blocked_no_service
                            else -> R.string.device_setup_rejected_body
                        }
                    ),
                    isError = true
                )
            } else if (configured && alwaysOnOnPhone != false) {
                StatusLine(
                    text = "✓ " + stringResource(R.string.smart_backlight_setup_ready),
                    isError = false
                )
            }

            // The broker is reachable but the phone no longer holds the setting: it was written
            // once and something took it back. Nothing in the stored state can see this, which is
            // how a toggle stays on while the backlight times out.
            if (alwaysOnOnPhone == false) {
                StatusLine(
                    text = "! " + stringResource(
                        if (configured) R.string.smart_backlight_not_on_device else R.string.smart_backlight_not_applied_yet
                    ),
                    isError = true
                )
                Button(
                    onClick = {
                        // The whole setup, on a tap: the WRITE_SECURE_SETTINGS grant the tile
                        // needs, the always-on value, and Wireless debugging off afterwards.
                        val trigger = BrokerTrigger.fromTap(context, "backlight_reapply")
                        scope.launch {
                            PrivilegedSetup.applyAll(context, reason = "backlight_reapply", trigger = trigger)
                            // The write is queued; look again after it has had time to land.
                            delay(1_500)
                            verifyTick++
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(stringResource(if (configured) R.string.smart_backlight_reapply else R.string.smart_backlight_apply_now))
                }
            }

            lastFailure?.let { failure ->
                Text(
                    text = stringResource(R.string.smart_backlight_last_error, failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                )
            }
        }

        // the keyboard-light tile's own hold on WRITE_SECURE_SETTINGS, so the tile
        // keeps working with Smart backlight off. Revoked only when Smart backlight does not
        // still need it.
        Row(
            modifier = Modifier
                .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_BACKLIGHT_TILE)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backlight_tile_holder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.backlight_tile_holder_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = tileEnabled,
                onCheckedChange = { on ->
                    tileEnabled = on
                    SettingsManager.setBacklightTileEnabled(context, on)
                    val trigger = BrokerTrigger.fromTap(context, "backlight_tile_toggle")
                    // Grants on the first holder, revokes on the last; the session is audited.
                    // When it queued one, Wireless debugging goes off after it as usual.
                    if (PrivilegedSetup.setWriteSecureSettingsNeeded(
                            context, WriteSecureSettingsHolders.BACKLIGHT_TILE, on, trigger
                        )
                    ) {
                        PrivilegedSetup.queueFinishSetup(context, trigger)
                    }
                }
            )
        }
    }
}

@Composable
private fun StatusLine(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
