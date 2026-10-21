package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.toolbox.SystemTweaks
import rocks.pkb.plektra.ui.awaitScreenTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings Android supports and this ROM never exposes. Each one applies immediately and can
 * be put back; none of them can leave the phone unusable, which is why there is no countdown
 * here the way there is on density.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemTweaksScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<SystemTweaks.State?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // Opening the screen reads (a SCREEN trigger runs read-only commands only).
    fun load() {
        loading = true
        scope.launch {
            val trigger = awaitScreenTrigger(context, "tweaks_screen")
            state = withContext(Dispatchers.IO) { SystemTweaks.read(context, trigger) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    // Every change is a tap. The screen reads the result back on the same tap, then Wireless
    // debugging is switched off if the user keeps that setting.
    fun run(source: String, block: suspend (BrokerTrigger?) -> Unit) {
        val trigger = BrokerTrigger.fromTap(context, source)
        busy = true
        scope.launch {
            state = withContext(Dispatchers.IO) {
                block(trigger)
                SystemTweaks.read(context, trigger)
            }
            busy = false
            PrivilegedSetup.queueFinishSetup(context, trigger)
        }
    }

    ToolboxPageScaffold(
        title = stringResource(R.string.tweaks_title),
        onBack = onBack,
        modifier = modifier
    ) {
            Text(
                text = stringResource(R.string.tweaks_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                return@ToolboxPageScaffold
            }
            val current = state
            if (current == null) {
                Text(
                    text = stringResource(R.string.bloat_not_paired),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Button(
                    onClick = onOpenPairing,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.bloat_set_up_pairing)) }
                return@ToolboxPageScaffold
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.tweaks_animation_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.tweaks_animation_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SystemTweaks.AnimationSpeed.entries.forEach { speed ->
                        FilterChip(
                            selected = current.animation == speed,
                            enabled = !busy,
                            onClick = { run("tweaks_animation") { t -> SystemTweaks.setAnimationSpeed(context, speed, t) } },
                            label = {
                                Text(
                                    stringResource(
                                        when (speed) {
                                            SystemTweaks.AnimationSpeed.OFF -> R.string.tweaks_animation_off
                                            SystemTweaks.AnimationSpeed.FAST -> R.string.tweaks_animation_fast
                                            SystemTweaks.AnimationSpeed.NORMAL -> R.string.tweaks_animation_normal
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
            }

            TweakSwitch(
                title = stringResource(R.string.tweaks_notification_history_title),
                description = stringResource(R.string.tweaks_notification_history_description),
                checked = current.toggles[SystemTweaks.Toggle.NOTIFICATION_HISTORY] == true,
                enabled = !busy,
                onCheckedChange = { on ->
                    run("tweaks_notification_history") { t ->
                        SystemTweaks.setToggle(context, SystemTweaks.Toggle.NOTIFICATION_HISTORY, on, t)
                    }
                }
            )
            TweakSwitch(
                title = stringResource(R.string.tweaks_one_handed_title),
                description = stringResource(R.string.tweaks_one_handed_description),
                checked = current.toggles[SystemTweaks.Toggle.ONE_HANDED_MODE] == true,
                enabled = !busy,
                onCheckedChange = { on ->
                    run("tweaks_one_handed") { t ->
                        SystemTweaks.setToggle(context, SystemTweaks.Toggle.ONE_HANDED_MODE, on, t)
                    }
                }
            )

            TextButton(
                enabled = !busy,
                onClick = { run("tweaks_reset") { t -> SystemTweaks.resetAll(context, t) } },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text(stringResource(R.string.tweaks_reset)) }

            Spacer(modifier = Modifier.height(24.dp))
        }
}

@Composable
private fun TweakSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}
