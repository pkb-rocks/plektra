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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardControlKey
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.filled.RestartAlt
import rocks.pkb.plektra.commands.CommandTarget
import rocks.pkb.plektra.commands.CommandIcon
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.inputmethod.commandMaterialIcon
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

    // Command targets are resolved once per screen and shared by keycaps and the mapping dialog.
    val commandTargets = remember { CommandRegistry(context).getCommands(CommandSurface.NavMode) }
    val commandsById = remember(commandTargets) { commandTargets.associateBy { it.id } }
    val appIcons = rememberAppCommandIcons(commandTargets)

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
                val keyHeight = keySize * (1.3f + 0.2f * (LocalDensity.current.fontScale.coerceIn(1f, 1.5f) - 1f))

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
                            commandsById = commandsById,
                            appIcons = appIcons,
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
            commandTargets = commandTargets,
            appIcons = appIcons,
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
    commandsById: Map<String, CommandTarget>,
    appIcons: Map<String, ImageBitmap>,
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
                defaultMapping = defaultMappings[keyCode],
                layoutHint = layoutHints[keyCode],
                command = mappings[keyCode]?.takeIf { it.type == "command" }?.let { commandsById[it.value] },
                appIcon = mappings[keyCode]?.takeIf { it.type == "command" }?.let { appIcons[it.value] },
                onClick = { onKeyClick(keyCode) },
                modifier = Modifier
                    .width(keySize)
                    .height(keyHeight)
            )
        }
    }
}

private fun mappingIcon(mapping: KeyMappingLoader.CtrlMapping?, command: CommandTarget? = null): ImageVector? =
    when (mapping?.type) {
        "keycode" -> keycodeIcon(mapping.value)
        "action" -> actionIcon(mapping.value)
        "native_ctrl" -> typeIcon("native_ctrl")
        "command" -> command?.let(::commandMaterialIcon) ?: typeIcon("command")
        else -> null
    }

