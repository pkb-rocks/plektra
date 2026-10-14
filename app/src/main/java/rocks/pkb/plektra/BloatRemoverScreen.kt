package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import rocks.pkb.plektra.toolbox.BloatCatalog
import rocks.pkb.plektra.toolbox.PackageRemover
import rocks.pkb.plektra.toolbox.PackageState
import rocks.pkb.plektra.toolbox.RemovalJournal
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.ui.awaitScreenTrigger
import rocks.pkb.plektra.ui.rememberVerifiedBrokerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remove the vendor packages Android will not let you uninstall.
 *
 * Disable is the default action everywhere because it achieves what people actually want —
 * gone from the drawer, not running — while staying trivially reversible. Uninstall is a
 * second, deliberate step behind a warning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloatRemoverScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supported = remember { BloatCatalog.isSupportedDevice() }

    // Verified, not "a key is stored": these tools disable and uninstall packages through the
    // broker, so claiming they are available when nothing can connect is the worst version of
    // this bug — every action would fail after the user committed to it.
    val brokerStatus by rememberVerifiedBrokerStatus()
    val paired = brokerStatus == EmbeddedAdbShell.BrokerStatus.OK
    var states by remember { mutableStateOf<Map<String, PackageState>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf<BloatCatalog.Entry?>(null) }
    var journalCount by remember { mutableStateOf(RemovalJournal.all(context).size) }
    var unrecognised by remember { mutableStateOf<List<String>>(emptyList()) }

    // [trigger] is the tap that just changed something, so the census reads on that same tap
    // before Wireless debugging is switched off; null means the screen is opening, which may
    // only read (a SCREEN trigger, finding 1.3g).
    fun refresh(trigger: BrokerTrigger? = null, afterRead: () -> Unit = {}) {
        if (!supported) return
        loading = true
        scope.launch {
            val readTrigger = trigger ?: awaitScreenTrigger(context, "bloat_screen")
            // `paired` is now the verified broker status and refreshes itself; re-deriving it
            // from the stored key here would put the false positive straight back.
            val read = withContext(Dispatchers.IO) {
                PackageRemover.census(context, readTrigger)
            }
            afterRead()
            if (read != null) {
                states = read.states
                unrecognised = read.unrecognised
            }
            journalCount = RemovalJournal.all(context).size
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // After a change: read back on the same tap, then Wireless debugging off (finding 1.3e).
    fun refreshAndFinish(trigger: BrokerTrigger?) =
        refresh(trigger) { PrivilegedSetup.queueFinishSetup(context, trigger) }

    fun act(entry: BloatCatalog.Entry, uninstall: Boolean) {
        val trigger = BrokerTrigger.fromTap(context, if (uninstall) "bloat_uninstall" else "bloat_disable")
        busy = entry.packageName
        scope.launch {
            val current = states[entry.packageName] ?: PackageState.ACTIVE
            val result = withContext(Dispatchers.IO) {
                if (uninstall) {
                    PackageRemover.uninstall(context, entry.packageName, current, trigger)
                } else {
                    PackageRemover.disable(context, entry.packageName, current, trigger)
                }
            }
            busy = null
            message = when (result) {
                is PackageRemover.Result.Success -> null
                is PackageRemover.Result.NotPaired -> privilegedBlockedMessage(context)
                is PackageRemover.Result.Refused -> result.reason
                is PackageRemover.Result.Failed -> result.reason
            }
            refreshAndFinish(trigger)
        }
    }

    fun restore(packageName: String) {
        val trigger = BrokerTrigger.fromTap(context, "bloat_restore")
        busy = packageName
        scope.launch {
            val result = withContext(Dispatchers.IO) { PackageRemover.restore(context, packageName, trigger) }
            busy = null
            if (result is PackageRemover.Result.Failed) message = result.reason
            refreshAndFinish(trigger)
        }
    }

    ToolboxPageScaffold(
        title = stringResource(R.string.bloat_title),
        onBack = onBack,
        modifier = modifier
    ) {
            Text(
                text = stringResource(R.string.bloat_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (!supported) {
                Notice(stringResource(R.string.bloat_wrong_device))
                return@ToolboxPageScaffold
            }
            if (!paired) {
                PrivilegedBlockedNotice(onOpenPairing = onOpenPairing)
                return@ToolboxPageScaffold
            }

            message?.let { Notice(it) }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // One card per preset, each listing only what is still active, so a bundle whose
            // packages are already gone stops taking up room. Presets overlap deliberately —
            // a package can be both factory tooling and a privacy concern.
            BloatCatalog.Preset.entries.forEach { preset ->
                val active = BloatCatalog.entriesIn(preset)
                    .filter { states[it.packageName] == PackageState.ACTIVE }
                if (active.isEmpty()) return@forEach
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(presetTitle(preset)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            // The Android Auto bundle is a hypothesis, not a proven fix. The
                            // description says so, but a paragraph is easy to skim past.
                            if (preset == BloatCatalog.Preset.BACKGROUND_KILLERS) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = stringResource(R.string.bloat_preset_in_testing),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(presetDescription(preset)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            enabled = busy == null,
                            onClick = {
                                val trigger = BrokerTrigger.fromTap(context, "bloat_preset")
                                busy = "*"
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        active.forEach { entry ->
                                            PackageRemover.disable(
                                                context,
                                                entry.packageName,
                                                states[entry.packageName] ?: PackageState.ACTIVE,
                                                trigger
                                            )
                                        }
                                    }
                                    busy = null
                                    refreshAndFinish(trigger)
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.bloat_preset_action, active.size))
                        }
                    }
                }
            }

            if (journalCount > 0) {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = pluralStringResource(R.plurals.bloat_restore_all_summary, journalCount, journalCount),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val trigger = BrokerTrigger.fromTap(context, "bloat_restore_all")
                            busy = "*"
                            scope.launch {
                                withContext(Dispatchers.IO) { PackageRemover.restoreAll(context, trigger) }
                                busy = null
                                refreshAndFinish(trigger)
                            }
                        }) { Text(stringResource(R.string.bloat_restore_all)) }
                    }
                }
            }

            var lastTier: BloatCatalog.Tier? = null
            BloatCatalog.entries().forEach { entry ->
                val state = states[entry.packageName] ?: PackageState.ABSENT
                if (state == PackageState.ABSENT) return@forEach
                if (entry.tier != lastTier) {
                    lastTier = entry.tier
                    TierHeader(entry.tier)
                }
                BloatRow(
                    entry = entry,
                    state = state,
                    busy = busy == entry.packageName || busy == "*",
                    onDisable = { act(entry, uninstall = false) },
                    onUninstall = { confirmUninstall = entry },
                    onRestore = { restore(entry.packageName) }
                )
            }

            if (unrecognised.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = pluralStringResource(R.plurals.bloat_unknown_title, unrecognised.size, unrecognised.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.bloat_unknown_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    unrecognised.forEach { pkg ->
                        Text(
                            text = pkg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        confirmUninstall?.let { entry ->
            AlertDialog(
                onDismissRequest = { confirmUninstall = null },
                title = { Text(stringResource(R.string.bloat_uninstall_confirm_title, entry.label)) },
                text = { Text(stringResource(R.string.bloat_uninstall_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUninstall = null
                        act(entry, uninstall = true)
                    }) { Text(stringResource(R.string.bloat_uninstall)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUninstall = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
}

@Composable
private fun Notice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun TierHeader(tier: BloatCatalog.Tier) {
    val (title, blurb) = when (tier) {
        BloatCatalog.Tier.SAFE ->
            stringResource(R.string.bloat_tier_safe) to stringResource(R.string.bloat_tier_safe_blurb)
        BloatCatalog.Tier.OPTIONAL ->
            stringResource(R.string.bloat_tier_optional) to stringResource(R.string.bloat_tier_optional_blurb)
        BloatCatalog.Tier.USEFUL ->
            stringResource(R.string.bloat_tier_useful) to stringResource(R.string.bloat_tier_useful_blurb)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BloatRow(
    entry: BloatCatalog.Entry,
    state: PackageState,
    busy: Boolean,
    onDisable: () -> Unit,
    onUninstall: () -> Unit,
    onRestore: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(text = entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when (state) {
                        PackageState.ACTIVE -> stringResource(R.string.bloat_state_active)
                        PackageState.DISABLED -> stringResource(R.string.bloat_state_disabled)
                        PackageState.UNINSTALLED -> stringResource(R.string.bloat_state_uninstalled)
                        PackageState.ABSENT -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state == PackageState.ACTIVE) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.weight(1f)
                )
                when (state) {
                    PackageState.ACTIVE -> {
                        TextButton(enabled = !busy, onClick = onDisable) {
                            Text(stringResource(R.string.bloat_disable))
                        }
                        TextButton(enabled = !busy, onClick = onUninstall) {
                            Text(stringResource(R.string.bloat_uninstall))
                        }
                    }
                    PackageState.DISABLED -> {
                        TextButton(enabled = !busy, onClick = onRestore) {
                            Text(stringResource(R.string.bloat_restore))
                        }
                        TextButton(enabled = !busy, onClick = onUninstall) {
                            Text(stringResource(R.string.bloat_uninstall))
                        }
                    }
                    PackageState.UNINSTALLED -> {
                        TextButton(enabled = !busy, onClick = onRestore) {
                            Text(stringResource(R.string.bloat_restore))
                        }
                    }
                    PackageState.ABSENT -> Unit
                }
            }
        }
    }
}

@StringRes
private fun presetTitle(preset: BloatCatalog.Preset): Int = when (preset) {
    BloatCatalog.Preset.BACKGROUND_KILLERS -> R.string.bloat_preset_title
    BloatCatalog.Preset.FACTORY_TOOLS -> R.string.bloat_preset_factory_title
    BloatCatalog.Preset.VENDOR_EXTRAS -> R.string.bloat_preset_vendor_title
    BloatCatalog.Preset.PRIVACY -> R.string.bloat_preset_privacy_title
}

@StringRes
private fun presetDescription(preset: BloatCatalog.Preset): Int = when (preset) {
    BloatCatalog.Preset.BACKGROUND_KILLERS -> R.string.bloat_preset_description
    BloatCatalog.Preset.FACTORY_TOOLS -> R.string.bloat_preset_factory_description
    BloatCatalog.Preset.VENDOR_EXTRAS -> R.string.bloat_preset_vendor_description
    BloatCatalog.Preset.PRIVACY -> R.string.bloat_preset_privacy_description
}
