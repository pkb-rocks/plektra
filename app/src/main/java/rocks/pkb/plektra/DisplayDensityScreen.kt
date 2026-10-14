package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.toolbox.DisplayDensity
import rocks.pkb.plektra.toolbox.RevertibleChange
import rocks.pkb.plektra.ui.awaitScreenTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Screen density, with the undo armed before the change lands.
 *
 * The countdown is the whole safety model: after applying, the user has a fixed window to say
 * the screen is still readable, and anything else — expiry, a crash, putting the phone down —
 * puts the panel back where it started.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayDensityScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<DisplayDensity.Info?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deadlineMs by remember { mutableStateOf<Long?>(null) }
    var secondsLeft by remember { mutableStateOf<Int?>(null) }
    // A change past its deadline that could not be undone without a tap (the process died, so
    // the trigger of the tap that applied it is gone): the screen offers the revert as a button.
    var stuckRevert by remember { mutableStateOf(false) }

    // Broker triggers (finding 1.3g): opening the screen only reads (SCREEN trigger); a change is
    // a tap, and after one the screen reads back on that same tap. Wireless debugging is switched
    // off (finding 1.3e) only once a change is settled, never while a revert is armed.
    suspend fun reload(trigger: BrokerTrigger?) {
        loading = true
        // A change armed before this screen was (re)created, which is what a density change
        // does to the settings activity, resumes its countdown; one already past its deadline
        // is undone at once, with the trigger of the tap that applied it.
        val pending = RevertibleChange.pending(context)
        if (pending != null && pending.remainingMs() == 0L) {
            withContext(Dispatchers.IO) { RevertibleChange.revertExpired(context) }
        }
        val readTrigger = trigger ?: awaitScreenTrigger(context, "density_screen")
        val read = withContext(Dispatchers.IO) { DisplayDensity.read(context, readTrigger) }
        info = read
        if (read != null && selected == 0f) selected = read.current.toFloat()
        val now = RevertibleChange.pending(context)
        stuckRevert = now != null && now.remainingMs() == 0L
        deadlineMs = now?.deadlineMs?.takeIf { now.remainingMs() > 0L }
        loading = false
    }

    fun load() {
        scope.launch { reload(null) }
    }

    LaunchedEffect(Unit) { load() }

    // The countdown IS the confirmation. Reaching zero reverts; only an explicit Keep stops it. The
    // deadline is stored with the pending change, so it survives this screen being recreated.
    LaunchedEffect(deadlineMs) {
        val deadline = deadlineMs ?: run { secondsLeft = null; return@LaunchedEffect }
        while (true) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0) break
            secondsLeft = ((remainingMs + 999) / 1000).toInt()
            delay(250)
        }
        secondsLeft = null
        deadlineMs = null
        withContext(Dispatchers.IO) { RevertibleChange.revertExpired(context) }
        reload(null)
    }

    ToolboxPageScaffold(
        title = stringResource(R.string.density_title),
        onBack = onBack,
        modifier = modifier
    ) {
            Text(
                text = stringResource(R.string.density_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                return@ToolboxPageScaffold
            }

            if (stuckRevert) {
                Text(
                    text = stringResource(R.string.density_revert_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        val trigger = BrokerTrigger.fromTap(context, "density_revert_pending")
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { RevertibleChange.revertNow(context, trigger) }
                            busy = false
                            reload(trigger)
                            PrivilegedSetup.queueFinishSetup(context, trigger)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) { Text(stringResource(R.string.density_revert)) }
            }

            val current = info
            if (current == null) {
                PrivilegedBlockedNotice(onOpenPairing = onOpenPairing)
                return@ToolboxPageScaffold
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val range = DisplayDensity.range(current.physical)
            val chosen = selected.roundToInt()

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.density_value, chosen, current.physical),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (chosen < current.physical) {
                        stringResource(R.string.density_smaller)
                    } else if (chosen > current.physical) {
                        stringResource(R.string.density_larger)
                    } else {
                        stringResource(R.string.density_stock)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = (it / 5f).roundToInt() * 5f },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    enabled = !busy && secondsLeft == null,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && !stuckRevert && secondsLeft == null && chosen != current.current,
                        onClick = {
                            val trigger = BrokerTrigger.fromTap(context, "density_apply")
                            busy = true
                            error = null
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    DisplayDensity.apply(context, current, chosen, trigger)
                                }
                                busy = false
                                when (outcome) {
                                    // Settled by Keep, Revert or the countdown, each of which
                                    // switches Wireless debugging off afterwards.
                                    is RevertibleChange.Outcome.Applied -> {
                                        deadlineMs = RevertibleChange.pending(context)?.deadlineMs
                                    }
                                    is RevertibleChange.Outcome.NotPaired -> {
                                        error = privilegedBlockedMessage(context)
                                    }
                                    is RevertibleChange.Outcome.Failed -> {
                                        error = outcome.reason
                                        PrivilegedSetup.queueFinishSetup(context, trigger)
                                    }
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.density_apply)) }

                    if (current.isOverridden) {
                        TextButton(
                            enabled = !busy && secondsLeft == null,
                            onClick = {
                                val trigger = BrokerTrigger.fromTap(context, "density_reset")
                                busy = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { DisplayDensity.reset(context, trigger) }
                                    busy = false
                                    selected = current.physical.toFloat()
                                    reload(trigger)
                                    PrivilegedSetup.queueFinishSetup(context, trigger)
                                }
                            }
                        ) { Text(stringResource(R.string.density_reset)) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        secondsLeft?.let { remaining ->
            AlertDialog(
                onDismissRequest = { /* only Keep or the countdown may close this */ },
                title = { Text(stringResource(R.string.density_confirm_title)) },
                text = { Text(pluralStringResource(R.plurals.density_confirm_message, remaining, remaining)) },
                confirmButton = {
                    TextButton(onClick = {
                        val trigger = BrokerTrigger.fromTap(context, "density_keep")
                        deadlineMs = null
                        RevertibleChange.keep(context)
                        scope.launch {
                            reload(trigger)
                            PrivilegedSetup.queueFinishSetup(context, trigger)
                        }
                    }) { Text(stringResource(R.string.density_keep)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val trigger = BrokerTrigger.fromTap(context, "density_revert")
                        deadlineMs = null
                        scope.launch {
                            withContext(Dispatchers.IO) { RevertibleChange.revertNow(context, trigger) }
                            reload(trigger)
                            PrivilegedSetup.queueFinishSetup(context, trigger)
                        }
                    }) { Text(stringResource(R.string.density_revert)) }
                }
            )
        }
}
