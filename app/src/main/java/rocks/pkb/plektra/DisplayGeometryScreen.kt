package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import rocks.pkb.plektra.inputmethod.DisplayCorners
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import rocks.pkb.plektra.R

@Composable
fun DisplayGeometryScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var cornerSizeDialog by remember { mutableStateOf(false) }
    var titan2EliteRoundedCornerInsetsEnabled by remember {
        mutableStateOf(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
    }
    var titan2EliteFillBottomCorners by remember {
        mutableStateOf(SettingsManager.getTitan2EliteFillBottomCorners(context))
    }
    var topCornerMultiplier by remember {
        mutableStateOf(SettingsManager.getTitan2EliteTopCornerMultiplier(context))
    }
    var maxIconShrink by remember { mutableStateOf(SettingsManager.getTitan2EliteMaxIconShrink(context)) }

    Scaffold(modifier = modifier, topBar = {
        PlektraSettingsHeader(stringResource(R.string.keyboard_bar_geometry), onBack)
    }) { paddingValues ->
        Column(Modifier.fillMaxWidth().padding(paddingValues).verticalScroll(rememberScrollState())) {
        // A stored choice stays reachable even on a display without rounded corners.
        if (DisplayCorners.hasRoundedDisplay(context) || titan2EliteRoundedCornerInsetsEnabled) {
        SettingsSectionDivider(stringResource(R.string.device_specific_interface_section))
        SettingsToggleRow(
            title = stringResource(R.string.titan2_elite_rounded_corners_title),
            description = stringResource(R.string.titan2_elite_rounded_corners_description),
            checked = titan2EliteRoundedCornerInsetsEnabled,
            linkId = "status_bar.rounded_corners",
            onCheckedChange = { enabled ->
                titan2EliteRoundedCornerInsetsEnabled = enabled
                SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, enabled)
            }
        )

        if (titan2EliteRoundedCornerInsetsEnabled) {
            SettingsToggleRow(
                title = stringResource(R.string.titan2_elite_fill_bottom_corners_title),
                description = stringResource(R.string.titan2_elite_fill_bottom_corners_description),
                checked = titan2EliteFillBottomCorners,
                linkId = "status_bar.fill_bottom_corners",
                onCheckedChange = { enabled ->
                    titan2EliteFillBottomCorners = enabled
                    SettingsManager.setTitan2EliteFillBottomCorners(context, enabled)
                }
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                ListItem(modifier = Modifier.fillMaxWidth().settingRow("status_bar.top_corner").clickable { cornerSizeDialog = true },
                    headlineContent = { Text(stringResource(R.string.keyboard_bar_corner_size)) },
                    supportingContent = { Text("${topCornerMultiplier}×") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
                Text(stringResource(R.string.titan2_elite_top_corner_description),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.titan2_elite_max_icon_shrink_title, maxIconShrink),
                    modifier = Modifier.fillMaxWidth().settingRow("status_bar.max_icon_shrink"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.titan2_elite_max_icon_shrink_description),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = maxIconShrink.toFloat(), valueRange = 0f..90f, steps = 8,
                    onValueChange = { maxIconShrink = (it / 10f).toInt() * 10 },
                    onValueChangeFinished = { SettingsManager.setTitan2EliteMaxIconShrink(context, maxIconShrink) })
            }
        }

        } else {
            Text(stringResource(R.string.keyboard_bar_geometry_unavailable),
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        }
    }
    if (cornerSizeDialog) AlertDialog(onDismissRequest = { cornerSizeDialog = false },
        title = { Text(stringResource(R.string.titan2_elite_top_corner_title)) },
        text = { Column {
            listOf(1, 2, 4, 6).forEach { multiplier ->
                Row(Modifier.fillMaxWidth().selectable(topCornerMultiplier == multiplier, role = Role.RadioButton,
                    onClick = {
                        topCornerMultiplier = multiplier
                        SettingsManager.setTitan2EliteTopCornerMultiplier(context, multiplier)
                        cornerSizeDialog = false
                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(topCornerMultiplier == multiplier, onClick = null)
                    Text("${multiplier}×", Modifier.padding(start = 8.dp))
                }
            }
        } }, confirmButton = { TextButton(onClick = { cornerSizeDialog = false }) { Text(stringResource(android.R.string.cancel)) } })

}
