package rocks.pkb.plektra

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import rocks.pkb.plektra.inputmethod.trackpad.ShizukuTrackpadDeviceDiscovery
import rocks.pkb.plektra.inputmethod.trackpad.TrackpadInputDevice
import rocks.pkb.plektra.inputmethod.trackpad.TrackpadInputDeviceDiscovery
import kotlinx.coroutines.delay

/**
 * Settings screen for trackpad gesture suggestions.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TrackpadGestureSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var trackpadGesturesEnabled by remember {
        mutableStateOf(SettingsManager.getTrackpadGesturesEnabled(context))
    }
    var trackpadGestureAddWordEnabled by remember {
        mutableStateOf(SettingsManager.getTrackpadGestureAddWordEnabled(context))
    }
    var trackpadGestureAddWordFullWidthEnabled by remember {
        mutableStateOf(SettingsManager.getTrackpadGestureAddWordFullWidthEnabled(context))
    }
    var suggestionSwipeThreshold by remember {
        mutableStateOf(SettingsManager.getTrackpadSuggestionSwipeThreshold(context))
    }
    var deleteSwipeThreshold by remember {
        mutableStateOf(SettingsManager.getTrackpadDeleteSwipeThreshold(context))
    }
    var showTutorialDialog by remember { mutableStateOf(false) }
    var showSensitivitySettings by remember { mutableStateOf(settingsChild(context, "trackpad") == "sensitivity") }
    var shizukuStatus by remember { mutableStateOf(ShizukuStatus.NotConnected) }
    var trackpadProvider by remember { mutableStateOf(SettingsManager.getTrackpadProvider(context)) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var shizukuDevice by remember { mutableStateOf(SettingsManager.getTrackpadShizukuDevice(context)) }
    var shizukuDeviceMenuExpanded by remember { mutableStateOf(false) }
    var shizukuDevices by remember { mutableStateOf<List<TrackpadInputDevice>>(emptyList()) }
    var shizukuDeviceDiscoveryFailed by remember { mutableStateOf(false) }
    var swipeToDelete by remember { mutableStateOf(SettingsManager.getSwipeToDelete(context)) }
    var swipeToDeleteProvider by remember { mutableStateOf(SettingsManager.getSwipeToDeleteProvider(context)) }
    var swipeToDeleteProviderMenuExpanded by remember { mutableStateOf(false) }
    val highlightedSettingId = LocalSettingHighlightId.current
    val trackpadProviderOptions = listOf(
        SettingsManager.TRACKPAD_PROVIDER_NATIVE_IME to stringResource(R.string.trackpad_provider_native_ime),
        SettingsManager.TRACKPAD_PROVIDER_SHIZUKU to stringResource(R.string.trackpad_provider_shizuku)
    )
    val swipeToDeleteProviderOptions = listOf(
        SettingsManager.SWIPE_TO_DELETE_PROVIDER_NATIVE_IME to stringResource(R.string.swipe_to_delete_provider_native_ime),
        SettingsManager.SWIPE_TO_DELETE_PROVIDER_TITAN2_KEYCODE to stringResource(R.string.swipe_to_delete_provider_titan2_keycode)
    )


    LaunchedEffect(highlightedSettingId) {
        when (highlightedSettingId) {
            SettingLinkIds.TRACKPAD_SUGGESTION_SWIPE_THRESHOLD,
            SettingLinkIds.TRACKPAD_DELETE_SWIPE_THRESHOLD -> showSensitivitySettings = true
            "trackpad.add_word",
            "trackpad.add_word_full_width",
            "trackpad.swipe_to_delete",
            "trackpad.swipe_to_delete_provider",
            SettingLinkIds.TRACKPAD_GESTURES_ENABLED,
            SettingLinkIds.TRACKPAD_PROVIDER,
            SettingLinkIds.TRACKPAD_SHIZUKU_DEVICE,
            SettingLinkIds.TRACKPAD_SENSITIVITY,
            SettingLinkIds.TRACKPAD_DEBUG -> showSensitivitySettings = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            shizukuStatus = resolveShizukuStatus()
            delay(2000)
        }
    }
    LaunchedEffect(trackpadProvider, shizukuStatus) {
        if (
            trackpadProvider == SettingsManager.TRACKPAD_PROVIDER_SHIZUKU &&
            shizukuStatus == ShizukuStatus.Connected
        ) {
            runCatching { ShizukuTrackpadDeviceDiscovery.discover() }
                .onSuccess { devices ->
                    shizukuDevices = TrackpadInputDeviceDiscovery.selectableDevices(devices)
                    shizukuDeviceDiscoveryFailed = false
                }
                .onFailure {
                    shizukuDevices = emptyList()
                    shizukuDeviceDiscoveryFailed = true
                }
        }
    }

    Scaffold(
        topBar = {
            PlektraSettingsHeader(
                title = stringResource(
                    if (showSensitivitySettings) {
                        R.string.trackpad_sensitivity_title
                    } else {
                        R.string.trackpad_gestures_title
                    }
                ),
                onBack = {
                    if (showSensitivitySettings) {
                        context.settingsActivity().finish()
                    } else {
                        onBack()
                    }
                }
            )
        }
    ) { paddingValues ->
        SettingsSectionScrollColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(paddingValues)
        ) {
            if (showSensitivitySettings) {
                TrackpadSensitivitySettings(
                    suggestionSwipeThreshold = suggestionSwipeThreshold,
                    onSuggestionSwipeThresholdChange = { newValue ->
                        suggestionSwipeThreshold = newValue
                        SettingsManager.setTrackpadSuggestionSwipeThreshold(context, newValue)
                    },
                    deleteSwipeThreshold = deleteSwipeThreshold,
                    onDeleteSwipeThresholdChange = { newValue ->
                        deleteSwipeThreshold = newValue
                        SettingsManager.setTrackpadDeleteSwipeThreshold(context, newValue)
                    }
                )
                return@SettingsSectionScrollColumn
            }

            // Enable/Disable Toggle
            SettingsToggleRow(
                title = stringResource(R.string.trackpad_gestures_enabled_title),
                description = stringResource(R.string.trackpad_gestures_enabled_description),
                icon = Icons.Filled.TouchApp,
                checked = trackpadGesturesEnabled,
                linkId = SettingLinkIds.TRACKPAD_GESTURES_ENABLED,
                extra = { FeatureStatusIcon(FeatureStatus.Experimental) },
                onCheckedChange = { enabled ->
                    trackpadGesturesEnabled = enabled
                    SettingsManager.setTrackpadGesturesEnabled(context, enabled)
                }
            )

            if (DeviceSpecific.isTitan2EliteDevice()) {
                SettingsSupportingText(stringResource(R.string.trackpad_titan2_elite_scroll_assistant_note))
            }

            SettingsToggleRow(
                title = stringResource(R.string.trackpad_gesture_add_word_title),
                description = stringResource(R.string.trackpad_gesture_add_word_description),
                checked = trackpadGestureAddWordEnabled,
                enabled = trackpadGesturesEnabled,
                dependent = true,
                linkId = "trackpad.add_word",
                onCheckedChange = { enabled ->
                    trackpadGestureAddWordEnabled = enabled
                    SettingsManager.setTrackpadGestureAddWordEnabled(context, enabled)
                }
            )

            SettingsToggleRow(
                title = stringResource(R.string.trackpad_gesture_add_word_full_width_title),
                description = stringResource(R.string.trackpad_gesture_add_word_full_width_description),
                checked = trackpadGestureAddWordFullWidthEnabled,
                enabled = trackpadGesturesEnabled && trackpadGestureAddWordEnabled,
                dependent = true,
                linkId = "trackpad.add_word_full_width",
                onCheckedChange = { enabled ->
                    trackpadGestureAddWordFullWidthEnabled = enabled
                    SettingsManager.setTrackpadGestureAddWordFullWidthEnabled(context, enabled)
                }
            )

            SettingsDropdownRow(
                title = stringResource(R.string.trackpad_provider_title),
                value = trackpadProviderOptions.firstOrNull { it.first == trackpadProvider }?.second ?: trackpadProvider,
                options = trackpadProviderOptions,
                expanded = providerMenuExpanded,
                onExpandedChange = { providerMenuExpanded = it },
                onSelected = { value ->
                    trackpadProvider = value
                    SettingsManager.setTrackpadProvider(context, value)
                    providerMenuExpanded = false
                },
                icon = Icons.Filled.Tune,
                linkId = SettingLinkIds.TRACKPAD_PROVIDER
            )

            if (trackpadProvider == SettingsManager.TRACKPAD_PROVIDER_SHIZUKU) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val (statusIcon, statusTint, statusText) = when (shizukuStatus) {
                            ShizukuStatus.Connected -> Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.trackpad_gestures_shizuku_connected))
                            ShizukuStatus.NotAuthorized -> Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.tertiary, stringResource(R.string.trackpad_gestures_shizuku_not_authorized))
                            ShizukuStatus.NotConnected -> Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, stringResource(R.string.trackpad_gestures_shizuku_not_connected))
                        }
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusTint
                        )
                    }

                    val automaticLabel = stringResource(R.string.trackpad_shizuku_device_automatic)
                    val automaticDevice = TrackpadInputDeviceDiscovery.selectAutomatic(shizukuDevices)
                    val selectedDeviceLabel = when (shizukuDevice) {
                        SettingsManager.TRACKPAD_SHIZUKU_DEVICE_AUTO -> automaticDevice?.let {
                            "$automaticLabel, ${it.displayName}"
                        } ?: automaticLabel
                        else -> shizukuDevices.firstOrNull { it.path == shizukuDevice }?.displayName
                            ?: stringResource(R.string.trackpad_shizuku_device_unavailable, shizukuDevice)
                    }
                    val deviceSupportingText: (@Composable () -> Unit)? = when {
                        shizukuDeviceDiscoveryFailed -> {
                            { Text(stringResource(R.string.trackpad_shizuku_device_discovery_failed)) }
                        }
                        shizukuStatus == ShizukuStatus.Connected &&
                            shizukuDevice == SettingsManager.TRACKPAD_SHIZUKU_DEVICE_AUTO &&
                            automaticDevice == null -> {
                            { Text(stringResource(R.string.trackpad_shizuku_device_no_candidate)) }
                        }
                        else -> null
                    }
                    ExposedDropdownMenuBox(
                        expanded = shizukuDeviceMenuExpanded,
                        onExpandedChange = { shizukuDeviceMenuExpanded = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .settingRow(SettingLinkIds.TRACKPAD_SHIZUKU_DEVICE)
                    ) {
                        OutlinedTextField(
                            value = selectedDeviceLabel,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            enabled = shizukuStatus == ShizukuStatus.Connected ||
                                shizukuDevice != SettingsManager.TRACKPAD_SHIZUKU_DEVICE_AUTO,
                            label = { Text(stringResource(R.string.trackpad_shizuku_device_title)) },
                            supportingText = deviceSupportingText,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = shizukuDeviceMenuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = shizukuDeviceMenuExpanded,
                            onDismissRequest = { shizukuDeviceMenuExpanded = false }
                        ) {
                            val choices = buildList {
                                add(SettingsManager.TRACKPAD_SHIZUKU_DEVICE_AUTO to automaticLabel)
                                addAll(shizukuDevices.map { it.path to it.displayName })
                                if (
                                    shizukuDevice != SettingsManager.TRACKPAD_SHIZUKU_DEVICE_AUTO &&
                                    none { it.first == shizukuDevice }
                                ) {
                                    add(
                                        shizukuDevice to context.getString(
                                            R.string.trackpad_shizuku_device_unavailable,
                                            shizukuDevice
                                        )
                                    )
                                }
                            }
                            choices.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        shizukuDevice = value
                                        SettingsManager.setTrackpadShizukuDevice(context, value)
                                        shizukuDeviceMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        if (shizukuDevice == value) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }
            }

            if (trackpadProvider == SettingsManager.TRACKPAD_PROVIDER_NATIVE_IME) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.trackpad_provider_native_ime_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SettingsToggleRow(
                title = stringResource(R.string.swipe_to_delete_title),
                description = stringResource(R.string.swipe_to_delete_description),
                icon = Icons.AutoMirrored.Filled.Backspace,
                checked = swipeToDelete,
                linkId = "trackpad.swipe_to_delete",
                onCheckedChange = { enabled ->
                    swipeToDelete = enabled
                    SettingsManager.setSwipeToDelete(context, enabled)
                }
            )
            SettingsDropdownRow(
                title = stringResource(R.string.swipe_to_delete_provider_title),
                value = swipeToDeleteProviderOptions.firstOrNull { it.first == swipeToDeleteProvider }?.second ?: swipeToDeleteProvider,
                options = swipeToDeleteProviderOptions,
                expanded = swipeToDeleteProviderMenuExpanded,
                onExpandedChange = { swipeToDeleteProviderMenuExpanded = it },
                onSelected = { value ->
                    swipeToDeleteProvider = value
                    SettingsManager.setSwipeToDeleteProvider(context, value)
                    swipeToDeleteProviderMenuExpanded = false
                },
                enabled = swipeToDelete,
                dependent = true,
                linkId = "trackpad.swipe_to_delete_provider"
            )

            SettingsNavigationRow(
                title = stringResource(R.string.trackpad_sensitivity_title),
                description = stringResource(R.string.trackpad_sensitivity_description),
                icon = Icons.Filled.Speed,
                linkId = SettingLinkIds.TRACKPAD_SENSITIVITY,
                onClick = { openSettingsChild(context, "trackpad", "sensitivity") },
                trailing = { SettingsValueText("${suggestionSwipeThreshold.toInt()} / ${deleteSwipeThreshold.toInt()}") }
            )

            SettingsNavigationRow(
                title = stringResource(R.string.trackpad_debug_title),
                description = stringResource(R.string.trackpad_debug_description),
                icon = Icons.Filled.BugReport,
                linkId = SettingLinkIds.TRACKPAD_DEBUG,
                onClick = {
                    context.startActivity(Intent(context, TrackpadDebugActivity::class.java))
                }
            )

            // Show Tutorial Button
            SettingsNavigationRow(
                title = stringResource(R.string.trackpad_gestures_show_tutorial),
                description = stringResource(R.string.trackpad_gestures_tutorial_description),
                icon = Icons.Filled.Info,
                onClick = { showTutorialDialog = true }
            )

            if (trackpadProvider == SettingsManager.TRACKPAD_PROVIDER_SHIZUKU) {
                SettingsNavigationRow(
                    title = stringResource(R.string.trackpad_gestures_install_shizuku),
                    description = stringResource(R.string.trackpad_gestures_install_shizuku_description),
                    icon = Icons.Filled.Download,
                    onClick = {
                        val url = context.getString(R.string.trackpad_gestures_shizuku_url)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
            }
        }
    }

    // Tutorial Dialog
    if (showTutorialDialog) {
        TrackpadTutorialDialog(
            onDismiss = { showTutorialDialog = false }
        )
    }
}

@Composable
private fun TrackpadSensitivitySettings(
    suggestionSwipeThreshold: Float,
    onSuggestionSwipeThresholdChange: (Float) -> Unit,
    deleteSwipeThreshold: Float,
    onDeleteSwipeThresholdChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        TrackpadSensitivitySlider(
            title = stringResource(R.string.trackpad_suggestion_swipe_threshold_title),
            description = stringResource(R.string.trackpad_suggestion_swipe_threshold_description),
            value = suggestionSwipeThreshold,
            linkId = SettingLinkIds.TRACKPAD_SUGGESTION_SWIPE_THRESHOLD,
            onValueChange = onSuggestionSwipeThresholdChange
        )
        TrackpadSensitivitySlider(
            title = stringResource(R.string.trackpad_delete_swipe_threshold_title),
            description = stringResource(R.string.trackpad_delete_swipe_threshold_description),
            value = deleteSwipeThreshold,
            linkId = SettingLinkIds.TRACKPAD_DELETE_SWIPE_THRESHOLD,
            onValueChange = onDeleteSwipeThresholdChange
        )
    }
}

@Composable
private fun TrackpadSensitivitySlider(
    title: String,
    description: String,
    value: Float,
    linkId: String,
    onValueChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .settingRow(linkId)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Text(
                    text = value.toInt().toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = SettingsManager.getMinTrackpadSwipeThreshold()..SettingsManager.getMaxTrackpadSwipeThreshold(),
                steps = 10
            )
        }
    }
}

/**
 * Tutorial dialog for trackpad gestures.
 */
