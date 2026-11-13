package rocks.pkb.plektra

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.PrivilegedBackend
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.ui.BrokerStatusMonitor
import rocks.pkb.plektra.ui.rememberVerifiedBrokerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * The one-time ADB pairing every privileged feature depends on, and the verified state of the
 * embedded broker it creates.
 *
 * It sits at the top of the Titan 2 Elite tools screen so the pairing comes before the features
 * that need it. The broker is an extra privilege route beside Shizuku. When
 * "Privileged access via" picks Shizuku, the verified status is Shizuku's and the card says so
 * (no pairing needed); the card itself never asks Shizuku for anything.
 *
 * The pairing watcher is armed only when the user taps the setup button, never because this screen
 * opened: the screen can be reached by an external deep link, and a link must not be able to start
 * a foreground service or the mDNS search. Once armed, the PIN notification is already waiting by
 * the time the user reaches Android's pairing dialog. The button takes them to the right settings
 * screen - which one depends on whether Developer options exist yet, so it checks rather than
 * telling everyone to go find them.
 *
 * The watcher is stopped when pairing succeeds, when the user leaves this screen for good, or
 * after [PAIRING_WINDOW_MS], whichever comes first.
 */
@Composable
fun DeviceSetupCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var statusTick by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            statusTick++
        }
    }
    val paired = remember(statusTick) { EmbeddedAdbShell.isPaired(context) }
    val developerOptionsOn = remember(statusTick) { developerOptionsEnabled(context) }
    val wirelessDebuggingOn = remember(statusTick) {
        EmbeddedAdbShell.isWirelessDebuggingEnabled(context)
    }
    // Shared with every other surface. A private check here is what let this card say the tools
    // could reach the system while the home tile said "needs pairing" at the same moment.
    val brokerStatus by rememberVerifiedBrokerStatus(paired)
    val doNotDisturbOn = remember(statusTick) { doNotDisturbActive(context) }

    // "Forget pairing" first has the broker unpair itself and give back what it was
    // granted; the outcome decides whether the user still has a step to do by hand.
    val scope = rememberCoroutineScope()
    var forgetting by remember { mutableStateOf(false) }
    var forgetOutcome by rememberSaveable { mutableStateOf<String?>(null) }

    // > 0 while the watcher is armed; bumped on every tap so each tap restarts the timeout.
    var armToken by rememberSaveable { mutableIntStateOf(0) }

    var notificationsAllowed by remember {
        mutableStateOf(notificationPermissionGranted(context))
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
        // Only if the user already armed it: the permission prompt must not start the watcher.
        if (granted && !paired && armToken > 0) armPairing(context)
    }

    // The watcher is armed by the button below and deliberately NOT torn down when the app merely
    // goes to the background: the button's whole job is to send the user to Android's Wireless
    // debugging page, and stopping the watcher as they walk out of the door is precisely how they
    // arrive at "Pair device" with nothing listening. It stops on success, on a real exit from
    // this screen (not a rotation), or after PAIRING_WINDOW_MS.
    LaunchedEffect(paired) {
        if (paired && armToken > 0) {
            stopPairing(context)
            armToken = 0
        }
    }
    LaunchedEffect(armToken) {
        if (armToken > 0) {
            delay(PAIRING_WINDOW_MS)
            stopPairing(context)
            armToken = 0
        }
    }
    val armTokenNow by rememberUpdatedState(armToken)
    DisposableEffect(Unit) {
        onDispose {
            if (armTokenNow > 0 && context.findActivity()?.isChangingConfigurations != true) {
                stopPairing(context)
            }
        }
    }

    // Asked for separately, and never as a precondition for arming. The pairing code arrives as a
    // notification, so a denied permission means no code - but the watcher itself still has to run.
    // POST_NOTIFICATIONS is a runtime permission only from Android 13; below that it is implied.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Readiness is the VERIFIED status, never "a key is stored". A key is stored the moment a
    // pairing is attempted, and it stays stored when Wireless debugging is later turned off — so
    // keying this card off `paired` is what let it claim the tools could reach the system while
    // nothing could connect at all.
    val connected = brokerStatus == EmbeddedAdbShell.BrokerStatus.OK
    // OK can come from Shizuku, which needs no pairing.
    val verifiedBackend by BrokerStatusMonitor.backend.collectAsState()
    val viaShizuku = connected && verifiedBackend == PrivilegedBackend.SHIZUKU
    Surface(
        color = if (connected) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (connected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        when {
                            viaShizuku -> R.string.device_setup_ready_shizuku_title
                            connected -> R.string.device_setup_ready_title
                            // Whatever the route (a key, or Shizuku just allowed), no verdict yet
                            // means a check is pending, never "Setup needed".
                            brokerStatus == null -> R.string.device_setup_checking_title
                            paired -> R.string.device_setup_unreachable_title
                            else -> R.string.device_setup_needed_title
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (forgetting) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.broker_forgetting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            forgetOutcome?.let { outcome ->
                ForgetOutcomeNotice(outcome = outcome, onOpenWireless = { openWirelessDebugging(context) })
            }

            if (viaShizuku && !paired) {
                // Nothing to pair: Shizuku carries the commands. Choosing "Built-in broker only"
                // under "Privileged access via" brings the pairing steps back.
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.device_setup_ready_shizuku_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            if (paired) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        when (brokerStatus) {
                            EmbeddedAdbShell.BrokerStatus.OK ->
                                if (viaShizuku) R.string.device_setup_ready_shizuku_body
                                else R.string.device_setup_ready_body
                            EmbeddedAdbShell.BrokerStatus.REJECTED -> R.string.device_setup_rejected_body
                            EmbeddedAdbShell.BrokerStatus.PIN_MISMATCH -> R.string.broker_pin_mismatch_body
                            EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF ->
                                R.string.broker_blocked_wireless_debugging
                            EmbeddedAdbShell.BrokerStatus.NO_SERVICE ->
                                R.string.broker_blocked_no_service
                            EmbeddedAdbShell.BrokerStatus.NOT_PAIRED ->
                                R.string.broker_blocked_not_paired
                            EmbeddedAdbShell.BrokerStatus.SHIZUKU_UNAVAILABLE ->
                                R.string.broker_blocked_shizuku
                            null -> R.string.device_setup_checking_body
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connected || brokerStatus == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Wireless debugging being off does not invalidate the pairing, so that case
                    // is sent to the toggle rather than being told to pair again.
                    if (brokerStatus == EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF ||
                        brokerStatus == EmbeddedAdbShell.BrokerStatus.NO_SERVICE
                    ) {
                        Button(onClick = { openWirelessDebugging(context) }) {
                            Text(stringResource(R.string.device_setup_open_wireless))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(enabled = !forgetting, onClick = {
                        val trigger = BrokerTrigger.fromTap(context, "forget_pairing")
                        forgetting = true
                        scope.launch {
                            // Held against the status monitor so no check connects mid-unpair (the
                            // TLS failure of a connect with a just-removed key was audited as a
                            // failed verify); the check that follows finds no key and answers
                            // NOT_PAIRED without connecting.
                            val result = BrokerStatusMonitor.exclusive {
                                withContext(Dispatchers.IO) {
                                    PrivilegedSetup.resetToStock(context, trigger)
                                }
                            }
                            forgetOutcome = forgetOutcomeOf(result)
                            forgetting = false
                            BrokerStatusMonitor.invalidate()
                            statusTick++
                        }
                    }) {
                        Text(
                            stringResource(
                                if (brokerStatus == EmbeddedAdbShell.BrokerStatus.REJECTED)
                                    R.string.broker_repair
                                else R.string.device_setup_forget_pairing
                            )
                        )
                    }
                }
                return@Column
            }

            Spacer(modifier = Modifier.height(10.dp))
            val steps = if (!developerOptionsOn) {
                listOf(
                    R.string.device_setup_step_dev_1,
                    R.string.device_setup_step_dev_2,
                    R.string.device_setup_step_dev_3
                )
            } else {
                listOf(
                    R.string.device_setup_step_pair_1,
                    R.string.device_setup_step_pair_2,
                    R.string.device_setup_step_pair_3
                )
            }
            steps.forEachIndexed { index, res ->
                Step(number = index + 1, text = stringResource(res))
            }

            val warning = when {
                !notificationsAllowed -> R.string.device_setup_needs_notifications
                doNotDisturbOn -> R.string.device_setup_dnd_on
                else -> null
            }
            if (warning != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = {
                forgetOutcome = null
                armPairing(context)
                armToken++
                if (developerOptionsOn) openWirelessDebugging(context) else openAboutPhone(context)
            }) {
                Text(
                    stringResource(
                        when {
                            !developerOptionsOn -> R.string.device_setup_open_about
                            wirelessDebuggingOn -> R.string.device_setup_open_pairing
                            else -> R.string.device_setup_open_wireless
                        }
                    )
                )
            }
        }
    }
}

private const val OUTCOME_UNPAIRED = "unpaired"
private const val OUTCOME_MANUAL = "manual"
private const val SUFFIX_PERMISSION_KEPT = "+permission"

private fun forgetOutcomeOf(result: PrivilegedSetup.ResetResult): String =
    (if (result.unpaired) OUTCOME_UNPAIRED else OUTCOME_MANUAL) +
        (if (result.writeSecureSettingsStillHeld) SUFFIX_PERMISSION_KEPT else "")

/** What "Forget pairing" achieved, and the step left to the user when the broker could not unpair. */
@Composable
private fun ForgetOutcomeNotice(outcome: String, onOpenWireless: () -> Unit) {
    val manual = outcome.startsWith(OUTCOME_MANUAL)
    val keyName = EmbeddedAdbInit.keyName(LocalContext.current.packageName)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = if (manual) {
            stringResource(R.string.broker_forget_manual, keyName)
        } else {
            stringResource(R.string.broker_forget_done)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (manual) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (outcome.endsWith(SUFFIX_PERMISSION_KEPT)) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.broker_forget_permission_kept),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (manual) {
        Spacer(modifier = Modifier.height(6.dp))
        Button(onClick = onOpenWireless) {
            // Not the pairing button's label: this one only opens the page, to remove the entry.
            Text(stringResource(R.string.broker_forget_open_wireless, keyName))
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Bedtime mode and Do Not Disturb both land here, and both can hide the pairing code. */
private fun doNotDisturbActive(context: Context): Boolean =
    runCatching {
        Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 0
    }.getOrDefault(false)

private fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun developerOptionsEnabled(context: Context): Boolean =
    runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

/** Wireless debugging has no public intent action; fall back to Developer options. */
private fun openWirelessDebugging(context: Context) {
    val wireless = Intent("android.settings.ADB_WIRELESS_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (wireless.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(wireless) }.onSuccess { return }
    }
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openAboutPhone(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** How long a tap on the setup button keeps the pairing watcher (foreground service + mDNS) alive. */
private const val PAIRING_WINDOW_MS = 5 * 60_000L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun armPairing(context: Context) {
    // Wireless debugging, and the mDNS pairing service the watcher listens for, start at Android 11.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        ContextCompat.startForegroundService(context, AdbPairingService.startIntent(context))
    }
}

private fun stopPairing(context: Context) {
    runCatching { context.stopService(AdbPairingService.stopIntent(context)) }
}
