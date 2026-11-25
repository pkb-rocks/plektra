package rocks.pkb.plektra

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.text.NumberFormat
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rocks.pkb.plektra.backlight.BacklightObservation
import rocks.pkb.plektra.backlight.BacklightOutcome
import rocks.pkb.plektra.backlight.BacklightSnapshot
import rocks.pkb.plektra.backlight.BacklightSupport
import rocks.pkb.plektra.backlight.KeyboardBacklightFeature
import rocks.pkb.plektra.commands.DeviceControlCommandSource

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4211

/**
 * Hardware keyboard section for the Unihertz keyboard backlight. Only values read from the device
 * are shown as current; otherwise the last read value is labelled with its time.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardBacklightSection() {
    val context = LocalContext.current
    val controller = remember { KeyboardBacklightFeature.controller(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<BacklightSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun launchOperation(action: suspend () -> BacklightSnapshot) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                snapshot = action()
            } finally {
                busy = false
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        launchOperation { controller.refresh() }
        onPauseOrDispose { }
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) launchOperation { controller.refresh() }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    val current = snapshot

    Surface(modifier = Modifier.settingRow("hardware.keyboard_backlight_follows_display").fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.keyboard_backlight_follows_display_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.keyboard_backlight_follows_display_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = current?.let { observationText(context, it.observation) }
                        ?: stringResource(R.string.keyboard_backlight_state_reading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Switch(
                checked = current?.observation?.followsDisplay == true,
                enabled = current?.canChange == true && !busy,
                onCheckedChange = { enabled ->
                    launchOperation { if (enabled) controller.enable() else controller.disable() }
                }
            )
        }
    }

    if (current == null) return
    statusMessages(context, current).forEach { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (current.support == BacklightSupport.Supported &&
            current.shizuku == ShizukuStatus.NotAuthorized &&
            !shizukuRationaleRequired()
        ) {
            TextButton(onClick = { requestShizukuPermission() }) {
                Text(stringResource(R.string.keyboard_backlight_shizuku_allow))
            }
        }
        if (current.outcome == BacklightOutcome.Uncertain || current.outcome == BacklightOutcome.ReadFailed) {
            TextButton(onClick = { launchOperation { controller.refresh() } }, enabled = !busy) {
                Text(stringResource(R.string.keyboard_backlight_check_again))
            }
        }
        if (needsSystemSettings(current)) {
            TextButton(onClick = { openSystemBacklightSettings(context) }) {
                Text(stringResource(R.string.keyboard_backlight_open_system_settings))
            }
        }
    }
}

private fun observationText(context: Context, observation: BacklightObservation): String {
    val state = when (val timeout = observation.timeout) {
        null -> return context.getString(R.string.keyboard_backlight_state_unknown)
        else -> stateText(context, timeout)
    }
    return when (observation) {
        is BacklightObservation.LastVerified -> context.getString(
            R.string.keyboard_backlight_last_read,
            state,
            DateUtils.formatDateTime(
                context,
                observation.verifiedAtMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
            )
        )
        else -> state
    }
}

private fun stateText(context: Context, timeout: String): String = when {
    timeout == "-1" -> context.getString(R.string.keyboard_backlight_state_on)
    timeout == "0" -> context.getString(R.string.keyboard_backlight_state_off_light_disabled)
    else -> timeout.toLongOrNull()?.let { millis ->
        context.getString(R.string.keyboard_backlight_state_off_timeout, seconds(millis))
    } ?: context.getString(R.string.keyboard_backlight_state_unknown)
}

private fun seconds(millis: Long): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(millis / 1000.0)

private fun statusMessages(context: Context, snapshot: BacklightSnapshot): List<String> = buildList {
    if (snapshot.support == BacklightSupport.FirmwareChanged) {
        add(context.getString(R.string.keyboard_backlight_firmware_changed))
    } else {
        when (snapshot.shizuku) {
            ShizukuStatus.NotConnected -> add(context.getString(R.string.keyboard_backlight_shizuku_not_running))
            ShizukuStatus.NotAuthorized -> add(context.getString(R.string.keyboard_backlight_shizuku_not_authorized))
            ShizukuStatus.Connected -> Unit
        }
    }
    outcomeRes(snapshot.outcome)?.let { add(context.getString(it)) }
    snapshot.recovery?.let { record ->
        add(
            if (record.originalTimeout == "0") {
                context.getString(R.string.keyboard_backlight_restore_light_off)
            } else {
                context.getString(
                    R.string.keyboard_backlight_restore_timeout,
                    seconds(record.originalTimeout.toLong())
                )
            }
        )
    }
}

private fun outcomeRes(outcome: BacklightOutcome): Int? = when (outcome) {
    BacklightOutcome.AlreadyFollowingWithoutOriginal,
    BacklightOutcome.NoOriginalToRestore -> R.string.keyboard_backlight_outcome_no_original
    BacklightOutcome.UnrestorableCurrentValue -> R.string.keyboard_backlight_outcome_unrestorable
    BacklightOutcome.ServiceUnverified -> R.string.keyboard_backlight_outcome_service_unverified
    BacklightOutcome.ReadFailed -> R.string.keyboard_backlight_outcome_read_failed
    BacklightOutcome.RecordFailed -> R.string.keyboard_backlight_outcome_record_failed
    BacklightOutcome.WriteFailed -> R.string.keyboard_backlight_outcome_write_failed
    BacklightOutcome.Uncertain -> R.string.keyboard_backlight_outcome_uncertain
    BacklightOutcome.None,
    BacklightOutcome.Enabled,
    BacklightOutcome.Disabled,
    BacklightOutcome.ShizukuUnavailable,
    BacklightOutcome.UnsupportedFirmware -> null
}

private fun needsSystemSettings(snapshot: BacklightSnapshot): Boolean =
    snapshot.support == BacklightSupport.FirmwareChanged ||
        snapshot.outcome == BacklightOutcome.AlreadyFollowingWithoutOriginal ||
        snapshot.outcome == BacklightOutcome.NoOriginalToRestore

private fun shizukuRationaleRequired(): Boolean =
    runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true)

private fun requestShizukuPermission() {
    runCatching {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }
}

private fun openSystemBacklightSettings(context: Context) {
    val component = ComponentName.unflattenFromString(
        DeviceControlCommandSource.UNIHERTZ_KEYBOARD_BACKLIGHT_COMPONENT
    ) ?: return
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, R.string.keyboard_backlight_system_settings_unavailable, Toast.LENGTH_SHORT).show()
    }
}