@Composable
fun TrackpadTutorialDialog(
    onDismiss: () -> Unit
) {
    var currentPage by remember { mutableStateOf(0) }
    val pages = listOf(
        TutorialPage(
            title = stringResource(R.string.trackpad_tutorial_welcome_title),
            description = stringResource(R.string.trackpad_tutorial_welcome_description),
            icon = Icons.Filled.TouchApp
        ),
        TutorialPage(
            title = stringResource(R.string.trackpad_tutorial_how_it_works_title),
            description = stringResource(R.string.trackpad_tutorial_how_it_works_description),
            icon = Icons.Filled.Swipe
        ),
        TutorialPage(
            title = stringResource(R.string.trackpad_tutorial_shizuku_required_title),
            description = stringResource(R.string.trackpad_tutorial_shizuku_required_description),
            icon = Icons.Filled.Security
        ),
        TutorialPage(
            title = stringResource(R.string.trackpad_tutorial_tips_title),
            description = stringResource(R.string.trackpad_tutorial_tips_description),
            icon = Icons.Filled.Lightbulb
        )
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.trackpad_tutorial_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = pages[currentPage].icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = pages[currentPage].title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = pages[currentPage].description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Page indicator
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    pages.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (index == currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (currentPage < pages.size - 1) {
                        currentPage++
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text(
                    text = if (currentPage < pages.size - 1)
                        stringResource(R.string.trackpad_tutorial_next)
                    else
                        stringResource(R.string.trackpad_tutorial_complete)
                )
            }
        },
        dismissButton = if (currentPage > 0) {
            {
                TextButton(onClick = { currentPage-- }) {
                    Text(text = stringResource(R.string.trackpad_tutorial_previous))
                }
            }
        } else null
    )
}

/**
 * Data class representing a tutorial page.
 */
private data class TutorialPage(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
