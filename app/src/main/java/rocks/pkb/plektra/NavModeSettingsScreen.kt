package rocks.pkb.plektra

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.window.DialogProperties
import rocks.pkb.plektra.R
import rocks.pkb.plektra.commands.CommandRegistry
import rocks.pkb.plektra.commands.CommandSourceId
import rocks.pkb.plektra.commands.CommandSurface
import rocks.pkb.plektra.data.layout.JsonLayoutLoader
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import kotlin.math.min

/**
 * Nav Mode settings screen with keyboard visualization.
 */
@Composable
fun NavModeSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    initialKeyCode: Int? = null
) {
    val context = LocalContext.current

    // Load nav mode enabled state
    var navModeEnabled by remember {
        mutableStateOf(SettingsManager.getNavModeEnabled(context))
    }
    var navModeCtrlHoldEnabled by remember {
        mutableStateOf(SettingsManager.getNavModeCtrlHoldEnabled(context))
    }
    var layoutAwareCtrlShortcutsEnabled by remember {
        mutableStateOf(SettingsManager.getLayoutAwareCtrlShortcutsEnabled(context))
    }
    var showNavModeGuide by remember { mutableStateOf(false) }
    var showLayoutAwareCtrlInfo by remember { mutableStateOf(false) }

    // Load current mappings (all alphabetic keys)
    var keyMappings by remember {
        mutableStateOf(loadAllKeyMappings(context))
    }

    // Dialog state for key configuration
    var selectedKeyCode by remember(initialKeyCode) { mutableStateOf(initialKeyCode) }

    // Load default mappings for comparison
    val defaultMappings = remember {
        loadAllKeyMappings(context, useDefaults = true)
    }
    val layoutHints = remember(keyMappings) {
        loadLayoutHints(context)
    }

    // Handle system back button

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
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
                    text = stringResource(R.string.nav_mode_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                IconButton(onClick = { showNavModeGuide = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.nav_mode_guide_title)
                    )
                }
            }
        }

        // Enable/Disable toggle
        Surface(
            modifier = Modifier.fillMaxWidth().settingRow("nav_mode.enabled")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.nav_mode_enable_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.nav_mode_enable_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = navModeEnabled,
                    onCheckedChange = { enabled ->
                        navModeEnabled = enabled
                        SettingsManager.setNavModeEnabled(context, enabled)
                    }
                )
            }
        }

        if (navModeEnabled) {
            val layoutAwareCtrlShortcutsAvailable = !navModeCtrlHoldEnabled

            Surface(
                modifier = Modifier.fillMaxWidth().settingRow("nav_mode.ctrl_hold")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.nav_mode_ctrl_hold_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.nav_mode_ctrl_hold_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = navModeCtrlHoldEnabled,
                        onCheckedChange = { enabled ->
                            navModeCtrlHoldEnabled = enabled
                            SettingsManager.setNavModeCtrlHoldEnabled(context, enabled)
                        }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().settingRow("nav_mode.layout_aware_ctrl_shortcuts")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.layout_aware_ctrl_shortcuts_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (layoutAwareCtrlShortcutsAvailable) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            IconButton(
                                onClick = { showLayoutAwareCtrlInfo = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = stringResource(R.string.layout_aware_ctrl_shortcuts_info_title),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                if (layoutAwareCtrlShortcutsAvailable) {
                                    R.string.layout_aware_ctrl_shortcuts_description
                                } else {
                                    R.string.layout_aware_ctrl_shortcuts_disabled_description
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = layoutAwareCtrlShortcutsEnabled,
                        enabled = layoutAwareCtrlShortcutsAvailable,
                        onCheckedChange = { enabled ->
                            layoutAwareCtrlShortcutsEnabled = enabled
                            SettingsManager.setLayoutAwareCtrlShortcutsEnabled(context, enabled)
                        }
                    )
                }
            }
        }

        if (showLayoutAwareCtrlInfo) {
            AlertDialog(
                onDismissRequest = { showLayoutAwareCtrlInfo = false },
                confirmButton = {
                    TextButton(onClick = { showLayoutAwareCtrlInfo = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
                title = {
                    Text(stringResource(R.string.layout_aware_ctrl_shortcuts_info_title))
                },
                text = {
                    Text(
                        text = stringResource(R.string.layout_aware_ctrl_shortcuts_info_text),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.65f)
                            .verticalScroll(rememberScrollState())
                    )
                }
            )
        }

        if (showNavModeGuide) {
            AlertDialog(
                onDismissRequest = { showNavModeGuide = false },
                modifier = Modifier.fillMaxWidth(0.9f),
                confirmButton = {
                    TextButton(onClick = { showNavModeGuide = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
                title = {
                    Text(stringResource(R.string.nav_mode_guide_title))
                },
                text = {
                    Text(
                        text = stringResource(R.string.nav_mode_guide_text),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.65f)
                            .verticalScroll(rememberScrollState())
                    )
                }
            )
        }


        // Keyboard visualization
        if (navModeEnabled) {
            val keyboardRows = listOf(
                listOf(
                    KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E,
                    KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y,
                    KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O,
                    KeyEvent.KEYCODE_P
                ),
                listOf(
                    KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
                    KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
                    KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L
                ),
                listOf(
                    KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C,
                    KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N,
                    KeyEvent.KEYCODE_M
                )
            )

            val spacing = 2.dp

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                val density = LocalDensity.current
                val maxKeysInRow = keyboardRows.maxOf { it.size }
                val maxRowWidthPx = with(density) { maxWidth.toPx() }
                val spacingPx = with(density) { spacing.toPx() }
                val desiredSizePx = with(density) { 64.dp.toPx() }
                val totalSpacingPx = spacingPx * (maxKeysInRow - 1)
                val availableForKeys = (maxRowWidthPx - totalSpacingPx).coerceAtLeast(0f)
                val exactSizePx = if (maxKeysInRow > 0) availableForKeys / maxKeysInRow else desiredSizePx
                val finalSizePx = min(desiredSizePx, exactSizePx)
                val keySize = with(density) { finalSizePx.toDp() }
                val keyHeight = keySize * 1.25f

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    keyboardRows.forEach { row ->
                        KeyboardRow(
                            keys = row,
                            keySize = keySize,
                            keyHeight = keyHeight,
                            spacing = spacing,
                            mappings = keyMappings,
                            defaultMappings = defaultMappings,
                            layoutHints = layoutHints,
                            onKeyClick = { keyCode ->
                                selectedKeyCode = keyCode
                            }
                        )
                    }
                }
            }
            // Revert to default button
            Surface(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            SettingsManager.resetNavModeKeyMappings(context)
                            keyMappings = loadAllKeyMappings(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.nav_mode_revert_to_default))
                    }
                }
            }
        }
    }

    // Key configuration dialog
    selectedKeyCode?.let { keyCode ->
        KeyMappingDialog(
            keyCode = keyCode,
            currentMapping = keyMappings[keyCode],
            defaultMapping = defaultMappings[keyCode],
            onDismiss = { selectedKeyCode = null },
            onSave = { mapping ->
                val newMappings = keyMappings.toMutableMap()
                newMappings[keyCode] = mapping
                keyMappings = newMappings
                SettingsManager.saveNavModeKeyMappings(context, newMappings)
                selectedKeyCode = null
            }
        )
    }
}

