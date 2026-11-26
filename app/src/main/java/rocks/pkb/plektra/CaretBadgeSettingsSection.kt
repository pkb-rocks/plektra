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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rocks.pkb.plektra.ui.color.ColorPickerDialog
import rocks.pkb.plektra.ui.color.ColorSwatch

/**
 * The modifier badge beside the text cursor: an on/off switch (with the overlay-permission prompt
 * it needs) and optional colour overrides, under the other modifier indicators.
 */
@Composable
fun CaretBadgeSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsManager.getCaretModifierBadgeEnabled(context)) }
    var armedColor by remember { mutableStateOf(SettingsManager.getCaretBadgeArmedColor(context)) }
    var lockedColor by remember { mutableStateOf(SettingsManager.getCaretBadgeLockedColor(context)) }
    var customColors by remember { mutableStateOf(SettingsManager.getCaretBadgeCustomColors(context)) }
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
        KeyboardBarCheckRow(
            label = stringResource(R.string.caret_badge_custom_colors_title),
            checked = customColors,
            linkId = SettingLinkIds.MODIFIERS_CARET_BADGE_CUSTOM_COLORS,
            description = stringResource(R.string.caret_badge_custom_colors_description)
        ) {
            customColors = it
            SettingsManager.setCaretBadgeCustomColors(context, it)
            if (!it) pickingColor = null
        }
    }
    if (enabled && customColors) {
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

    pickingColor?.takeIf { enabled && customColors }?.let { which ->
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
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsToggleRow(
            title = stringResource(R.string.caret_modifier_badge_title),
            description = stringResource(R.string.caret_modifier_badge_description),
            icon = Icons.Filled.Keyboard,
            checked = checked,
            linkId = SettingLinkIds.MODIFIERS_CARET_BADGE,
            onCheckedChange = onCheckedChange
        )
        // Drawing beside the cursor means drawing over the app being typed into, which Android
        // gates behind a permission the user grants themselves - no ADB, no developer options.
        if (checked && !overlayGranted) {
            SettingsSupportingText(
                text = stringResource(R.string.caret_modifier_badge_needs_overlay),
                dependent = true,
                isError = true
            )
            Column(modifier = Modifier.padding(start = SettingsSpace.textInsetWithIcon, end = SettingsSpace.edge, bottom = 8.dp)) {
                Button(onClick = onGrantOverlay) {
                    Text(stringResource(R.string.caret_modifier_badge_grant_overlay))
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
    SettingsNavigationRow(
        title = title,
        description = description,
        dependent = true,
        linkId = linkId,
        onClick = onClick,
        trailing = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.clearAndSetSemantics { }
            ) {
                ColorSwatch(argb = argb, selected = false, size = 32.dp)
            }
        }
    )
}