/** Keycap in the Nav Mode keyboard: letter, layout hint, mapping icon and a one-line short label. */
@Composable
private fun KeyButton(
    keyCode: Int,
    mapping: KeyMappingLoader.CtrlMapping?,
    defaultMapping: KeyMappingLoader.CtrlMapping?,
    layoutHint: String?,
    command: CommandTarget?,
    appIcon: ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val keyLabel = getKeyLabel(keyCode)
    val hasMapping = mapping != null && mapping.type != "none"
    val customized = mapping != null && mapping != defaultMapping
    val icon = if (hasMapping) mappingIcon(mapping, command) else null
    // Direction arrows carry their meaning in the icon; every other mapping keeps a short label.
    val shortLabel = if (hasMapping && !(mapping?.type == "keycode" && mapping.value.startsWith("DPAD_"))) {
        // The icon already shows the direction, so drop arrows from labels such as "←WordSel".
        (command?.label ?: mapping?.let { getMappingLabelShort(it) })?.let { label ->
            if (icon != null) label.trim('←', '→', ' ').ifEmpty { label } else label
        }
    } else null
    val fullLabel = mapping?.let { mappingDescription(it, command) }
        ?: if (mapping == null && defaultMapping != null) stringResource(R.string.nav_mode_default) else null
    // The keycaps are a dense diagram with a fixed geometry: let text follow the system scale up
    // to 120% and keep the full meaning in the key's accessibility label and in the dialog.
    val fontScale = LocalDensity.current.fontScale
    val textScale = fontScale.coerceAtMost(1.2f) / fontScale
    val shape = RoundedCornerShape(10.dp)
    val contentColor = if (hasMapping) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.6f)

    Surface(
        onClick = onClick,
        shape = shape,
        color = if (hasMapping) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
        contentColor = contentColor,
        border = BorderStroke(1.5.dp, if (customized) scheme.primary else scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(keyLabel, fullLabel, layoutHint).joinToString(", ")
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = keyLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp * textScale,
                    lineHeight = 14.sp * textScale,
                    color = if (hasMapping) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 2.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (layoutHint != null) {
                    Text(
                        text = layoutHint.removePrefix("→").trim(),
                        fontSize = 9.sp * textScale,
                        lineHeight = 11.sp * textScale,
                        color = scheme.tertiary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        appIcon != null -> Image(
                            bitmap = appIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        icon != null -> Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        mapping == null && defaultMapping != null -> Icon(
                            imageVector = Icons.Filled.Restore,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (shortLabel != null) {
                        Text(
                            text = shortLabel,
                            fontSize = 9.sp * textScale,
                            lineHeight = 11.sp * textScale,
                            letterSpacing = 0.sp,
                            color = contentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
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
    val icon: ImageVector? = null,
    val appIcon: ImageBitmap? = null
)

/**
 * Real launcher icons for app and app-action commands, rendered off the main thread once per
 * command list so scrolling never decodes or rasterizes drawables.
 */
@Composable
private fun rememberAppCommandIcons(commands: List<CommandTarget>): Map<String, ImageBitmap> {
    val sizePx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val icons by produceState(emptyMap<String, ImageBitmap>(), commands, sizePx) {
        value = withContext(Dispatchers.Default) {
            commands.mapNotNull { command ->
                if (command.source != CommandSourceId.Apps && command.source != CommandSourceId.AppActions) {
                    return@mapNotNull null
                }
                val drawable = (command.icon as? CommandIcon.DrawableIcon)?.drawable ?: return@mapNotNull null
                // Draw a private copy so the shared drawable's bounds stay untouched.
                val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
                runCatching { command.id to copy.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
            }.toMap()
        }
    }
    return icons
}

private data class NavOptionGroup(
    val id: String,
    val title: String,
    val accent: Color,
    val showCount: Boolean = false,
    val options: List<NavOptionEntry>
)

/** One stable tone for every type, so switching type never recolors the picker. */
@Composable
private fun pickerTone(): PickerTone {
    val scheme = MaterialTheme.colorScheme
    return PickerTone(
        container = scheme.primaryContainer,
        content = scheme.onPrimaryContainer,
        accent = scheme.primary
    )
}

private fun typeIcon(type: String): ImageVector = when (type) {
    "keycode" -> Icons.Filled.Keyboard
    "action" -> Icons.Filled.Bolt
    "native_ctrl" -> Icons.Filled.KeyboardControlKey
    "command" -> Icons.Filled.Terminal
    else -> Icons.Filled.Block
}

private fun commandSourceIcon(source: CommandSourceId): ImageVector = when (source) {
    CommandSourceId.Apps -> Icons.Filled.Apps
    CommandSourceId.Plektra -> Icons.Filled.Keyboard
    CommandSourceId.AppActions -> Icons.Filled.TouchApp
    CommandSourceId.DeviceControl -> Icons.Filled.SettingsRemote
    CommandSourceId.NavActions -> Icons.Filled.OpenWith
}

private fun keycodeIcon(keycode: String): ImageVector = when (keycode) {
    "DPAD_UP" -> Icons.Filled.KeyboardArrowUp
    "DPAD_DOWN" -> Icons.Filled.KeyboardArrowDown
    "DPAD_LEFT" -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    "DPAD_RIGHT" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    "DPAD_CENTER" -> Icons.Filled.RadioButtonUnchecked
    "MOVE_HOME" -> Icons.Filled.FirstPage
    "MOVE_END" -> Icons.AutoMirrored.Filled.LastPage
    "PAGE_UP" -> Icons.Filled.KeyboardDoubleArrowUp
    "PAGE_DOWN" -> Icons.Filled.KeyboardDoubleArrowDown
    "TAB" -> Icons.AutoMirrored.Filled.KeyboardTab
    "ESCAPE" -> Icons.Filled.Close
    "FORWARD_DEL" -> Icons.Filled.Delete
    else -> Icons.Filled.Keyboard
}

private fun actionIcon(action: String): ImageVector = when (action) {
    "copy" -> Icons.Filled.ContentCopy
    "paste" -> Icons.Filled.ContentPaste
    "cut" -> Icons.Filled.ContentCut
    "undo" -> Icons.AutoMirrored.Filled.Undo
    "select_all" -> Icons.Filled.SelectAll
    "expand_selection_left" -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    "expand_selection_right" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    "move_word_left" -> Icons.AutoMirrored.Filled.ArrowBack
    "move_word_right" -> Icons.AutoMirrored.Filled.ArrowForward
    "expand_selection_word_left" -> Icons.Filled.KeyboardDoubleArrowLeft
    "expand_selection_word_right" -> Icons.Filled.KeyboardDoubleArrowRight
    "page_start" -> Icons.Filled.VerticalAlignTop
    "page_end" -> Icons.Filled.VerticalAlignBottom
    "toggle_minimal_ui" -> Icons.Filled.Fullscreen
    "media_play_pause" -> Icons.Filled.PlayArrow
    "media_previous" -> Icons.Filled.SkipPrevious
    "media_next" -> Icons.Filled.SkipNext
    else -> Icons.Filled.Bolt
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun KeyMappingDialog(
    keyCode: Int,
    currentMapping: KeyMappingLoader.CtrlMapping?,
    defaultMapping: KeyMappingLoader.CtrlMapping?,
    onDismiss: () -> Unit,
    onSave: (KeyMappingLoader.CtrlMapping) -> Unit,
    commandTargets: List<CommandTarget> = LocalContext.current.let { context ->
        remember { CommandRegistry(context).getCommands(CommandSurface.NavMode) }
    },
    appIcons: Map<String, ImageBitmap> = emptyMap()
) {
    val keyLabel = getKeyLabel(keyCode)
    var selectedType by remember { mutableStateOf<String?>(currentMapping?.type) }
    var selectedValue by remember { mutableStateOf<String?>(currentMapping?.value) }
    var selectedCommandSource by remember { mutableStateOf<CommandSourceId?>(null) }
    val commandGroupsBySource = remember(commandTargets) {
        commandTargets.groupBy { it.source }
    }
    val defaultCommand = defaultMapping?.takeIf { it.type == "command" }
        ?.let { mapping -> commandTargets.firstOrNull { it.id == mapping.value } }
    val defaultLabel = defaultMapping?.let { mappingDescription(it, defaultCommand) }
    val defaultIcon = mappingIcon(defaultMapping, defaultCommand)
    val fontScale = LocalDensity.current.fontScale
    val minCellWidth = if (fontScale >= 1.4f) 200.dp else 180.dp
    val selection = keyMappingSelection(selectedType, selectedValue)
    val tone = pickerTone()
    // Compose measures dialog content against the full display, while the window manager clamps
    // the dialog window below the status bar. Size the card from the host window minus the
    // safe-drawing insets (bars, cutout, IME) so Cancel and Save always stay visible.
    val density = LocalDensity.current
    val hostView = LocalView.current
    val safeTopPx = WindowInsets.safeDrawing.getTop(density)
    val safeBottomPx = WindowInsets.safeDrawing.getBottom(density)
    val hostHeightPx = hostView.rootView.height.takeIf { it > 0 }
        ?: with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    val availableHeight = with(density) { (hostHeightPx - safeTopPx - safeBottomPx).coerceAtLeast(0).toDp() } - 16.dp
    // One fixed card height for every type, so switching type never resizes the dialog.
    val dialogHeight = availableHeight.coerceAtMost(640.dp)

    // label shown on the chip, full label for accessibility
    val types = listOf(
        Triple("keycode", stringResource(R.string.nav_mode_keycode), stringResource(R.string.nav_mode_keycode)),
        Triple("action", stringResource(R.string.nav_mode_action), stringResource(R.string.nav_mode_action)),
        Triple("native_ctrl", stringResource(R.string.nav_mode_native_ctrl_short), stringResource(R.string.nav_mode_native_ctrl)),
        Triple("command", stringResource(R.string.nav_mode_command), stringResource(R.string.nav_mode_command)),
        Triple("none", stringResource(R.string.nav_mode_none), stringResource(R.string.nav_mode_none))
    )

    val optionGroups: List<NavOptionGroup> = when (selectedType) {
        "keycode" -> listOf(
            NavOptionGroup(
                id = "direction",
                title = stringResource(R.string.nav_mode_category_direction),
                accent = tone.accent,
                options = listOf("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER")
                    .map { NavOptionEntry(value = it, label = it, icon = keycodeIcon(it)) }
            ),
            NavOptionGroup(
                id = "navigation_keys",
                title = stringResource(R.string.nav_mode_category_navigation_keys),
                accent = tone.accent,
                options = listOf("MOVE_HOME", "MOVE_END", "PAGE_UP", "PAGE_DOWN", "TAB", "ESCAPE", "FORWARD_DEL")
                    .map { NavOptionEntry(value = it, label = it, icon = keycodeIcon(it)) }
            )
        )
        "action" -> listOf(
            NavOptionGroup(
                id = "clipboard",
                title = stringResource(R.string.nav_mode_category_clipboard),
                accent = tone.accent,
                options = listOf("copy", "paste", "cut", "undo")
                    .map { NavOptionEntry(value = it, label = getActionLabel(it), icon = actionIcon(it)) }
            ),
            NavOptionGroup(
                id = "selection",
                title = stringResource(R.string.nav_mode_category_selection),
                accent = tone.accent,
                options = listOf(
                    "select_all",
                    "expand_selection_left",
                    "expand_selection_right",
                    "move_word_left",
                    "move_word_right",
                    "expand_selection_word_left",
                    "expand_selection_word_right"
                ).map { NavOptionEntry(value = it, label = getActionLabel(it), icon = actionIcon(it)) }
            ),
            NavOptionGroup(
                id = "document_ui",
                title = stringResource(R.string.nav_mode_category_document_ui),
                accent = tone.accent,
                options = listOf("page_start", "page_end", "toggle_minimal_ui")
                    .map { NavOptionEntry(value = it, label = getActionLabel(it), icon = actionIcon(it)) }
            ),
            NavOptionGroup(
                id = "media",
                title = stringResource(R.string.nav_mode_category_media),
                accent = tone.accent,
                options = listOf("media_play_pause", "media_previous", "media_next")
                    .map { NavOptionEntry(value = it, label = getActionLabel(it), icon = actionIcon(it)) }
            )
        )
        "command" -> commandGroupsBySource.entries
            .filter { (source, _) -> selectedCommandSource == null || source == selectedCommandSource }
            .map { (source, commands) ->
                NavOptionGroup(
                    id = source.storageValue,
                    title = source.displayLabel,
                    accent = tone.accent,
                    showCount = true,
                    options = commands.map {
                        NavOptionEntry(
                            value = it.id,
                            label = it.label,
                            icon = commandMaterialIcon(it),
                            appIcon = appIcons[it.id]
                        )
                    }
                )
            }
        else -> emptyList()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(dialogHeight)
                .semantics { paneTitle = keyLabel },
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header: keycap badge, title and the default shortcut share one line.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                        modifier = Modifier.defaultMinSize(minWidth = 30.dp, minHeight = 28.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (defaultLabel != null) {
                        val useDefault = stringResource(R.string.nav_mode_use_default, defaultLabel)
                        // Outlined reset chip: the app's reset icon, the default's own icon and its
                        // readable name; 48dp touch target around a compact visual.
                        Surface(
                            onClick = {
                                selectedType = defaultMapping?.type
                                selectedValue = defaultMapping?.value
                                selectedCommandSource = null
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .widthIn(max = 220.dp)
                                .semantics { contentDescription = useDefault }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                if (defaultIcon != null) {
                                    Icon(
                                        imageVector = defaultIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = defaultLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Type row: five equal segments with the icon above a single-line label, so all
                // types fit one row at 100–150% font scale without wrapping.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    types.forEach { (type, label, fullLabel) ->
                        TypeSegment(
                            label = label,
                            fullLabel = fullLabel,
                            icon = typeIcon(type),
                            selected = selectedType == type,
                            tone = tone,
                            onClick = {
                                if (selectedType != type) {
                                    selectedType = type
                                    selectedValue = null
                                    selectedCommandSource = null
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                // Value choices scroll; header, type row and footer stay fixed.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minCellWidth),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (selectedType == "command" && commandGroupsBySource.size > 1) {
                        item(key = "command_sources", span = { GridItemSpan(maxLineSpan) }, contentType = "sources") {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val sourceChips = listOf<Pair<CommandSourceId?, String>>(
                                    null to stringResource(R.string.nav_mode_command_source_all)
                                ) + commandGroupsBySource.keys.map { it to it.displayLabel }
                                sourceChips.forEach { (source, label) ->
                                    val sourceSelected = selectedCommandSource == source
                                    FilterChip(
                                        selected = sourceSelected,
                                        onClick = { selectedCommandSource = source },
                                        label = { Text(text = label, maxLines = 1, softWrap = false) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = source?.let(::commandSourceIcon) ?: Icons.Filled.Terminal,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = tone.container,
                                            selectedLabelColor = tone.content,
                                            selectedLeadingIconColor = tone.content,
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                            iconColor = tone.accent
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = sourceSelected,
                                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                            selectedBorderColor = tone.accent.copy(alpha = 0.75f)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    optionGroups.forEach { group ->
                        item(key = "header:$selectedType:${group.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            OptionCategoryHeader(
                                title = group.title,
                                accentColor = group.accent,
                                count = if (group.showCount) group.options.size else null
                            )
                        }
                        items(group.options, key = { "$selectedType:${it.value}" }, contentType = { "option" }) { entry ->
                            NavOptionTile(
                                label = entry.label,
                                selected = selectedValue == entry.value,
                                tone = tone,
                                icon = entry.icon,
                                appIcon = entry.appIcon,
                                onClick = { selectedValue = entry.value }
                            )
                        }
                    }

                    if (selectedType == "native_ctrl" || selectedType == "none") {
                        item(key = "hint:$selectedType", span = { GridItemSpan(maxLineSpan) }, contentType = "hint") {
                            val hintText = if (selectedType == "native_ctrl") {
                                stringResource(R.string.nav_mode_native_ctrl_hint, keyLabel)
                            } else {
                                stringResource(R.string.nav_mode_none_hint, keyLabel)
                            }
                            PickerModeHintCard(
                                text = hintText,
                                accentColor = tone.accent
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.nav_mode_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        // Disabled until the chosen type has a value, so a type switch cannot save as None.
                        onClick = { selection?.let(onSave) },
                        enabled = selection != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.nav_mode_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeSegment(
    label: String,
    fullLabel: String,
    icon: ImageVector,
    selected: Boolean,
    tone: PickerTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) tone.content else MaterialTheme.colorScheme.onSurface
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) tone.container else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = contentColor,
        border = BorderStroke(1.5.dp, if (selected) tone.accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.Tab
                if (label != fullLabel) contentDescription = fullLabel
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 1.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) tone.content else tone.accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                // No extra tracking: keeps "Command" whole at 150% in a fifth of a 480dp window.
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
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
    appIcon: ImageBitmap? = null,
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
    val border = BorderStroke(1.5.dp, if (selected) tone.accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

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
            if (appIcon != null) {
                Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            } else if (icon != null) {
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

/** Readable name of a mapping for headers and accessibility, rather than its internal id. */
@Composable
private fun mappingDescription(mapping: KeyMappingLoader.CtrlMapping, command: CommandTarget? = null): String =
    when (mapping.type) {
        "keycode" -> keycodeDescriptionRes(mapping.value)?.let { stringResource(it) } ?: mapping.value
        "action" -> actionDescriptionRes(mapping.value)?.let { stringResource(it) } ?: getActionLabel(mapping.value)
        "command" -> command?.label ?: mapping.value
        "native_ctrl" -> stringResource(R.string.nav_mode_native_ctrl)
        else -> stringResource(R.string.nav_mode_none)
    }

private fun keycodeDescriptionRes(keycode: String): Int? = when (keycode) {
    "DPAD_UP" -> R.string.nav_mode_desc_dpad_up
    "DPAD_DOWN" -> R.string.nav_mode_desc_dpad_down
    "DPAD_LEFT" -> R.string.nav_mode_desc_dpad_left
    "DPAD_RIGHT" -> R.string.nav_mode_desc_dpad_right
    "DPAD_CENTER" -> R.string.nav_mode_desc_dpad_center
    "MOVE_HOME" -> R.string.nav_mode_desc_move_home
    "MOVE_END" -> R.string.nav_mode_desc_move_end
    "PAGE_UP" -> R.string.nav_mode_desc_page_up
    "PAGE_DOWN" -> R.string.nav_mode_desc_page_down
    "TAB" -> R.string.nav_mode_desc_tab
    "ESCAPE" -> R.string.nav_mode_desc_escape
    "FORWARD_DEL" -> R.string.nav_mode_desc_forward_del
    else -> null
}

private fun actionDescriptionRes(action: String): Int? = when (action) {
    "copy" -> R.string.nav_mode_action_copy
    "paste" -> R.string.nav_mode_action_paste
    "cut" -> R.string.nav_mode_action_cut
    "undo" -> R.string.nav_mode_action_undo
    "select_all" -> R.string.nav_mode_desc_select_all
    "expand_selection_left" -> R.string.nav_mode_desc_expand_selection_left
    "expand_selection_right" -> R.string.nav_mode_desc_expand_selection_right
    "move_word_left" -> R.string.nav_mode_desc_move_word_left
    "move_word_right" -> R.string.nav_mode_desc_move_word_right
    "expand_selection_word_left" -> R.string.nav_mode_desc_expand_selection_word_left
    "expand_selection_word_right" -> R.string.nav_mode_desc_expand_selection_word_right
    "page_start" -> R.string.nav_mode_desc_page_start
    "page_end" -> R.string.nav_mode_desc_page_end
    "toggle_minimal_ui" -> R.string.nav_mode_desc_toggle_minimal_ui
    "media_play_pause" -> R.string.nav_mode_action_media_play_pause
    "media_previous" -> R.string.nav_mode_desc_media_previous
    "media_next" -> R.string.nav_mode_desc_media_next
    else -> null
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
        "action" -> getActionLabel(mapping.value)
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