@Composable
private fun KeyboardRow(
    keys: List<Int>,
    keySize: Dp,
    keyHeight: Dp,
    spacing: Dp,
    mappings: Map<Int, KeyMappingLoader.CtrlMapping>,
    defaultMappings: Map<Int, KeyMappingLoader.CtrlMapping>,
    layoutHints: Map<Int, String>,
    onKeyClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        keys.forEachIndexed { index, keyCode ->
            if (index > 0) {
                Spacer(modifier = Modifier.width(spacing))
            }
            KeyButton(
                keyCode = keyCode,
                mapping = mappings[keyCode],
                hasDefault = defaultMappings.containsKey(keyCode),
                layoutHint = layoutHints[keyCode],
                onClick = { onKeyClick(keyCode) },
                modifier = Modifier
                    .width(keySize)
                    .height(keyHeight)
            )
        }
    }
}

@Composable
private fun KeyButton(
    keyCode: Int,
    mapping: KeyMappingLoader.CtrlMapping?,
    hasDefault: Boolean,
    layoutHint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyLabel = getKeyLabel(keyCode)
    val mappingLabel = mapping?.let { getMappingLabelShort(it) }
    val hasMapping = mapping != null && mapping.type != "none"
    val backgroundColor = if (hasMapping) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (hasMapping) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    val keyTextColor = if (hasMapping) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    val mappingTextColor = if (hasMapping) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val mappingIcon: ImageVector?
    val mappingIconDesc: String?
    if (mapping?.type == "keycode") {
        when (mapping.value) {
            "DPAD_UP" -> {
                mappingIcon = Icons.Filled.KeyboardArrowUp
                mappingIconDesc = mappingLabel
            }
            "DPAD_DOWN" -> {
                mappingIcon = Icons.Filled.KeyboardArrowDown
                mappingIconDesc = mappingLabel
            }
            "DPAD_LEFT" -> {
                mappingIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft
                mappingIconDesc = mappingLabel
            }
            "DPAD_RIGHT" -> {
                mappingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight
                mappingIconDesc = mappingLabel
            }
            "DPAD_CENTER" -> {
                mappingIcon = Icons.Filled.RadioButtonUnchecked
                mappingIconDesc = mappingLabel
            }
            else -> {
                mappingIcon = null
                mappingIconDesc = null
            }
        }
    } else {
        mappingIcon = null
        mappingIconDesc = null
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        color = backgroundColor,
        tonalElevation = if (hasMapping) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = keyLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = keyTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (layoutHint != null) {
                Text(
                    text = layoutHint,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            if (mappingIcon != null) {
                Icon(
                    imageVector = mappingIcon,
                    contentDescription = mappingIconDesc,
                    tint = mappingTextColor,
                    modifier = Modifier.size(18.dp)
                )
            } else if (mappingLabel != null) {
                Text(
                    text = mappingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = mappingTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            } else if (hasDefault) {
                Text(
                    text = stringResource(R.string.nav_mode_default),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** The mapping the dialog would save, or null while the chosen type still needs a value. */
internal fun keyMappingSelection(type: String?, value: String?): KeyMappingLoader.CtrlMapping? {
    return when (type) {
        "keycode", "action", "command" -> value?.let { KeyMappingLoader.CtrlMapping(type, it) }
        "native_ctrl", "none" -> KeyMappingLoader.CtrlMapping(type, "")
        else -> null
    }
}

private data class PickerTone(
    val container: Color,
    val content: Color,
    val accent: Color
)

private data class NavOptionEntry(
    val value: String,
    val label: String,
    val icon: ImageVector? = null
)

private data class NavOptionGroup(
    val id: String,
    val title: String,
    val accent: Color,
    val showCount: Boolean = false,
    val options: List<NavOptionEntry>
)

@Composable
private fun pickerToneForType(type: String?): PickerTone {
    val scheme = MaterialTheme.colorScheme
    return when (type) {
        "keycode" -> PickerTone(
            container = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            accent = scheme.primary
        )
        "action" -> PickerTone(
            container = scheme.tertiaryContainer,
            content = scheme.onTertiaryContainer,
            accent = scheme.tertiary
        )
        "command" -> PickerTone(
            container = scheme.secondaryContainer,
            content = scheme.onSecondaryContainer,
            accent = scheme.secondary
        )
        "native_ctrl" -> PickerTone(
            container = scheme.primaryContainer.copy(alpha = 0.72f),
            content = scheme.onPrimaryContainer,
            accent = scheme.primary
        )
        else -> PickerTone(
            container = scheme.surfaceContainerHighest,
            content = scheme.onSurface,
            accent = scheme.outline
        )
    }
}

private fun keycodeIcon(keycode: String): ImageVector? {
    return when (keycode) {
        "DPAD_UP" -> Icons.Filled.KeyboardArrowUp
        "DPAD_DOWN" -> Icons.Filled.KeyboardArrowDown
        "DPAD_LEFT" -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
        "DPAD_RIGHT" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        "DPAD_CENTER" -> Icons.Filled.RadioButtonUnchecked
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun KeyMappingDialog(
    keyCode: Int,
    currentMapping: KeyMappingLoader.CtrlMapping?,
    defaultMapping: KeyMappingLoader.CtrlMapping?,
    onDismiss: () -> Unit,
    onSave: (KeyMappingLoader.CtrlMapping) -> Unit
) {
    val keyLabel = getKeyLabel(keyCode)
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf<String?>(currentMapping?.type) }
    var selectedValue by remember { mutableStateOf<String?>(currentMapping?.value) }
    var selectedCommandSource by remember { mutableStateOf<CommandSourceId?>(null) }
    val commandTargets = remember { CommandRegistry(context).getCommands(CommandSurface.NavMode) }
    val commandGroupsBySource = remember(commandTargets) {
        commandTargets.groupBy { it.source }
    }
    val defaultLabel = defaultMapping?.let { getMappingLabel(it) }
    val dialogMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.92f
    val fontScale = LocalDensity.current.fontScale
    val minCellWidth = if (fontScale >= 1.4f) 220.dp else 180.dp
    val selection = keyMappingSelection(selectedType, selectedValue)
    val activeTone = pickerToneForType(selectedType)

    val types = listOf(
        "keycode" to stringResource(R.string.nav_mode_keycode),
        "action" to stringResource(R.string.nav_mode_action),
        "native_ctrl" to stringResource(R.string.nav_mode_native_ctrl),
        "command" to stringResource(R.string.nav_mode_command),
        "none" to stringResource(R.string.nav_mode_none)
    )

    val optionGroups: List<NavOptionGroup> = when (selectedType) {
        "keycode" -> listOf(
            NavOptionGroup(
                id = "direction",
                title = stringResource(R.string.nav_mode_category_direction),
                accent = activeTone.accent,
                options = listOf("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER")
                    .map { NavOptionEntry(value = it, label = it, icon = keycodeIcon(it)) }
            ),
            NavOptionGroup(
                id = "navigation_keys",
                title = stringResource(R.string.nav_mode_category_navigation_keys),
                accent = activeTone.accent,
                options = listOf("MOVE_HOME", "MOVE_END", "PAGE_UP", "PAGE_DOWN", "TAB", "ESCAPE", "FORWARD_DEL")
                    .map { NavOptionEntry(value = it, label = it) }
            )
        )
        "action" -> listOf(
            NavOptionGroup(
                id = "clipboard",
                title = stringResource(R.string.nav_mode_category_clipboard),
                accent = activeTone.accent,
                options = listOf("copy", "paste", "cut", "undo")
                    .map { NavOptionEntry(value = it, label = getActionLabel(it)) }
            ),
            NavOptionGroup(
                id = "selection",
                title = stringResource(R.string.nav_mode_category_selection),
                accent = activeTone.accent,
                options = listOf(
                    "select_all",
                    "expand_selection_left",
                    "expand_selection_right",
                    "move_word_left",
                    "move_word_right",
                    "expand_selection_word_left",
                    "expand_selection_word_right"
                ).map { NavOptionEntry(value = it, label = getActionLabel(it)) }
            ),
            NavOptionGroup(
                id = "document_ui",
                title = stringResource(R.string.nav_mode_category_document_ui),
                accent = activeTone.accent,
                options = listOf("page_start", "page_end", "toggle_minimal_ui")
                    .map { NavOptionEntry(value = it, label = getActionLabel(it)) }
            ),
            NavOptionGroup(
                id = "media",
                title = stringResource(R.string.nav_mode_category_media),
                accent = activeTone.accent,
                options = listOf("media_play_pause", "media_previous", "media_next")
                    .map { NavOptionEntry(value = it, label = getActionLabel(it)) }
            )
        )
        "command" -> {
            val scheme = MaterialTheme.colorScheme
            commandGroupsBySource.entries
                .filter { (source, _) -> selectedCommandSource == null || source == selectedCommandSource }
                .map { (source, commands) ->
                    val sourceAccent = when (source) {
                        CommandSourceId.Apps -> scheme.secondary
                        CommandSourceId.Plektra -> scheme.primary
                        CommandSourceId.AppActions -> scheme.tertiary
                        CommandSourceId.DeviceControl -> scheme.secondary
                        CommandSourceId.NavActions -> scheme.primary
                    }
                    NavOptionGroup(
                        id = source.storageValue,
                        title = source.displayLabel,
                        accent = sourceAccent,
                        showCount = true,
                        options = commands.map { NavOptionEntry(value = it.id, label = it.label) }
                    )
                }
        }
        else -> emptyList()
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .heightIn(max = dialogMaxHeight),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Compact left-aligned header with keycap badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                        modifier = Modifier.defaultMinSize(minWidth = 34.dp, minHeight = 32.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = keyLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.nav_mode_configure_key, keyLabel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                // Type and value choices share one scrolling grid so Cancel and Save stay on screen
                // in short windows and at 100–150% font scale.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minCellWidth),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    item(key = "type", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.nav_mode_type),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { heading() }
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                types.forEach { (type, label) ->
                                    val isSelected = selectedType == type
                                    val chipTone = pickerToneForType(type)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            if (selectedType != type) {
                                                selectedType = type
                                                selectedValue = null
                                                selectedCommandSource = null
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = label,
                                                maxLines = 1,
                                                softWrap = false,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                            )
                                        },
                                        leadingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = chipTone.container,
                                            selectedLabelColor = chipTone.content,
                                            selectedLeadingIconColor = chipTone.content,
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                            labelColor = MaterialTheme.colorScheme.onSurface
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                            selectedBorderColor = chipTone.accent.copy(alpha = 0.8f),
                                            selectedBorderWidth = 1.dp
                                        )
                                    )
                                }
                            }
                            if (defaultLabel != null) {
                                TextButton(
                                    onClick = {
                                        selectedType = defaultMapping?.type
                                        selectedValue = defaultMapping?.value
                                        selectedCommandSource = null
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Restore,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.nav_mode_use_default, defaultLabel),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }

                    if (selectedType == "command" && commandGroupsBySource.size > 1) {
                        item(key = "command_sources", span = { GridItemSpan(maxLineSpan) }) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                val allSelected = selectedCommandSource == null
                                FilterChip(
                                    selected = allSelected,
                                    onClick = { selectedCommandSource = null },
                                    label = {
                                        Text(
                                            text = stringResource(R.string.nav_mode_command_source_all),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = activeTone.container,
                                        selectedLabelColor = activeTone.content,
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = allSelected,
                                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        selectedBorderColor = activeTone.accent.copy(alpha = 0.75f)
                                    )
                                )
                                commandGroupsBySource.keys.forEach { source ->
                                    val sourceSelected = selectedCommandSource == source
                                    FilterChip(
                                        selected = sourceSelected,
                                        onClick = { selectedCommandSource = source },
                                        label = {
                                            Text(
                                                text = source.displayLabel,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = activeTone.container,
                                            selectedLabelColor = activeTone.content,
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = sourceSelected,
                                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                            selectedBorderColor = activeTone.accent.copy(alpha = 0.75f)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    optionGroups.forEach { group ->
                        item(key = "header:$selectedType:${group.id}", span = { GridItemSpan(maxLineSpan) }) {
                            OptionCategoryHeader(
                                title = group.title,
                                accentColor = group.accent,
                                count = if (group.showCount) group.options.size else null
                            )
                        }
                        items(group.options, key = { "$selectedType:${it.value}" }) { entry ->
                            NavOptionTile(
                                label = entry.label,
                                selected = selectedValue == entry.value,
                                tone = activeTone,
                                icon = entry.icon,
                                onClick = { selectedValue = entry.value }
                            )
                        }
                    }

                    if (selectedType == "native_ctrl" || selectedType == "none") {
                        item(key = "hint:$selectedType", span = { GridItemSpan(maxLineSpan) }) {
                            val hintText = if (selectedType == "native_ctrl") {
                                stringResource(R.string.nav_mode_native_ctrl_hint, keyLabel)
                            } else {
                                stringResource(R.string.nav_mode_none_hint, keyLabel)
                            }
                            PickerModeHintCard(
                                text = hintText,
                                accentColor = activeTone.accent
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.nav_mode_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        // Disabled until the chosen type has a value, so a type switch cannot save as None.
                        onClick = { selection?.let(onSave) },
                        enabled = selection != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.nav_mode_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionCategoryHeader(
    title: String,
    accentColor: Color,
    count: Int? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NavOptionTile(
    label: String,
    selected: Boolean,
    tone: PickerTone,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        tone.container
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (selected) {
        tone.content
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) {
        BorderStroke(1.5.dp, tone.accent)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }

    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) tone.content else tone.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = tone.content,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun PickerModeHintCard(
    text: String,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun getKeyLabel(keyCode: Int): String {
    return when (keyCode) {
        KeyEvent.KEYCODE_Q -> "Q"
        KeyEvent.KEYCODE_W -> "W"
        KeyEvent.KEYCODE_E -> "E"
        KeyEvent.KEYCODE_R -> "R"
        KeyEvent.KEYCODE_T -> "T"
        KeyEvent.KEYCODE_Y -> "Y"
        KeyEvent.KEYCODE_U -> "U"
        KeyEvent.KEYCODE_I -> "I"
        KeyEvent.KEYCODE_O -> "O"
        KeyEvent.KEYCODE_P -> "P"
        KeyEvent.KEYCODE_A -> "A"
        KeyEvent.KEYCODE_S -> "S"
        KeyEvent.KEYCODE_D -> "D"
        KeyEvent.KEYCODE_F -> "F"
        KeyEvent.KEYCODE_G -> "G"
        KeyEvent.KEYCODE_H -> "H"
        KeyEvent.KEYCODE_J -> "J"
        KeyEvent.KEYCODE_K -> "K"
        KeyEvent.KEYCODE_L -> "L"
        KeyEvent.KEYCODE_Z -> "Z"
        KeyEvent.KEYCODE_X -> "X"
        KeyEvent.KEYCODE_C -> "C"
        KeyEvent.KEYCODE_V -> "V"
        KeyEvent.KEYCODE_B -> "B"
        KeyEvent.KEYCODE_N -> "N"
        KeyEvent.KEYCODE_M -> "M"
        KeyEvent.KEYCODE_0 -> "0"
        KeyEvent.KEYCODE_GRAVE -> "$"
        else -> stringResource(R.string.nav_mode_key_unknown)
    }
}

private fun getMappingLabel(mapping: KeyMappingLoader.CtrlMapping): String? {
    return when (mapping.type) {
        "keycode" -> mapping.value
        "action" -> mapping.value
        "command" -> mapping.value
        "native_ctrl" -> "Ctrl"
        "none" -> null // Don't show label for "none"
        else -> null
    }
}

@Composable
private fun getActionLabel(action: String): String {
    return when (action) {
        "copy" -> stringResource(R.string.nav_mode_action_copy)
        "paste" -> stringResource(R.string.nav_mode_action_paste)
        "cut" -> stringResource(R.string.nav_mode_action_cut)
        "undo" -> stringResource(R.string.nav_mode_action_undo)
        "select_all" -> stringResource(R.string.nav_mode_action_select_all)
        "expand_selection_left" -> stringResource(R.string.nav_mode_action_expand_selection_left)
        "expand_selection_right" -> stringResource(R.string.nav_mode_action_expand_selection_right)
        "move_word_left" -> stringResource(R.string.nav_mode_action_move_word_left)
        "move_word_right" -> stringResource(R.string.nav_mode_action_move_word_right)
        "expand_selection_word_left" -> stringResource(R.string.nav_mode_action_expand_selection_word_left)
        "expand_selection_word_right" -> stringResource(R.string.nav_mode_action_expand_selection_word_right)
        "page_start" -> stringResource(R.string.nav_mode_action_page_start)
        "page_end" -> stringResource(R.string.nav_mode_action_page_end)
        "toggle_minimal_ui" -> stringResource(R.string.nav_mode_action_toggle_slim)
        "media_play_pause" -> stringResource(R.string.nav_mode_action_media_play_pause)
        "media_previous" -> stringResource(R.string.nav_mode_action_media_previous)
        "media_next" -> stringResource(R.string.nav_mode_action_media_next)
        else -> action
    }
}

@Composable
private fun getMappingLabelShort(mapping: KeyMappingLoader.CtrlMapping): String? {
    return when (mapping.type) {
        "keycode" -> when (mapping.value) {
            "DPAD_UP" -> stringResource(R.string.nav_mode_keycode_up)
            "DPAD_DOWN" -> stringResource(R.string.nav_mode_keycode_down)
            "DPAD_LEFT" -> stringResource(R.string.nav_mode_keycode_left)
            "DPAD_RIGHT" -> stringResource(R.string.nav_mode_keycode_right)
            "DPAD_CENTER" -> stringResource(R.string.nav_mode_keycode_center)
            "MOVE_HOME" -> stringResource(R.string.nav_mode_keycode_home)
            "MOVE_END" -> stringResource(R.string.nav_mode_keycode_end)
            "PAGE_UP" -> stringResource(R.string.nav_mode_keycode_page_up)
            "PAGE_DOWN" -> stringResource(R.string.nav_mode_keycode_page_down)
            "ESCAPE" -> stringResource(R.string.nav_mode_keycode_escape)
            "TAB" -> stringResource(R.string.nav_mode_keycode_tab)
            "FORWARD_DEL" -> stringResource(R.string.nav_mode_keycode_forward_delete)
            else -> mapping.value
        }
        "action" -> when (mapping.value) {
            "copy" -> stringResource(R.string.nav_mode_action_copy)
            "paste" -> stringResource(R.string.nav_mode_action_paste)
            "cut" -> stringResource(R.string.nav_mode_action_cut)
            "undo" -> stringResource(R.string.nav_mode_action_undo)
            "select_all" -> stringResource(R.string.nav_mode_action_select_all)
            "expand_selection_left" -> stringResource(R.string.nav_mode_action_expand_selection_left)
            "expand_selection_right" -> stringResource(R.string.nav_mode_action_expand_selection_right)
            "move_word_left" -> stringResource(R.string.nav_mode_action_move_word_left)
            "move_word_right" -> stringResource(R.string.nav_mode_action_move_word_right)
            "expand_selection_word_left" -> stringResource(R.string.nav_mode_action_expand_selection_word_left)
            "expand_selection_word_right" -> stringResource(R.string.nav_mode_action_expand_selection_word_right)
            "page_start" -> stringResource(R.string.nav_mode_action_page_start)
            "page_end" -> stringResource(R.string.nav_mode_action_page_end)
            "toggle_minimal_ui" -> stringResource(R.string.nav_mode_action_toggle_slim)
            "media_play_pause" -> stringResource(R.string.nav_mode_action_media_play_pause)
            "media_previous" -> stringResource(R.string.nav_mode_action_media_previous)
            "media_next" -> stringResource(R.string.nav_mode_action_media_next)
            else -> mapping.value
        }
        "command" -> mapping.value.substringAfterLast('.').replace('_', ' ')
        "native_ctrl" -> "Ctrl"
        "none" -> null // Don't show label for "none"
        else -> null
    }
}

private fun loadAllKeyMappings(context: Context, useDefaults: Boolean = false): Map<Int, KeyMappingLoader.CtrlMapping> {
    val allAlphabeticKeys = listOf(
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
        KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
        KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
        KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z,
        KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B,
        KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_GRAVE
    )

    val loadedMappings = try {
        val assets = context.assets
        if (useDefaults) {
            KeyMappingLoader.loadCtrlKeyMappings(assets, null) // Load from assets only
        } else {
            KeyMappingLoader.loadCtrlKeyMappings(assets, context) // Load custom if exists
        }
    } catch (e: Exception) {
        emptyMap()
    }

    // Return all keys with their mappings (or null if no mapping)
    return allAlphabeticKeys.associateWith { keyCode ->
        loadedMappings[keyCode] ?: KeyMappingLoader.CtrlMapping("none", "")
    }
}

private fun loadLayoutHints(context: Context): Map<Int, String> {
    val layoutName = SettingsManager.getKeyboardLayout(context)
    val layout = JsonLayoutLoader.loadLayout(context.assets, layoutName, context)
        ?: return emptyMap()
    val allAlphabeticKeys = listOf(
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
        KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
        KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
        KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z,
        KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B,
        KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_GRAVE
    )

    return allAlphabeticKeys.mapNotNull { keyCode ->
        val physicalLabel = keyCodeToLetter(keyCode) ?: return@mapNotNull null
        val layoutChar = layout[keyCode]?.lowercase?.firstOrNull()
            ?.uppercaseChar()
            ?: return@mapNotNull null
        if (layoutChar.toString() != physicalLabel) {
            keyCode to "→ $layoutChar"
        } else {
            null
        }
    }.toMap()
}

private fun keyCodeToLetter(keyCode: Int): String? {
    return when (keyCode) {
        KeyEvent.KEYCODE_Q -> "Q"
        KeyEvent.KEYCODE_W -> "W"
        KeyEvent.KEYCODE_E -> "E"
        KeyEvent.KEYCODE_R -> "R"
        KeyEvent.KEYCODE_T -> "T"
        KeyEvent.KEYCODE_Y -> "Y"
        KeyEvent.KEYCODE_U -> "U"
        KeyEvent.KEYCODE_I -> "I"
        KeyEvent.KEYCODE_O -> "O"
        KeyEvent.KEYCODE_P -> "P"
        KeyEvent.KEYCODE_A -> "A"
        KeyEvent.KEYCODE_S -> "S"
        KeyEvent.KEYCODE_D -> "D"
        KeyEvent.KEYCODE_F -> "F"
        KeyEvent.KEYCODE_G -> "G"
        KeyEvent.KEYCODE_H -> "H"
        KeyEvent.KEYCODE_J -> "J"
        KeyEvent.KEYCODE_K -> "K"
        KeyEvent.KEYCODE_L -> "L"
        KeyEvent.KEYCODE_Z -> "Z"
        KeyEvent.KEYCODE_X -> "X"
        KeyEvent.KEYCODE_C -> "C"
        KeyEvent.KEYCODE_V -> "V"
        KeyEvent.KEYCODE_B -> "B"
        KeyEvent.KEYCODE_N -> "N"
        KeyEvent.KEYCODE_M -> "M"
        KeyEvent.KEYCODE_0 -> "0"
        KeyEvent.KEYCODE_GRAVE -> "$"
        else -> null
    }
}
