package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.ring.NotificationRingActivity
import rocks.pkb.plektra.ring.NotificationRingPolicy
import rocks.pkb.plektra.ring.NotificationRingSetup
import rocks.pkb.plektra.ring.RingAdjustActivity
import rocks.pkb.plektra.ring.RingBacklight
import rocks.pkb.plektra.ring.RingBrightness
import rocks.pkb.plektra.ring.RingPalette
import rocks.pkb.plektra.ui.color.ColorPickerDialog
import rocks.pkb.plektra.ui.color.ColorSwatch
import rocks.pkb.plektra.ui.rememberVerifiedBrokerStatus

/** The settings-child section and page that open the ring's own page from the Titan 2 Elite tools screen. */
internal const val T2E_TOOLS_CHILD_SECTION = "titan2_tools"
internal const val T2E_TOOLS_RING_PAGE = "ring"

/**
 * The notification ring's entry on the Titan 2 Elite tools screen: one row that opens the ring's own
 * page ([NotificationRingScreen]), a settings child page.
 */
@Composable
fun NotificationRingSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_NOTIFICATION_RING) {
                openSettingsChild(context, T2E_TOOLS_CHILD_SECTION, T2E_TOOLS_RING_PAGE)
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ring_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.ring_row_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * The ring's own page: the switch, the three grants it needs and where each stands, and the
 * two knobs that matter — how long the screen stays on and how bright the ring is.
 *
 * The one grant that is not the ring's own, [RingBacklight]'s WRITE_SECURE_SETTINGS, is offered
 * here too, next to the "keep the keyboard dark" switch it belongs to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationRingScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var enabled by remember { mutableStateOf(SettingsManager.isNotificationRingEnabled(context)) }
    var minutes by remember { mutableIntStateOf(SettingsManager.getNotificationRingMinutes(context)) }
    var brightness by remember { mutableStateOf(SettingsManager.getNotificationRingBrightness(context)) }
    var icons by remember { mutableStateOf(SettingsManager.isNotificationRingIconsEnabled(context)) }
    var keyboardDark by remember { mutableStateOf(SettingsManager.isRingKeyboardDarkEnabled(context)) }
    var canDarkenKeyboard by remember { mutableStateOf(RingBacklight.canWrite(context)) }
    var appColors by remember { mutableStateOf(SettingsManager.getNotificationRingAppColors(context)) }
    var defaultColor by remember { mutableStateOf(SettingsManager.getNotificationRingDefaultColor(context)) }
    var pickingDefaultColor by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    var colouringPackage by remember { mutableStateOf<String?>(null) }
    var listenerGranted by remember { mutableStateOf(NotificationRingSetup.isListenerGranted(context)) }
    var fullScreenAllowed by remember { mutableStateOf(NotificationRingSetup.canUseFullScreenIntent(context)) }
    var postAllowed by remember { mutableStateOf(NotificationRingSetup.canPostNotifications(context)) }
    var granting by remember { mutableStateOf(false) }
    // Verified rather than "a key is stored": the listener and full-screen grants are handed
    // out through the broker, so offering them when nothing can connect just fails silently.
    val brokerStatus by rememberVerifiedBrokerStatus()
    val paired = brokerStatus == EmbeddedAdbShell.BrokerStatus.OK

    fun refresh() {
        listenerGranted = NotificationRingSetup.isListenerGranted(context)
        fullScreenAllowed = NotificationRingSetup.canUseFullScreenIntent(context)
        postAllowed = NotificationRingSetup.canPostNotifications(context)
        canDarkenKeyboard = RingBacklight.canWrite(context)
    }

    // Grants change in system settings, behind this screen: re-read on every return.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Every broker call here is a tap (finding 1.3g), and ends with Wireless debugging off when
    // the user keeps that setting on (finding 1.3e).
    fun grant() {
        if (granting) return
        granting = true
        val trigger = BrokerTrigger.fromTap(context, "ring_grant")
        NotificationRingSetup.grantViaBroker(context, trigger) {
            // Worker thread; the composition reads state on the next frame.
            granting = false
            refresh()
        }
        PrivilegedSetup.queueFinishSetup(context, trigger)
    }

    // The keyboard-dark permission follows the ring and its switch (a WRITE_SECURE_SETTINGS holder).
    fun updateKeyboardDarkHolder(source: String) {
        val trigger = BrokerTrigger.fromTap(context, source)
        if (NotificationRingSetup.updateKeyboardDarkHolder(context, trigger)) {
            PrivilegedSetup.queueFinishSetup(context, trigger)
        }
    }

    BackHandler { onBack() }

    if (pickingApp) {
        AppPickerDialog(
            onAppSelected = { app -> colouringPackage = app.packageName },
            onDismiss = { pickingApp = false }
        )
    }
    if (pickingDefaultColor) {
        RingColorDialog(
            title = stringResource(R.string.ring_default_color_title),
            current = defaultColor,
            onPick = { argb ->
                SettingsManager.setNotificationRingDefaultColor(context, argb)
                defaultColor = argb
                pickingDefaultColor = false
            },
            onDismiss = { pickingDefaultColor = false }
        )
    }
    colouringPackage?.let { pkg ->
        RingColorDialog(
            title = appLabel(context, pkg),
            current = appColors[pkg],
            onPick = { argb ->
                SettingsManager.setNotificationRingAppColor(context, pkg, argb)
                appColors = SettingsManager.getNotificationRingAppColors(context)
                colouringPackage = null
            },
            onDismiss = { colouringPackage = null }
        )
    }

    Scaffold(
        topBar = {
            RingTopBar(title = stringResource(R.string.ring_title), onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = modifier.fillMaxWidth().padding(padding).verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.ring_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_ENABLE)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ring_enable_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.ring_enable_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            SettingsManager.setNotificationRingEnabled(context, on)
                            if (on) grant() else updateKeyboardDarkHolder("ring_toggle")
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            GrantRow(
                title = stringResource(R.string.ring_access_title),
                granted = listenerGranted,
                grantedText = stringResource(R.string.ring_access_granted),
                missingText = stringResource(R.string.ring_access_missing),
                onOpenSettings = { context.startActivity(NotificationRingSetup.listenerSettingsIntent()) }
            )
            GrantRow(
                title = stringResource(R.string.ring_fullscreen_title),
                granted = fullScreenAllowed,
                grantedText = stringResource(R.string.ring_fullscreen_granted),
                missingText = stringResource(R.string.ring_fullscreen_missing),
                onOpenSettings = { context.startActivity(NotificationRingSetup.fullScreenSettingsIntent(context)) }
            )
            GrantRow(
                title = stringResource(R.string.ring_post_title),
                granted = postAllowed,
                grantedText = stringResource(R.string.ring_post_granted),
                missingText = stringResource(R.string.ring_post_missing),
                onOpenSettings = { context.startActivity(NotificationRingSetup.appNotificationSettingsIntent(context)) }
            )

            val allGranted = listenerGranted && fullScreenAllowed && postAllowed
            if (!allGranted) {
                if (paired) {
                    Button(
                        onClick = { grant() },
                        enabled = !granting,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (granting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.ring_grant_via_broker))
                    }
                } else {
                    PrivilegedBlockedNotice(
                        onOpenPairing = null,
                        pairingRes = R.string.ring_grant_pairing_hint
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Column(
                modifier = Modifier.fillMaxWidth()
                    .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_DURATION)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ring_duration_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.ring_duration_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.ring_duration_minutes, minutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { minutes = it.toInt() },
                    onValueChangeFinished = {
                        SettingsManager.setNotificationRingMinutes(context, minutes)
                    },
                    valueRange = SettingsManager.NOTIFICATION_RING_MIN_MINUTES.toFloat()..
                        SettingsManager.NOTIFICATION_RING_MAX_MINUTES.toFloat(),
                    // Continuous rather than stepped. A stop per minute draws 59 tick marks, which
                    // on a 574dp-wide screen is a dotted smear; rounding the value on the way in
                    // lands on whole minutes just the same.
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_BRIGHTNESS)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ring_brightness_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RingBrightness.entries.forEach { level ->
                        FilterChip(
                            selected = brightness == level,
                            onClick = {
                                brightness = level
                                SettingsManager.setNotificationRingBrightness(context, level)
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (level) {
                                            RingBrightness.DIM -> R.string.ring_brightness_dim
                                            RingBrightness.NORMAL -> R.string.ring_brightness_normal
                                            RingBrightness.BRIGHT -> R.string.ring_brightness_bright
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
            }

            Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ring_icons_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.ring_icons_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = icons,
                        onCheckedChange = { on ->
                            icons = on
                            SettingsManager.setNotificationRingIconsEnabled(context, on)
                        }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_KEYBOARD_DARK)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ring_keyboard_dark_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.ring_keyboard_dark_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Without the permission the switch is simply inert, and a switch that
                        // reads on while doing nothing is the shape of bug this app keeps finding.
                        if (!canDarkenKeyboard) {
                            Text(
                                text = stringResource(R.string.ring_keyboard_dark_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            // the grant button used to cover only the three ring grants, so a
                            // paired phone could never get this one from here.
                            if (paired) {
                                TextButton(onClick = { grant() }, enabled = !granting) {
                                    Text(stringResource(R.string.ring_grant_via_broker))
                                }
                            }
                        }
                    }
                    Switch(
                        checked = keyboardDark,
                        onCheckedChange = { on ->
                            keyboardDark = on
                            SettingsManager.setRingKeyboardDarkEnabled(context, on)
                            if (on && enabled && !canDarkenKeyboard) {
                                grant()
                            } else {
                                updateKeyboardDarkHolder("ring_keyboard_dark_toggle")
                            }
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth()
                    .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_DEFAULT_COLOR) { pickingDefaultColor = true }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ring_default_color_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.ring_default_color_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ColorDot(argb = defaultColor, selected = false, size = 28.dp)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_APP_COLORS)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ring_app_colors_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.ring_app_colors_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            appColors.entries.sortedBy { appLabel(context, it.key).lowercase() }.forEach { (pkg, argb) ->
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable { colouringPackage = pkg }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ColorDot(argb = argb, selected = false, size = 22.dp)
                        Text(
                            text = appLabel(context, pkg),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            SettingsManager.setNotificationRingAppColor(context, pkg, null)
                            appColors = SettingsManager.getNotificationRingAppColors(context)
                        }) { Text(stringResource(R.string.ring_app_colors_remove)) }
                    }
                }
            }
            OutlinedButton(
                onClick = { pickingApp = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(stringResource(R.string.ring_app_colors_add)) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Column(modifier = Modifier.fillMaxWidth().settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_ADJUST)) {
                Text(
                    text = stringResource(R.string.ring_adjust_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                OutlinedButton(
                    onClick = { context.startActivity(Intent(context, RingAdjustActivity::class.java)) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.ring_adjust)) }
            }

            Text(
                text = stringResource(R.string.ring_try_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            OutlinedButton(
                onClick = { context.startActivity(NotificationRingActivity.demoIntent(context)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(stringResource(R.string.ring_try)) }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, size: androidx.compose.ui.unit.Dp, onClick: (() -> Unit)? = null) {
    ColorSwatch(argb = argb, selected = selected, size = size, onClick = onClick)
}

@Composable
private fun RingColorDialog(
    title: String,
    current: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val tooDark = stringResource(R.string.ring_color_too_dark)
    ColorPickerDialog(
        title = title,
        current = current ?: RingPalette.GREEN.argb,
        swatches = RingPalette.entries.map { it.argb },
        subtitle = stringResource(R.string.ring_app_colors_pick),
        warning = { argb -> if (NotificationRingPolicy.isTooDarkForRing(argb)) tooDark else null },
        onPick = onPick,
        onDismiss = onDismiss
    )
}

private fun appLabel(context: Context, packageName: String): String =
    runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

@Composable
private fun GrantRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    missingText: String,
    onOpenSettings: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    text = if (granted) grantedText else missingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!granted) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.ring_grant_open_settings)) }
            }
        }
    }
}


@Composable
private fun RingTopBar(title: String, onBack: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back_content_description)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
