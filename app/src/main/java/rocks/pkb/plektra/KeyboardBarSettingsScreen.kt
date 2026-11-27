package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import rocks.pkb.plektra.R

private enum class KeyboardBarEditorMode { Normal, Slim }

internal enum class KeyboardBarSettingsPage { Contents, Buttons, Variations, Indicators }

internal object KeyboardBarSharedRowSettings {
    /** The precedence between suggestions and variations only matters when both share one row. */
    fun precedenceApplies(suggestions: Boolean, actions: Boolean, arrangement: KeyboardBarArrangement): Boolean =
        suggestions && actions && arrangement == KeyboardBarArrangement.SHARED

    /** Mirrors the profile the settings page opens with. */
    fun precedenceApplies(context: android.content.Context): Boolean {
        val configuration = KeyboardBarConfiguration.read(context)
        return if (SettingsManager.getStatusBarPresentationMode(context) == SettingsManager.StatusBarPresentationMode.SLIM) {
            precedenceApplies(configuration.slimProfile.suggestions, configuration.slimProfile.actions, KeyboardBarArrangement.SHARED)
        } else precedenceApplies(configuration.suggestions, configuration.actions, configuration.arrangement)
    }
}

@Composable
internal fun KeyboardBarCheckRow(label: String, checked: Boolean, linkId: String,
    description: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingsToggleRow(title = label, description = description, checked = checked,
        onCheckedChange = onChange, enabled = enabled, linkId = linkId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardBarSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onCustomizeVariations: () -> Unit,
    page: KeyboardBarSettingsPage = KeyboardBarSettingsPage.Contents
) {
    val context = LocalContext.current
    var leftSlots by remember { mutableStateOf(SettingsManager.getStatusBarSlotsLeft(context)) }
    var rightSlots by remember { mutableStateOf(SettingsManager.getStatusBarSlotsRight(context)) }
    var suggestionsEnabled by remember { mutableStateOf(SettingsManager.getSuggestionsEnabled(context)) }
    var suggestionsBold by remember { mutableStateOf(SettingsManager.getSuggestionsBold(context)) }
    var showOnLauncher by remember { mutableStateOf(SettingsManager.getShowKeyboardBarOnLauncher(context)) }
    var hideImeCaptionBar by remember { mutableStateOf(SettingsManager.getHideImeCaptionBar(context)) }
    var imeSwitcherInBar by remember { mutableStateOf(SettingsManager.getImeSwitcherInBar(context)) }
    var hiddenAppCount by remember { mutableStateOf(SettingsManager.getKeyboardBarHiddenPackages(context).size) }
    var hardwareOnly by remember { mutableStateOf(SettingsManager.getHardwareOnlyMode(context)) }
    var hardwareOnlyConfirmation by remember { mutableStateOf(false) }
    var indicators by remember { mutableStateOf(SettingsManager.getModifierIndicators(context)) }
    var variationsVisible by remember {
        mutableStateOf(SettingsManager.areStatusBarVariationsEnabled(context))
    }
    var dynamicVariationSlotCount by remember {
        mutableStateOf(SettingsManager.getDynamicVariationBarSlotCount(context))
    }
    var dynamicVariationsResizeToContent by remember {
        mutableStateOf(SettingsManager.getDynamicVariationBarResizeToContent(context))
    }
    var sharedRowVariationsFirst by remember { mutableStateOf(SettingsManager.getSharedRowVariationsFirst(context)) }
    var editorMode by rememberSaveable {
        mutableStateOf(
            if (
                SettingsManager.getStatusBarPresentationMode(context) ==
                    SettingsManager.StatusBarPresentationMode.SLIM
            ) {
                KeyboardBarEditorMode.Slim
            } else {
                KeyboardBarEditorMode.Normal
            }
        )
    }

    var slimProfile by remember { mutableStateOf(KeyboardBarConfiguration.read(context).slimProfile) }
    var arrangement by remember { mutableStateOf(KeyboardBarConfiguration.read(context).arrangement) }
    var slimActive by remember { mutableStateOf(SettingsManager.getSlimModeActive(context)) }
    DisposableEffect(context) {
        val preferences = SettingsManager.getPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            slimActive = SettingsManager.getSlimModeActive(context)
            val config = KeyboardBarConfiguration.read(context)
            slimProfile = config.slimProfile
            arrangement = config.arrangement
            suggestionsEnabled = config.suggestions
            variationsVisible = config.actions
            indicators = SettingsManager.getModifierIndicators(context)
            leftSlots = SettingsManager.getStatusBarSlotsLeft(context)
            rightSlots = SettingsManager.getStatusBarSlotsRight(context)
            showOnLauncher = SettingsManager.getShowKeyboardBarOnLauncher(context)
            hiddenAppCount = SettingsManager.getKeyboardBarHiddenPackages(context).size
            hardwareOnly = SettingsManager.getHardwareOnlyMode(context)
            dynamicVariationSlotCount = SettingsManager.getDynamicVariationBarSlotCount(context)
            dynamicVariationsResizeToContent = SettingsManager.getDynamicVariationBarResizeToContent(context)
            sharedRowVariationsFirst = SettingsManager.getSharedRowVariationsFirst(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val editingSlim = editorMode == KeyboardBarEditorMode.Slim
    val displayedSuggestions = if (editingSlim) slimProfile.suggestions else suggestionsEnabled
    val displayedActions = if (editingSlim) slimProfile.actions else variationsVisible
    fun saveSlim(profile: KeyboardBarProfile) {
        slimProfile = profile
        KeyboardBarConfiguration.setSlimProfile(context, profile)
    }

    fun setIndicator(id: String, enabled: Boolean) {
        if (editingSlim) saveSlim(when (id) {
            SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP -> slimProfile.copy(bottomIndicators = enabled)
            SettingsManager.MODIFIER_INDICATOR_MENU_BAR -> slimProfile.copy(androidIndicators = enabled)
            else -> slimProfile.copy(inlineIndicators = enabled)
        }) else {
            indicators = if (enabled) indicators + id else indicators - id
            SettingsManager.setModifierIndicators(context, indicators)
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var resetConfirmation by remember { mutableStateOf(false) }
    val profilesVisible = page == KeyboardBarSettingsPage.Contents || page == KeyboardBarSettingsPage.Indicators
    val activeProfileSelected = editingSlim == slimActive
    val profileName = stringResource(if (editingSlim) R.string.keyboard_bar_slim_profile else R.string.keyboard_bar_normal_profile)
    val title = stringResource(when (page) {
        KeyboardBarSettingsPage.Contents -> R.string.status_bar_buttons_title
        KeyboardBarSettingsPage.Buttons -> R.string.status_bar_buttons_section
        KeyboardBarSettingsPage.Variations -> R.string.keyboard_bar_variations_title
        KeyboardBarSettingsPage.Indicators -> R.string.modifiers_section_indicators
    })

    fun selectExtendedButton(buttonId: String, targetSide: String, targetIndex: Int) {
        if (buttonId != SettingsManager.STATUS_BAR_BUTTON_NONE) {
            leftSlots = leftSlots.mapIndexed { index, current ->
                if (current == buttonId && !(targetSide == "left" && targetIndex == index)) {
                    SettingsManager.STATUS_BAR_BUTTON_NONE
                } else current
            }
            rightSlots = rightSlots.mapIndexed { index, current ->
                if (current == buttonId && !(targetSide == "right" && targetIndex == index)) {
                    SettingsManager.STATUS_BAR_BUTTON_NONE
                } else current
            }
        }
        if (targetSide == "left") {
            leftSlots = leftSlots.toMutableList().also { it[targetIndex] = buttonId }
        } else {
            rightSlots = rightSlots.toMutableList().also { it[targetIndex] = buttonId }
        }
        SettingsManager.setStatusBarSlotsLeft(context, leftSlots)
        SettingsManager.setStatusBarSlotsRight(context, rightSlots)
    }


    Scaffold(modifier = modifier, topBar = {
        PlektraSettingsHeader(title = title, onBack = onBack) {
                if (profilesVisible || page == KeyboardBarSettingsPage.Buttons) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.keyboard_bar_more_options))
                        }
                        DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (profilesVisible && !activeProfileSelected) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.keyboard_bar_use_profile, profileName)) },
                                    onClick = {
                                        SettingsManager.setStatusBarPresentationMode(context, if (editingSlim)
                                            SettingsManager.StatusBarPresentationMode.SLIM else SettingsManager.StatusBarPresentationMode.FULL_STATUS_BAR)
                                        SettingsManager.setSlimModeActive(context, editingSlim)
                                        menuExpanded = false
                                    })
                            }
                            DropdownMenuItem(text = { Text(if (profilesVisible)
                                stringResource(R.string.keyboard_bar_reset_profile, profileName)
                                else stringResource(R.string.status_bar_buttons_reset)) },
                                onClick = { menuExpanded = false; resetConfirmation = true })
                        }
                    }
                }
        }
    }) { paddingValues ->
        Column(Modifier.fillMaxWidth().padding(paddingValues).verticalScroll(rememberScrollState())) {
        if (profilesVisible) {
            SecondaryTabRow(selectedTabIndex = editorMode.ordinal, modifier = Modifier.settingRow("status_bar.presentation")) {
                KeyboardBarEditorMode.entries.forEach { mode ->
                    Tab(selected = editorMode == mode, onClick = { editorMode = mode },
                        text = { Text(stringResource(if (mode == KeyboardBarEditorMode.Normal)
                            R.string.keyboard_bar_normal_profile else R.string.keyboard_bar_slim_profile)) })
                }
            }
        }
        if (page == KeyboardBarSettingsPage.Contents) {
            SettingsSectionDivider(stringResource(R.string.keyboard_bar_contents))
            KeyboardBarCheckRow(stringResource(R.string.keyboard_bar_suggestions), displayedSuggestions,
                SettingLinkIds.AUTO_CORRECTION_SUGGESTIONS) {
                if (editingSlim) saveSlim(slimProfile.copy(suggestions = it))
                else { suggestionsEnabled = it; SettingsManager.setSuggestionsEnabled(context, it) }
            }
            KeyboardBarCheckRow(stringResource(R.string.suggestions_bold_title), suggestionsBold,
                "keyboard_bar.suggestions_bold", stringResource(R.string.suggestions_bold_summary)) {
                suggestionsBold = it
                SettingsManager.setSuggestionsBold(context, it)
            }
            KeyboardBarCheckRow(stringResource(R.string.keyboard_bar_actions), displayedActions,
                "status_bar.variations_visible") {
                if (editingSlim) saveSlim(slimProfile.copy(actions = it))
                else { variationsVisible = it; SettingsManager.setStatusBarVariationsEnabled(context, it) }
            }
            KeyboardBarCheckRow(stringResource(R.string.modifier_indicators_bottom_strip),
                if (editingSlim) slimProfile.bottomIndicators else SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP in indicators,
                SettingLinkIds.MODIFIERS_INDICATOR_BOTTOM_STRIP) { enabled ->
                setIndicator(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP, enabled)
            }
            KeyboardTextScaleSettingsCard()
            SettingsSectionDivider(stringResource(R.string.keyboard_bar_layout_section))
            // Slim always combines its rows (KeyboardBarConfiguration.presentation).
            KeyboardBarArrangementField(if (editingSlim) KeyboardBarArrangement.SHARED else arrangement,
                !editingSlim && displayedSuggestions && displayedActions) {
                arrangement = it
                KeyboardBarConfiguration.setArrangement(context, it)
            }
            if (KeyboardBarSharedRowSettings.precedenceApplies(displayedSuggestions, displayedActions,
                    if (editingSlim) KeyboardBarArrangement.SHARED else arrangement)) {
                KeyboardBarCheckRow(stringResource(R.string.shared_row_variations_first_title), sharedRowVariationsFirst,
                    SettingLinkIds.KEYBOARD_BAR_SHARED_ROW_VARIATIONS_FIRST,
                    stringResource(R.string.shared_row_variations_first_description)) {
                    sharedRowVariationsFirst = it
                    SettingsManager.setSharedRowVariationsFirst(context, it)
                }
            }
            KeyboardBarNavigationRow(Icons.Filled.SmartButton, stringResource(R.string.status_bar_buttons_section),
                stringResource(R.string.keyboard_bar_buttons_summary,
                    leftSlots.joinToString(", ") { stringResourceForButton(context, it) },
                    rightSlots.joinToString(", ") { stringResourceForButton(context, it) }), "keyboard_bar.buttons") {
                openSettingsChild(context, "customization", "KeyboardBarButtons")
            }
            KeyboardBarNavigationRow(Icons.Filled.Tune, stringResource(R.string.keyboard_bar_variations_title),
                stringResource(R.string.keyboard_bar_variations_summary), "keyboard_bar.variations") {
                openSettingsChild(context, "customization", "KeyboardBarVariations")
            }
            if (ImeCaptionBarSettings.canControlCaptionBar()) {
                KeyboardBarCheckRow(stringResource(R.string.keyboard_bar_hide_ime_caption_bar), hideImeCaptionBar,
                    SettingLinkIds.KEYBOARD_BAR_HIDE_IME_CAPTION, stringResource(R.string.keyboard_bar_hide_ime_caption_bar_description)) {
                    hideImeCaptionBar = it
                    SettingsManager.setHideImeCaptionBar(context, it)
                }
            }
            KeyboardBarCheckRow(stringResource(R.string.keyboard_bar_ime_switcher_in_bar), imeSwitcherInBar,
                "keyboard_bar.ime_switcher_in_bar", stringResource(R.string.keyboard_bar_ime_switcher_in_bar_description)) {
                imeSwitcherInBar = it
                SettingsManager.setImeSwitcherInBar(context, it)
            }
            KeyboardBarNavigationRow(Icons.Filled.Tune, stringResource(R.string.keyboard_bar_system_controls),
                stringResource(R.string.keyboard_bar_system_controls_description), SettingLinkIds.KEYBOARD_BAR_SYSTEM_CONTROLS) {
                ImeCaptionBarSettings.open(context)
            }
            SettingsSectionDivider(stringResource(R.string.keyboard_bar_visibility_section))
            KeyboardBarCheckRow(stringResource(R.string.hardware_only_mode_title), hardwareOnly,
                SettingLinkIds.KEYBOARD_BAR_HARDWARE_ONLY, stringResource(R.string.hardware_only_mode_description)) { enabled ->
                // Only a missing keyboard needs a warning; detection can miss one, so it never blocks the choice.
                if (enabled && !rocks.pkb.plektra.inputmethod.DeviceSpecific.hasConnectedHardwareKeyboard()) {
                    hardwareOnlyConfirmation = true
                } else {
                    hardwareOnly = enabled
                    SettingsManager.setHardwareOnlyMode(context, enabled)
                }
            }
            KeyboardBarCheckRow(stringResource(R.string.keyboard_bar_show_on_launcher), showOnLauncher,
                "status_bar.show_on_launcher", stringResource(R.string.keyboard_bar_show_on_launcher_description)) {
                showOnLauncher = it; SettingsManager.setShowKeyboardBarOnLauncher(context, it)
            }
            KeyboardBarNavigationRow(Icons.Filled.VisibilityOff, stringResource(R.string.keyboard_bar_hidden_apps_title),
                if (hiddenAppCount == 0) stringResource(R.string.keyboard_bar_hidden_apps_summary_none)
                else pluralStringResource(R.plurals.keyboard_bar_hidden_apps_summary, hiddenAppCount, hiddenAppCount),
                "status_bar.hidden_apps") {
                openSettingsChild(context, "customization", "KeyboardBarHiddenApps")
            }
        }
        if (page == KeyboardBarSettingsPage.Buttons) {
            SlotGroup(
                title = stringResource(R.string.status_bar_slots_left), linkId = "status_bar.extended_left", slots = leftSlots, slotPrefix = "L",
                onSlotSelected = { index, id -> selectExtendedButton(id, "left", index) },
                onAddSlot = { leftSlots = leftSlots + SettingsManager.STATUS_BAR_BUTTON_NONE; SettingsManager.setStatusBarSlotsLeft(context, leftSlots) },
                onRemoveSlot = { index -> leftSlots = leftSlots.toMutableList().also { it.removeAt(index) }; SettingsManager.setStatusBarSlotsLeft(context, leftSlots) }
            )
            SlotGroup(
                title = stringResource(R.string.status_bar_slots_right), linkId = "status_bar.extended_right", slots = rightSlots, slotPrefix = "R",
                onSlotSelected = { index, id -> selectExtendedButton(id, "right", index) },
                onAddSlot = { rightSlots = rightSlots + SettingsManager.STATUS_BAR_BUTTON_NONE; SettingsManager.setStatusBarSlotsRight(context, rightSlots) },
                onRemoveSlot = { index -> rightSlots = rightSlots.toMutableList().also { it.removeAt(index) }; SettingsManager.setStatusBarSlotsRight(context, rightSlots) }
            )
        }
        if (page == KeyboardBarSettingsPage.Indicators) {
            listOf(
                SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP to R.string.modifier_indicators_bottom_strip,
                SettingsManager.MODIFIER_INDICATOR_MENU_BAR to R.string.modifier_indicators_menu_bar,
                SettingsManager.MODIFIER_INDICATOR_STATUS_BAR to R.string.modifier_indicators_status_bar
            ).forEach { (id, label) ->
                val checked = if (!editingSlim) id in indicators else when (id) {
                    SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP -> slimProfile.bottomIndicators
                    SettingsManager.MODIFIER_INDICATOR_MENU_BAR -> slimProfile.androidIndicators
                    else -> slimProfile.inlineIndicators
                }
                KeyboardBarCheckRow(stringResource(label), checked, "modifiers.indicator_$id",
                    description = if (id == SettingsManager.MODIFIER_INDICATOR_STATUS_BAR && !displayedSuggestions)
                        stringResource(R.string.keyboard_bar_requires_suggestions) else null,
                    enabled = id != SettingsManager.MODIFIER_INDICATOR_STATUS_BAR || displayedSuggestions) { enabled ->
                    setIndicator(id, enabled)
                }
            }
            CaretBadgeSettingsSection()
        }
        if (page == KeyboardBarSettingsPage.Variations) {
            SettingsSectionDivider(stringResource(R.string.extended_status_bar_features_section))
            run {
                Surface(modifier = Modifier.fillMaxWidth().settingRow("status_bar.variation_slots")) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dynamic_variation_slot_count_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(
                                R.string.dynamic_variation_slot_count_description,
                                dynamicVariationSlotCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = dynamicVariationSlotCount.toFloat(),
                            onValueChange = { value ->
                                dynamicVariationSlotCount = value.toInt().coerceIn(
                                    SettingsManager.MIN_DYNAMIC_VARIATION_BAR_SLOT_COUNT,
                                    SettingsManager.MAX_DYNAMIC_VARIATION_BAR_SLOT_COUNT
                                )
                            },
                            onValueChangeFinished = {
                                SettingsManager.setDynamicVariationBarSlotCount(context, dynamicVariationSlotCount)
                            },
                            valueRange = SettingsManager.MIN_DYNAMIC_VARIATION_BAR_SLOT_COUNT.toFloat()..
                                SettingsManager.MAX_DYNAMIC_VARIATION_BAR_SLOT_COUNT.toFloat(),
                            steps = SettingsManager.MAX_DYNAMIC_VARIATION_BAR_SLOT_COUNT -
                                SettingsManager.MIN_DYNAMIC_VARIATION_BAR_SLOT_COUNT - 1
                        )
                    }
                }

                SettingsToggleRow(
                    title = stringResource(R.string.dynamic_variation_resize_to_content_title),
                    description = stringResource(R.string.dynamic_variation_resize_to_content_description),
                    checked = dynamicVariationsResizeToContent,
                    linkId = "status_bar.resize_variations",
                    onCheckedChange = { enabled ->
                        dynamicVariationsResizeToContent = enabled
                        SettingsManager.setDynamicVariationBarResizeToContent(context, enabled)
                    }
                )

                SettingsNavigationRow(
                    title = stringResource(R.string.variation_customize_title),
                    icon = Icons.Filled.Tune,
                    linkId = "customization.variations",
                    onClick = onCustomizeVariations
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (hardwareOnlyConfirmation) {
        AlertDialog(onDismissRequest = { hardwareOnlyConfirmation = false },
            title = { Text(stringResource(R.string.hardware_only_mode_no_keyboard_title)) },
            text = { Text(stringResource(R.string.hardware_only_mode_no_keyboard_message)) },
            dismissButton = { TextButton(onClick = { hardwareOnlyConfirmation = false }) {
                Text(stringResource(android.R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                hardwareOnly = true
                SettingsManager.setHardwareOnlyMode(context, true)
                hardwareOnlyConfirmation = false
            }) { Text(stringResource(R.string.hardware_only_mode_no_keyboard_confirm)) } })
    }

    if (resetConfirmation) {
        AlertDialog(onDismissRequest = { resetConfirmation = false },
            title = { Text(if (profilesVisible) stringResource(R.string.keyboard_bar_reset_profile, profileName)
                else stringResource(R.string.status_bar_buttons_reset)) },
            text = { Text(stringResource(R.string.keyboard_bar_reset_confirmation)) },
            dismissButton = { TextButton(onClick = { resetConfirmation = false }) { Text(stringResource(android.R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                if (profilesVisible) {
                    val defaults = SettingsManager.getDefaultKeyboardBarProfile(editingSlim)
                    if (page == KeyboardBarSettingsPage.Indicators) {
                        if (editingSlim) saveSlim(slimProfile.copy(bottomIndicators = true, androidIndicators = false, inlineIndicators = false))
                        else SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP))
                    } else if (editingSlim) saveSlim(defaults)
                    else {
                        SettingsManager.setSuggestionsEnabled(context, defaults.suggestions)
                        SettingsManager.setStatusBarVariationsEnabled(context, defaults.actions)
                        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP))
                    }
                } else {
                    SettingsManager.setStatusBarSlotsLeft(context, SettingsManager.getDefaultStatusBarSlotsLeft())
                    SettingsManager.setStatusBarSlotsRight(context, SettingsManager.getDefaultStatusBarSlotsRight())
                }
                resetConfirmation = false
            }) { Text(stringResource(R.string.keyboard_bar_reset_action)) } })
    }

}
@Composable
private fun KeyboardBarArrangementField(value: KeyboardBarArrangement, enabled: Boolean,
    onChange: (KeyboardBarArrangement) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(choice: KeyboardBarArrangement) = when (choice) {
        KeyboardBarArrangement.SUGGESTIONS_FIRST -> R.string.keyboard_bar_suggestions_first
        KeyboardBarArrangement.ACTIONS_FIRST -> R.string.keyboard_bar_actions_first
        KeyboardBarArrangement.SHARED -> R.string.keyboard_bar_shared
    }
    Column(Modifier.fillMaxWidth()) {
        ListItem(modifier = Modifier.settingRow("keyboard_bar.row_layout").clickable(enabled = enabled) { expanded = true },
            headlineContent = { Text(stringResource(R.string.keyboard_bar_arrangement)) },
            supportingContent = { Text(stringResource(label(value))) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)))
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.keyboard_bar_arrangement)) },
        text = { Column {
            KeyboardBarArrangement.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().selectable(value == choice, role = Role.RadioButton,
                    onClick = { onChange(choice); expanded = false }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == choice, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label(choice)))
                }
            }
        } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun KeyboardBarNavigationRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String,
    summary: String? = null, linkId: String, onClick: () -> Unit) {
    SettingsNavigationRow(title = title, description = summary, icon = icon, linkId = linkId, onClick = onClick)
}
