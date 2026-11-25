package rocks.pkb.plektra

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode
import rocks.pkb.plektra.ui.theme.AppThemeResolver
import rocks.pkb.plektra.ui.theme.LocalResolvedAppTheme
import rocks.pkb.plektra.ui.theme.rememberAppAppearancePreferences

@Composable
fun AppAppearanceSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val preferences = rememberAppAppearancePreferences()
    val resolvedTheme = LocalResolvedAppTheme.current
    val dynamicColorSupported = AppThemeResolver.isDynamicColorSupported()
    val darkSurfaceEnabled = preferences.themeMode != AppThemeMode.LIGHT

    Scaffold(
        topBar = {
            PlektraSettingsHeader(
                title = stringResource(R.string.app_appearance_title),
                onBack = onBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AppearancePreviewCard(
                preferences = preferences,
                resolvedDark = resolvedTheme.useDarkTheme,
                resolvedOled = resolvedTheme.useOledBlack,
                resolvedDynamic = resolvedTheme.useDynamicColor
            )

            // Theme mode section (System / Light / Dark)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceSectionHeader(
                    title = stringResource(R.string.app_appearance_mode_title),
                    description = stringResource(R.string.app_appearance_mode_description)
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingRow(SettingLinkIds.APP_APPEARANCE_THEME_MODE),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                    ) {
                        AppearanceRadioRow(
                            title = stringResource(R.string.app_appearance_mode_system),
                            description = stringResource(R.string.app_appearance_mode_system_description),
                            selected = preferences.themeMode == AppThemeMode.SYSTEM,
                            enabled = true,
                            onClick = { SettingsManager.setAppThemeMode(context, AppThemeMode.SYSTEM) },
                            leading = {
                                Icon(
                                    imageVector = Icons.Filled.BrightnessAuto,
                                    contentDescription = null,
                                    tint = if (preferences.themeMode == AppThemeMode.SYSTEM) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        )
                        SectionGroupDivider()
                        AppearanceRadioRow(
                            title = stringResource(R.string.app_appearance_mode_light),
                            description = stringResource(R.string.app_appearance_mode_light_description),
                            selected = preferences.themeMode == AppThemeMode.LIGHT,
                            enabled = true,
                            onClick = { SettingsManager.setAppThemeMode(context, AppThemeMode.LIGHT) },
                            leading = {
                                Icon(
                                    imageVector = Icons.Filled.LightMode,
                                    contentDescription = null,
                                    tint = if (preferences.themeMode == AppThemeMode.LIGHT) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        )
                        SectionGroupDivider()
                        AppearanceRadioRow(
                            title = stringResource(R.string.app_appearance_mode_dark),
                            description = stringResource(R.string.app_appearance_mode_dark_description),
                            selected = preferences.themeMode == AppThemeMode.DARK,
                            enabled = true,
                            onClick = { SettingsManager.setAppThemeMode(context, AppThemeMode.DARK) },
                            leading = {
                                Icon(
                                    imageVector = Icons.Filled.DarkMode,
                                    contentDescription = null,
                                    tint = if (preferences.themeMode == AppThemeMode.DARK) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Dark surface style section (Gray / OLED black)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceSectionHeader(
                    title = stringResource(R.string.app_appearance_dark_surface_title),
                    description = stringResource(
                        if (darkSurfaceEnabled) {
                            R.string.app_appearance_dark_surface_description
                        } else {
                            R.string.app_appearance_dark_surface_light_hint
                        }
                    )
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingRow(SettingLinkIds.APP_APPEARANCE_DARK_SURFACE),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                    ) {
                        AppearanceRadioRow(
                            title = stringResource(R.string.app_appearance_dark_surface_gray),
                            description = stringResource(R.string.app_appearance_dark_surface_gray_description),
                            selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.STANDARD,
                            enabled = darkSurfaceEnabled,
                            onClick = {
                                SettingsManager.setAppDarkSurfaceStyle(context, AppDarkSurfaceStyle.STANDARD)
                            },
                            leading = {
                                DarkSurfaceSwatch(
                                    canvasColor = Color(0xFF222028),
                                    cardColor = Color(0xFF3A3742),
                                    selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.STANDARD
                                )
                            }
                        )
                        SectionGroupDivider()
                        AppearanceRadioRow(
                            title = stringResource(R.string.app_appearance_dark_surface_oled),
                            description = stringResource(R.string.app_appearance_dark_surface_oled_description),
                            selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.OLED,
                            enabled = darkSurfaceEnabled,
                            onClick = {
                                SettingsManager.setAppDarkSurfaceStyle(context, AppDarkSurfaceStyle.OLED)
                            },
                            leading = {
                                DarkSurfaceSwatch(
                                    canvasColor = AppThemeResolver.OledCanvas,
                                    cardColor = AppThemeResolver.OledSurfaceContainerHigh,
                                    selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.OLED
                                )
                            }
                        )
                    }
                }
            }

            // Dynamic system color toggle
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceSectionHeader(
                    title = stringResource(R.string.app_appearance_color_title)
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingRow(SettingLinkIds.APP_APPEARANCE_DYNAMIC_COLOR),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    val checked = preferences.dynamicColor && dynamicColorSupported
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .toggleable(
                                value = checked,
                                enabled = dynamicColorSupported,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    SettingsManager.setAppDynamicColorEnabled(context, enabled)
                                }
                            )
                            .alpha(if (dynamicColorSupported) 1f else 0.55f)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ColorLens,
                            contentDescription = null,
                            tint = if (checked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_appearance_dynamic_color_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(
                                    if (dynamicColorSupported) {
                                        R.string.app_appearance_dynamic_color_description
                                    } else {
                                        R.string.app_appearance_dynamic_color_unavailable
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = dynamicColorSupported
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.app_appearance_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AppearancePreviewCard(
    preferences: AppAppearancePreferences,
    resolvedDark: Boolean,
    resolvedOled: Boolean,
    resolvedDynamic: Boolean
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val activeBadge = when {
        !resolvedDark -> stringResource(R.string.app_appearance_mode_light)
        resolvedOled -> stringResource(R.string.app_appearance_dark_surface_oled)
        else -> stringResource(R.string.app_appearance_mode_dark)
    }
    val summary = currentAppAppearanceSummary(context, preferences)
    val previewAccessibility = stringResource(
        R.string.app_appearance_preview_content_description,
        summary
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = previewAccessibility
            },
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Miniature surface sample using the active Material 3 colorScheme tokens
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colorScheme.background,
                border = BorderStroke(1.dp, colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(colorScheme.primary)
                            )
                            Box(
                                modifier = Modifier
                                    .width(72.dp)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colorScheme.onSurface.copy(alpha = 0.75f))
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = colorScheme.primaryContainer
                        ) {
                            Text(
                                text = activeBadge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colorScheme.onSurface.copy(alpha = 0.72f))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.55f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(42.dp)
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colorScheme.onSurface.copy(alpha = 0.72f))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(26.dp)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(colorScheme.primary)
                                        .padding(2.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(colorScheme.onPrimary)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AccentSwatchDot(color = colorScheme.primary)
                    AccentSwatchDot(color = colorScheme.secondary)
                    AccentSwatchDot(
                        color = if (resolvedDynamic) colorScheme.tertiary else colorScheme.surfaceContainerHighest
                    )
                }
            }
        }
    }
}

@Composable
private fun AccentSwatchDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

@Composable
private fun DarkSurfaceSwatch(
    canvasColor: Color,
    cardColor: Color,
    selected: Boolean
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(canvasColor)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .padding(horizontal = 3.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(cardColor)
        )
    }
}

@Composable
private fun AppearanceSectionHeader(
    title: String,
    description: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppearanceRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        leading()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
    }
}

@Composable
private fun SectionGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

fun currentAppAppearanceSummary(
    context: Context,
    preferences: AppAppearancePreferences = SettingsManager.getAppAppearancePreferences(context)
): String {
    val parts = mutableListOf<String>()
    parts += when (preferences.themeMode) {
        AppThemeMode.SYSTEM -> context.getString(R.string.app_appearance_mode_system)
        AppThemeMode.LIGHT -> context.getString(R.string.app_appearance_mode_light)
        AppThemeMode.DARK -> context.getString(R.string.app_appearance_mode_dark)
    }
    if (preferences.themeMode != AppThemeMode.LIGHT &&
        preferences.darkSurfaceStyle == AppDarkSurfaceStyle.OLED
    ) {
        parts += context.getString(R.string.app_appearance_dark_surface_oled)
    }
    if (preferences.dynamicColor && AppThemeResolver.isDynamicColorSupported()) {
        parts += context.getString(R.string.app_appearance_summary_dynamic)
    }
    return parts.joinToString(" · ")
}
