package rocks.pkb.plektra

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.data.emoji.EmojiRendering
import rocks.pkb.plektra.data.emoji.EmojiSkinTone

/** Emoji appearance, default skin tone and the emoji/symbol/kaomoji pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPagesSettingsSection() {
    val context = LocalContext.current
    var style by remember { mutableStateOf(EmojiRendering.style(context)) }
    var skinTone by remember { mutableIntStateOf(EmojiSkinTone.get(context)) }
    var emojiPages by remember { mutableStateOf(EmojiPagesSettings.emojiPagesEnabled(context)) }
    var symbolPages by remember { mutableStateOf(EmojiPagesSettings.symbolPagesEnabled(context)) }
    var kaomoji by remember { mutableStateOf(EmojiPagesSettings.kaomojiPlacement(context)) }
    var pickerExtras by remember { mutableStateOf(EmojiPagesSettings.pickerSymbolsAndKaomoji(context)) }

    ChoiceRow(
        id = "sym.emoji_style",
        title = stringResource(R.string.emoji_style_title),
        description = stringResource(R.string.emoji_style_description)
    ) {
        EmojiRendering.Style.entries.forEach { option ->
            FilterChip(
                selected = style == option,
                onClick = {
                    style = option
                    EmojiRendering.setStyle(context, option)
                },
                label = {
                    Text(
                        stringResource(
                            if (option == EmojiRendering.Style.NOTO) R.string.emoji_style_noto else R.string.emoji_style_system
                        )
                    )
                }
            )
        }
    }

    ChoiceRow(id = "sym.emoji_skin_tone", title = stringResource(R.string.emoji_skin_tone_title)) {
        (0..EmojiSkinTone.MODIFIERS.size).forEach { tone ->
            FilterChip(
                selected = skinTone == tone,
                onClick = {
                    skinTone = tone
                    EmojiSkinTone.set(context, tone)
                },
                label = { Text(SKIN_TONE_SAMPLE + EmojiSkinTone.MODIFIERS.getOrElse(tone - 1) { "" }) }
            )
        }
    }

    SwitchRow(
        id = "sym.emoji_pages",
        title = stringResource(R.string.emoji_pages_emoji_title),
        description = stringResource(R.string.emoji_pages_description),
        checked = emojiPages
    ) {
        emojiPages = it
        EmojiPagesSettings.setEmojiPagesEnabled(context, it)
    }
    SwitchRow(
        id = "sym.symbol_pages",
        title = stringResource(R.string.emoji_pages_symbols_title),
        checked = symbolPages
    ) {
        symbolPages = it
        EmojiPagesSettings.setSymbolPagesEnabled(context, it)
    }

    ChoiceRow(id = "sym.kaomoji_pages", title = stringResource(R.string.kaomoji_placement_title)) {
        EmojiPagesSettings.KaomojiPlacement.entries.forEach { option ->
            FilterChip(
                selected = kaomoji == option,
                onClick = {
                    kaomoji = option
                    EmojiPagesSettings.setKaomojiPlacement(context, option)
                },
                label = {
                    Text(
                        stringResource(
                            when (option) {
                                EmojiPagesSettings.KaomojiPlacement.SYMBOLS -> R.string.kaomoji_placement_symbols
                                EmojiPagesSettings.KaomojiPlacement.EMOJI -> R.string.kaomoji_placement_emoji
                                EmojiPagesSettings.KaomojiPlacement.OFF -> R.string.kaomoji_placement_off
                            }
                        )
                    )
                }
            )
        }
    }

    SwitchRow(
        id = "sym.picker_symbols_kaomoji",
        title = stringResource(R.string.emoji_picker_extras_title),
        checked = pickerExtras
    ) {
        pickerExtras = it
        EmojiPagesSettings.setPickerSymbolsAndKaomoji(context, it)
    }
}

private const val SKIN_TONE_SAMPLE = "👋" // 👋

@Composable
private fun SwitchRow(
    id: String,
    title: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(modifier = Modifier.settingRow(id).fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    id: String,
    title: String,
    description: String? = null,
    chips: @Composable () -> Unit
) {
    Surface(modifier = Modifier.settingRow(id).fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        }
    }
}
