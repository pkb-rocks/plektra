package rocks.pkb.plektra

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

private enum class AppearanceColour { Main, Selection }

private val AppearancePresets = listOf(
    0xff8f4f2b.toInt() to 0xffd7a348.toInt(),
    0xff176b9b.toInt() to 0xff83cce5.toInt(),
    0xff276c4d.toInt() to 0xffa5cb76.toInt(),
    0xff79529c.toInt() to 0xffd0a2db.toInt(),
    0xffa33c44.toInt() to 0xffefad78.toInt(),
    0xff4d6173.toInt() to 0xff9bbed7.toInt()
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppAppearanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val value = rememberAppAppearance()
    var picker by remember { mutableStateOf<AppearanceColour?>(null) }
    val mainLabel = stringResource(R.string.app_appearance_main)
    val selectionLabel = stringResource(R.string.app_appearance_selection)

    Scaffold(topBar = {
        Surface {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back_content_description))
                }
                Text(stringResource(R.string.app_appearance_title),
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
        }
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.app_appearance_hint), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = value.enabled, role = Role.Switch,
                    onValueChange = { AppAppearance.save(context, value.copy(enabled = it)) }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.app_appearance_enabled), Modifier.weight(1f))
                Switch(checked = value.enabled, onCheckedChange = null)
            }
            Text(stringResource(R.string.app_appearance_palette), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppearancePresets.forEach { (main, selection) ->
                    val isSelected = value.enabled && value.main == main && value.selection == selection
                    val label = String.format(Locale.ROOT, "%s #%06X / %s #%06X",
                        mainLabel, main and 0xffffff, selectionLabel, selection and 0xffffff)
                    OutlinedButton(
                        onClick = { AppAppearance.save(context, AppAppearanceColors(true, main, selection)) },
                        border = BorderStroke(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = label
                            selected = isSelected
                        }
                    ) {
                        Box(Modifier.size(24.dp).background(Color(main), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(24.dp).background(Color(selection), CircleShape))
                    }
                }
            }
            listOf(AppearanceColour.Main to mainLabel, AppearanceColour.Selection to selectionLabel)
                .forEach { (kind, title) ->
                    OutlinedButton(onClick = { picker = kind }, modifier = Modifier.fillMaxWidth()) {
                        val colour = if (kind == AppearanceColour.Main) value.main else value.selection
                        Box(Modifier.size(24.dp).background(Color(colour), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text(title, Modifier.weight(1f))
                    }
                }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.app_appearance_preview), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {}) { Text(stringResource(R.string.app_appearance_normal)) }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Text(stringResource(R.string.app_appearance_selected))
                        }
                    }
                }
            }
            TextButton(onClick = { AppAppearance.reset(context) }) {
                Text(stringResource(R.string.app_appearance_reset))
            }
        }
    }
    picker?.let { kind ->
        KeyboardThemeColorPickerDialog(
            initialColor = if (kind == AppearanceColour.Main) value.main else value.selection,
            onDismiss = { picker = null },
            onPreviewColorChanged = {},
            onColorSelected = { colour ->
                val updated = if (kind == AppearanceColour.Main) value.copy(enabled = true, main = colour)
                    else value.copy(enabled = true, selection = colour)
                AppAppearance.save(context, updated)
                picker = null
            }
        )
    }
}
