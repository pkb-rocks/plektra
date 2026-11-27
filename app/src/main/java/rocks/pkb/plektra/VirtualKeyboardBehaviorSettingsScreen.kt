package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import rocks.pkb.plektra.R

@Composable
internal fun VirtualKeyboardBehaviorSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenKeyboardTheme: () -> Unit
) {
    val context = LocalContext.current
    var softwareKeyboardLayoutStyle by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardLayoutStyle(context))
    }
    var nearestKeyTouchEnabled by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardNearestKeyTouchEnabled(context))
    }
    var longPressLayerPopupEnabled by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardLongPressLayerPopupEnabled(context))
    }
    var longPressLayerPopupBelowKey by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardLongPressLayerPopupBelowKey(context))
    }
    var numberRowEnabled by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardNumberRowEnabled(context))
    }
    var emojiPageInCycle by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardEmojiPageInCycle(context))
    }
    var hardwareKeypadAutoOpen by remember {
        mutableStateOf(SettingsManager.getHardwareKeypadAutoOpen(context))
    }
    var leftModifierKey by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardLeftModifierKey(context))
    }
    var rightModifierKey by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardRightModifierKey(context))
    }
    var keyHintsEnabled by remember {
        mutableStateOf(SettingsManager.getSoftwareKeyboardKeyHintsEnabled(context))
    }
    var showSoftwareKeyboardLayoutStyleMenu by remember { mutableStateOf(false) }
    var ctrlShortcutDisplay by remember { mutableStateOf(SettingsManager.getSoftwareCtrlShortcutDisplay(context)) }
    var showCtrlShortcutDisplay by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val prefs = SettingsManager.getPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "software_ctrl_shortcut_display" -> {
                    ctrlShortcutDisplay = SettingsManager.getSoftwareCtrlShortcutDisplay(context)
                }
                "software_keyboard_left_modifier_key" -> {
                    leftModifierKey = SettingsManager.getSoftwareKeyboardLeftModifierKey(context)
                }
                "software_keyboard_right_modifier_key" -> {
                    rightModifierKey = SettingsManager.getSoftwareKeyboardRightModifierKey(context)
                }
                "software_keyboard_long_press_layer_popup_enabled" -> {
                    longPressLayerPopupEnabled =
                        SettingsManager.getSoftwareKeyboardLongPressLayerPopupEnabled(context)
                }
                SettingsManager.KEY_SOFTWARE_KEYBOARD_KEY_HINTS_ENABLED -> {
                    keyHintsEnabled = SettingsManager.getSoftwareKeyboardKeyHintsEnabled(context)
                }
                "software_keyboard_long_press_layer_popup_below_key" -> {
                    longPressLayerPopupBelowKey =
                        SettingsManager.getSoftwareKeyboardLongPressLayerPopupBelowKey(context)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }


    Scaffold(
        topBar = {
            PlektraSettingsHeader(title = stringResource(R.string.virtual_keyboard_behaviour_title), onBack = onBack)
        }
    ) { paddingValues ->
        SettingsSectionScrollColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(paddingValues)
        ) {
            SettingsSectionDivider(stringResource(R.string.on_screen_keyboard_section_layout))
            fun ctrlDisplayLabel(value: SettingsManager.SoftwareCtrlShortcutDisplay) = context.getString(when (value) {
                SettingsManager.SoftwareCtrlShortcutDisplay.ICONS_WITH_LETTERS -> R.string.software_ctrl_shortcut_icons_letters
                SettingsManager.SoftwareCtrlShortcutDisplay.ICONS_WITH_CORNER_LETTERS -> R.string.software_ctrl_shortcut_icons_corner_letters
                SettingsManager.SoftwareCtrlShortcutDisplay.CORNER_ICONS -> R.string.software_ctrl_shortcut_corner_icons
                SettingsManager.SoftwareCtrlShortcutDisplay.LETTERS_ONLY -> R.string.software_ctrl_shortcut_letters_only
            })
            Box(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    modifier = Modifier
                        .settingRow("on_screen.ctrl_shortcut_display") { showCtrlShortcutDisplay = true }
                        .clickable { showCtrlShortcutDisplay = true },
                    headlineContent = { Text(stringResource(R.string.software_ctrl_shortcut_display_title)) },
                    supportingContent = { Text(ctrlDisplayLabel(ctrlShortcutDisplay)) },
                    leadingContent = { Icon(Icons.Filled.Keyboard, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ArrowDropDown, null) }
                )
                DropdownMenu(
                    expanded = showCtrlShortcutDisplay,
                    onDismissRequest = { showCtrlShortcutDisplay = false }
                ) {
                    SettingsManager.SoftwareCtrlShortcutDisplay.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(ctrlDisplayLabel(option)) },
                            onClick = {
                                ctrlShortcutDisplay = option
                                SettingsManager.setSoftwareCtrlShortcutDisplay(context, option)
                                showCtrlShortcutDisplay = false
                            },
                            leadingIcon = {
                                if (ctrlShortcutDisplay == option) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }

            val softwareKeyboardLayoutStyleLabel = when (softwareKeyboardLayoutStyle) {
                SettingsManager.SoftwareKeyboardLayoutStyle.COMPACT ->
                    stringResource(R.string.software_keyboard_layout_style_compact)
                SettingsManager.SoftwareKeyboardLayoutStyle.EXTENDED_ISO ->
                    stringResource(R.string.software_keyboard_layout_style_extended_iso)
                SettingsManager.SoftwareKeyboardLayoutStyle.FULL_ANSI ->
                    stringResource(R.string.software_keyboard_layout_style_full_ansi)
                SettingsManager.SoftwareKeyboardLayoutStyle.FULL_ISO ->
                    stringResource(R.string.software_keyboard_layout_style_full_iso)
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
                    .settingRow("on_screen.layout_style") { showSoftwareKeyboardLayoutStyleMenu = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
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
                            text = stringResource(R.string.software_keyboard_layout_style_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(
                                R.string.software_keyboard_layout_style_description,
                                softwareKeyboardLayoutStyleLabel
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showSoftwareKeyboardLayoutStyleMenu,
                    onDismissRequest = { showSoftwareKeyboardLayoutStyleMenu = false }
                ) {
                    listOf(
                        SettingsManager.SoftwareKeyboardLayoutStyle.COMPACT to stringResource(R.string.software_keyboard_layout_style_compact),
                        SettingsManager.SoftwareKeyboardLayoutStyle.EXTENDED_ISO to stringResource(R.string.software_keyboard_layout_style_extended_iso),
                        SettingsManager.SoftwareKeyboardLayoutStyle.FULL_ANSI to stringResource(R.string.software_keyboard_layout_style_full_ansi),
                        SettingsManager.SoftwareKeyboardLayoutStyle.FULL_ISO to stringResource(R.string.software_keyboard_layout_style_full_iso)
                    ).forEach { (style, title) ->
                        DropdownMenuItem(
                            text = { Text(title) },
                            onClick = {
                                softwareKeyboardLayoutStyle = style
                                SettingsManager.setSoftwareKeyboardLayoutStyle(context, style)
                                showSoftwareKeyboardLayoutStyleMenu = false
                            },
                            leadingIcon = {
                                if (softwareKeyboardLayoutStyle == style) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }

            ModifierTapLatchRow(
                title = stringResource(R.string.software_keyboard_number_row_title),
                linkId = "on_screen.number_row",
                description = stringResource(R.string.software_keyboard_number_row_description),
                checked = numberRowEnabled,
                onCheckedChange = { enabled ->
                    numberRowEnabled = enabled
                    SettingsManager.setSoftwareKeyboardNumberRowEnabled(context, enabled)
                }
            )

            ModifierTapLatchRow(
                title = stringResource(R.string.software_keyboard_emoji_page_in_cycle_title),
                linkId = "on_screen.emoji_page_in_cycle",
                description = stringResource(R.string.software_keyboard_emoji_page_in_cycle_description),
                checked = emojiPageInCycle,
                onCheckedChange = { enabled ->
                    emojiPageInCycle = enabled
                    SettingsManager.setSoftwareKeyboardEmojiPageInCycle(context, enabled)
                }
            )

            ModifierTapLatchRow(
                title = stringResource(R.string.hardware_keypad_auto_open_title),
                linkId = "on_screen.hardware_keypad_auto_open",
                description = stringResource(R.string.hardware_keypad_auto_open_description),
                checked = hardwareKeypadAutoOpen,
                onCheckedChange = { enabled ->
                    hardwareKeypadAutoOpen = enabled
                    SettingsManager.setHardwareKeypadAutoOpen(context, enabled)
                }
            )

            Text(
                text = stringResource(R.string.software_keyboard_modifier_keys_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SoftwareKeyboardModifierSelection(
                    title = stringResource(R.string.software_keyboard_left_modifier_key_title),
                    linkId = "on_screen.left_modifier",
                    selected = leftModifierKey,
                    modifier = Modifier.weight(1f),
                    onSelected = { selected ->
                        leftModifierKey = selected
                        SettingsManager.setSoftwareKeyboardLeftModifierKey(context, selected)
                    }
                )
                SoftwareKeyboardModifierSelection(
                    title = stringResource(R.string.software_keyboard_right_modifier_key_title),
                    linkId = "on_screen.right_modifier",
                    selected = rightModifierKey,
                    modifier = Modifier.weight(1f),
                    onSelected = { selected ->
                        rightModifierKey = selected
                        SettingsManager.setSoftwareKeyboardRightModifierKey(context, selected)
                    }
                )
            }

            SettingsSectionDivider(stringResource(R.string.on_screen_keyboard_section_touch))

            ModifierTapLatchRow(
                title = stringResource(R.string.software_keyboard_nearest_key_touch_title),
                linkId = "on_screen.nearest_key_touch",
                description = stringResource(R.string.software_keyboard_nearest_key_touch_description),
                checked = nearestKeyTouchEnabled,
                onCheckedChange = { enabled ->
                    nearestKeyTouchEnabled = enabled
                    SettingsManager.setSoftwareKeyboardNearestKeyTouchEnabled(context, enabled)
                }
            )

            ModifierTapLatchRow(
                title = stringResource(R.string.software_keyboard_long_press_layer_popup_title),
                linkId = "on_screen.layer_popup",
                description = stringResource(R.string.software_keyboard_long_press_layer_popup_description),
                checked = longPressLayerPopupEnabled,
                onCheckedChange = { enabled ->
                    longPressLayerPopupEnabled = enabled
                    SettingsManager.setSoftwareKeyboardLongPressLayerPopupEnabled(context, enabled)
                }
            )

            if (longPressLayerPopupEnabled) {
                ModifierTapLatchRow(
                    title = stringResource(R.string.software_keyboard_long_press_layer_popup_below_key_title),
                    linkId = "on_screen.layer_popup_below",
                    description = stringResource(R.string.software_keyboard_long_press_layer_popup_below_key_description),
                    checked = longPressLayerPopupBelowKey,
                    indent = true,
                    onCheckedChange = { enabled ->
                        longPressLayerPopupBelowKey = enabled
                        SettingsManager.setSoftwareKeyboardLongPressLayerPopupBelowKey(context, enabled)
                    }
                )
            }

            SettingsSectionDivider(stringResource(R.string.on_screen_keyboard_section_appearance))
            ModifierTapLatchRow(
                title = stringResource(R.string.software_keyboard_key_hints_title),
                linkId = "on_screen.key_hints",
                wrapText = true,
                description = stringResource(R.string.software_keyboard_key_hints_description),
                checked = keyHintsEnabled,
                onCheckedChange = { enabled ->
                    keyHintsEnabled = enabled
                    SettingsManager.setSoftwareKeyboardKeyHintsEnabled(context, enabled)
                }
            )
            SettingsNavigationRow(
                title = stringResource(R.string.software_keyboard_theme_link_title),
                description = stringResource(R.string.software_keyboard_theme_link_description),
                icon = Icons.Filled.Keyboard,
                linkId = "on_screen.theme",
                onClick = onOpenKeyboardTheme
            )
        }
    }
}

@Composable
private fun SoftwareKeyboardModifierSelection(
    title: String,
    linkId: String? = null,
    selected: SettingsManager.SoftwareKeyboardModifierKey,
    modifier: Modifier = Modifier,
    onSelected: (SettingsManager.SoftwareKeyboardModifierKey) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .height(82.dp)
            .settingRow(linkId) { expanded = true },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = softwareKeyboardModifierKeyLabel(selected),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                SettingsManager.SoftwareKeyboardModifierKey.values().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(softwareKeyboardModifierKeyLabel(option)) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        },
                        leadingIcon = {
                            if (selected == option) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun softwareKeyboardModifierKeyLabel(
    modifierKey: SettingsManager.SoftwareKeyboardModifierKey
): String {
    return when (modifierKey) {
        SettingsManager.SoftwareKeyboardModifierKey.CTRL ->
            stringResource(R.string.software_keyboard_modifier_key_ctrl)
        SettingsManager.SoftwareKeyboardModifierKey.ALT ->
            stringResource(R.string.software_keyboard_modifier_key_alt)
    }
}

@Composable
private fun ModifierTapLatchRow(
    title: String,
    linkId: String? = null,
    description: String,
    checked: Boolean,
    indent: Boolean = false,
    wrapText: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.settingRow(linkId)
            .fillMaxWidth()
            .then(if (wrapText) Modifier.heightIn(min = 74.dp) else Modifier.height(74.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (indent) 52.dp else 16.dp, end = 16.dp)
                .then(if (wrapText) Modifier.padding(vertical = 12.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!indent) {
                Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (wrapText) Int.MAX_VALUE else 1
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (wrapText) Int.MAX_VALUE else 2
                )
            }
            Switch(
                modifier = if (wrapText) Modifier.semantics { contentDescription = title } else Modifier,
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
