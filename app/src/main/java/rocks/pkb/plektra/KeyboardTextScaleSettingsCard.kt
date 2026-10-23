package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable
internal fun KeyboardTextScaleSettingsCard() {
    val context = LocalContext.current
    var textScale by remember { mutableFloatStateOf(KeyboardBarTypography.scale(context)) }
    OutlinedCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .settingRow("keyboard_bar.text_scale")
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.keyboard_text_size_title), Modifier.weight(1f))
                Text(stringResource(R.string.keyboard_text_size_value, (textScale * 100f).roundToInt()))
            }
            Slider(
                value = textScale,
                onValueChange = { textScale = it },
                valueRange = KeyboardBarTypography.MIN_SCALE..KeyboardBarTypography.MAX_SCALE,
                steps = 7,
                onValueChangeFinished = { KeyboardBarTypography.setScale(context, textScale) }
            )
            val labelColor = MaterialTheme.colorScheme.onSurface.toArgb()
            AndroidView(
                factory = { KeyboardTextScalePreview(it) },
                update = { it.setLabelColor(labelColor); it.setScale(textScale) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.keyboard_text_size_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                textScale = 1f
                KeyboardBarTypography.setScale(context, 1f)
            }) {
                Text(stringResource(R.string.keyboard_text_size_default))
            }
        }
    }
}
