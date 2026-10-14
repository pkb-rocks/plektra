package rocks.pkb.plektra

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rocks.pkb.plektra.ui.color.ColorPickerDialog
import rocks.pkb.plektra.ui.color.ColorSwatch

/**
 * The modifier badge beside the text cursor: an on/off switch (with the overlay-permission prompt
 * it needs) and the two colours it is drawn in. Sits under the modifier indicator picker on the
 * modifiers screen because it answers the same question - where the keyboard tells you what Shift
 * and Alt are doing.
 */
@Composable
fun CaretBadgeSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsManager.getCaretModifierBadgeEnabled(context)) }
    var armedColor by remember { mutableStateOf(SettingsManager.getCaretBadgeArmedColor(context)) }
    var lockedColor by remember { mutableStateOf(SettingsManager.getCaretBadgeLockedColor(context)) }
    var pickingColor by remember { mutableStateOf<String?>(null) }

    // The grant happens in system settings, so re-read it each time this screen comes back.
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayGranted = Settings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CaretBadgeSwitchRow(
        checked = enabled,
        overlayGranted = overlayGranted,
        onCheckedChange = {
            enabled = it
            SettingsManager.setCaretModifierBadgeEnabled(context, it)
        },
        onGrantOverlay = {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    )

    if (enabled) {
        CaretBadgeColorRow(
            title = stringResource(R.string.caret_badge_color_armed),
            description = stringResource(R.string.caret_badge_color_armed_description),
            argb = armedColor,
            linkId = SettingLinkIds.MODIFIERS_CARET_BADGE_ARMED_COLOR,
            onClick = { pickingColor = "armed" }
        )
        CaretBadgeColorRow(
            title = stringResource(R.string.caret_badge_color_locked),
            description = stringResource(R.string.caret_badge_color_locked_description),
            argb = lockedColor,
            linkId = SettingLinkIds.MODIFIERS_CARET_BADGE_LOCKED_COLOR,
            onClick = { pickingColor = "locked" }
        )
    }

    pickingColor?.let { which ->
        val armed = which == "armed"
        ColorPickerDialog(
            title = stringResource(
                if (armed) R.string.caret_badge_color_armed else R.string.caret_badge_color_locked
            ),
            current = if (armed) armedColor else lockedColor,
            swatches = CARET_BADGE_SWATCHES,
            subtitle = stringResource(R.string.caret_badge_color_pick),
            onPick = { argb ->
                if (armed) {
                    armedColor = argb
                    SettingsManager.setCaretBadgeArmedColor(context, argb)
                } else {
                    lockedColor = argb
                    SettingsManager.setCaretBadgeLockedColor(context, argb)
                }
                pickingColor = null
            },
            onDismiss = { pickingColor = null }
        )
    }
}

/**
 * The quick picks offered for the cursor modifiers. Saturated mid-tones, because these glyphs are
 * drawn over an app whose background is unknown - a very pale or very dark pick reads on one theme
 * and vanishes on the other. The wheel underneath still allows anything.
 */
private val CARET_BADGE_SWATCHES = listOf(
    0xFF2563EB.toInt(), 0xFFDC2626.toInt(), 0xFF16A34A.toInt(), 0xFFEA580C.toInt(),
    0xFF9333EA.toInt(), 0xFF0891B2.toInt(), 0xFFDB2777.toInt(), 0xFFCA8A04.toInt(),
    0xFF475569.toInt(), 0xFF111827.toInt()
)

@Composable
private fun CaretBadgeSwitchRow(
    checked: Boolean,
    overlayGranted: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onGrantOverlay: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().settingRow(SettingLinkIds.MODIFIERS_CARET_BADGE)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.caret_modifier_badge_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.caret_modifier_badge_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            // Drawing beside the cursor means drawing over the app being typed into, which Android
            // gates behind a permission the user grants themselves - no ADB, no developer options.
            if (checked && !overlayGranted) {
                Column(modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(
                        text = stringResource(R.string.caret_modifier_badge_needs_overlay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onGrantOverlay) {
                        Text(stringResource(R.string.caret_modifier_badge_grant_overlay))
                    }
                }
            }
        }
    }
}

@Composable
private fun CaretBadgeColorRow(
    title: String,
    description: String,
    argb: Int,
    linkId: String,
    onClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().settingRow(linkId).clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ColorSwatch(argb = argb, selected = false, size = 32.dp)
        }
    }
}
