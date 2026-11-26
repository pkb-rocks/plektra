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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
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
        SettingsSectionScrollColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(8.dp))
            AppearancePreviewCard(
                modifier = Modifier.padding(horizontal = SettingsSpace.edge),
                preferences = preferences,
                resolvedDark = resolvedTheme.useDarkTheme,
                resolvedOled = resolvedTheme.useOledBlack,
                resolvedDynamic = resolvedTheme.useDynamicColor,
                resolvedStyle = resolvedTheme.settingsStyle
            )

            // Layout style section (Compact with dividers / Material)
            SettingsSectionDivider(
                label = stringResource(R.string.app_appearance_layout_style_title),
                accentRole = SettingsAccentRole.TERTIARY
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingRow(SettingLinkIds.APP_APPEARANCE_SETTINGS_STYLE)
                    .selectableGroup()
            ) {
                SettingsRadioChoice(
                    label = stringResource(R.string.app_appearance_layout_style_compact_dividers),
                    description = stringResource(R.string.app_appearance_layout_style_compact_dividers_description),
                    selected = preferences.settingsStyle == AppSettingsStyle.COMPACT_DIVIDERS,
                    onSelect = {
                        SettingsManager.setAppSettingsStyle(context, AppSettingsStyle.COMPACT_DIVIDERS)
                    },
                    trailingRadio = true,
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.ViewStream,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
                SettingsRadioChoice(
                    label = stringResource(R.string.app_appearance_layout_style_material),
                    description = stringResource(R.string.app_appearance_layout_style_material_description),
                    selected = preferences.settingsStyle == AppSettingsStyle.MATERIAL,
                    onSelect = {
                        SettingsManager.setAppSettingsStyle(context, AppSettingsStyle.MATERIAL)
                    },
                    trailingRadio = true,
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.ViewAgenda,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            }
            SettingsToggleRow(
                title = stringResource(R.string.app_appearance_settings_fullscreen_title),
                description = stringResource(R.string.app_appearance_settings_fullscreen_description),
                icon = Icons.Filled.Fullscreen,
                checked = preferences.settingsFullscreen,
                linkId = SettingLinkIds.APP_APPEARANCE_SETTINGS_FULLSCREEN,
                onCheckedChange = { SettingsManager.setSettingsFullscreenEnabled(context, it) }
            )

            // Theme mode section (System / Light / Dark)
            SettingsSectionDivider(
                label = stringResource(R.string.app_appearance_mode_title),
                accentRole = SettingsAccentRole.PRIMARY
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingRow(SettingLinkIds.APP_APPEARANCE_THEME_MODE)
                    .selectableGroup()
            ) {
                SettingsRadioChoice(
                    label = stringResource(R.string.app_appearance_mode_system),
                    description = stringResource(R.string.app_appearance_mode_system_description),
                    selected = preferences.themeMode == AppThemeMode.SYSTEM,
                    onSelect = { SettingsManager.setAppThemeMode(context, AppThemeMode.SYSTEM) },
                    trailingRadio = true,
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.BrightnessAuto,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
                SettingsRadioChoice(
                    label = stringResource(R.string.app_appearance_mode_light),
                    description = stringResource(R.string.app_appearance_mode_light_description),
                    selected = preferences.themeMode == AppThemeMode.LIGHT,
                    onSelect = { SettingsManager.setAppThemeMode(context, AppThemeMode.LIGHT) },
                    trailingRadio = true,
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.LightMode,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
                SettingsRadioChoice(
                    label = stringResource(R.string.app_appearance_mode_dark),
                    description = stringResource(R.string.app_appearance_mode_dark_description),
                    selected = preferences.themeMode == AppThemeMode.DARK,
                    onSelect = { SettingsManager.setAppThemeMode(context, AppThemeMode.DARK) },
                    trailingRadio = true,
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.DarkMode,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            }

            // Dark surface style section (Gray / OLED black)
            SettingsSectionDivider(
                label = stringResource(R.string.app_appearance_dark_surface_title),
                accentRole = SettingsAccentRole.PRIMARY
            )
            if (!darkSurfaceEnabled) {
                SettingsSupportingText(stringResource(R.string.app_appearance_dark_surface_light_hint))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingRow(SettingLinkIds.APP_APPEARANCE_DARK_SURFACE)
                    .selectableGroup()
            ) {
                SettingsRadioChoice(
                    label = stringResource(R.string.app_appearance_dark_surface_gray),
                    description = stringResource(R.string.app_appearance_dark_surface_gray_description),
                    selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.STANDARD,
                    enabled = darkSurfaceEnabled,
                    onSelect = {
                        SettingsManager.setAppDarkSurfaceStyle(context, AppDarkSurfaceStyle.STANDARD)
                    },
                    trailingRadio = true,
                    leading = {
                        DarkSurfaceSwatch(
                            canvasColor = Color(0xFF222028),
                            cardColor = Color(0xFF3A3742),
                            selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.STANDARD
                        )
                    }
                )
                SettingsRadioChoice(
                    label = stringResource(R.string.app_appearance_dark_surface_oled),
                    description = stringResource(R.string.app_appearance_dark_surface_oled_description),
                    selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.OLED,
                    enabled = darkSurfaceEnabled,
                    onSelect = {
                        SettingsManager.setAppDarkSurfaceStyle(context, AppDarkSurfaceStyle.OLED)
                    },
                    trailingRadio = true,
                    leading = {
                        DarkSurfaceSwatch(
                            canvasColor = AppThemeResolver.OledCanvas,
                            cardColor = AppThemeResolver.OledSurfaceContainerHigh,
                            selected = preferences.darkSurfaceStyle == AppDarkSurfaceStyle.OLED
                        )
                    }
                )
            }

            // Dynamic system color toggle
            SettingsSectionDivider(
                label = stringResource(R.string.app_appearance_color_title),
                accentRole = SettingsAccentRole.TERTIARY
            )
            val dynamicChecked = preferences.dynamicColor && dynamicColorSupported
            SettingsToggleRow(
                title = stringResource(R.string.app_appearance_dynamic_color_title),
                description = stringResource(
                    if (dynamicColorSupported) {
                        R.string.app_appearance_dynamic_color_description
                    } else {
                        R.string.app_appearance_dynamic_color_unavailable
                    }
                ),
                icon = Icons.Filled.ColorLens,
                accentRole = SettingsAccentRole.TERTIARY,
                checked = dynamicChecked,
                enabled = dynamicColorSupported,
                alwaysInlineDescription = true,
                linkId = SettingLinkIds.APP_APPEARANCE_DYNAMIC_COLOR,
                onCheckedChange = { enabled ->
                    SettingsManager.setAppDynamicColorEnabled(context, enabled)
                }
            )

            SettingsSupportingText(stringResource(R.string.app_appearance_scope_note))
            SettingsBottomSpacer()
        }
    }
}

@Composable
private fun AppearancePreviewCard(
    modifier: Modifier = Modifier,
    preferences: AppAppearancePreferences,
    resolvedDark: Boolean,
    resolvedOled: Boolean,
    resolvedDynamic: Boolean,
    resolvedStyle: AppSettingsStyle
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
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = previewAccessibility
            },
        shape = RoundedCornerShape(SettingsSpace.cardCorner),
        color = colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Miniature preview reflecting active color scheme AND layout style
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(colorScheme.primary)
                        )
                        if (resolvedStyle == AppSettingsStyle.COMPACT_DIVIDERS) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = colorScheme.outlineVariant
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
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
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val tileColor = if (resolvedStyle == AppSettingsStyle.MATERIAL) {
                            colorScheme.surfaceContainer
                        } else {
                            colorScheme.background
                        }
                        val tileBorder = if (resolvedStyle == AppSettingsStyle.COMPACT_DIVIDERS) {
                            BorderStroke(0.5.dp, colorScheme.outlineVariant.copy(alpha = 0.6f))
                        } else {
                            null
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(if (resolvedStyle == AppSettingsStyle.MATERIAL) 12.dp else 6.dp),
                            color = tileColor,
                            border = tileBorder
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (resolvedStyle == AppSettingsStyle.MATERIAL) 18.dp else 10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (resolvedStyle == AppSettingsStyle.MATERIAL) colorScheme.primaryContainer
                                            else colorScheme.primary
                                        )
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.75f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(colorScheme.onSurface.copy(alpha = 0.72f))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.55f)
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    )
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(if (resolvedStyle == AppSettingsStyle.MATERIAL) 12.dp else 6.dp),
                            color = if (resolvedStyle == AppSettingsStyle.MATERIAL) colorScheme.surfaceContainerHigh else tileColor,
                            border = tileBorder
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(42.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(colorScheme.onSurface.copy(alpha = 0.72f))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(colorScheme.primary)
                                        .padding(2.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
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
                    AccentSwatchDot(color = colorScheme.tertiary)
                    AccentSwatchDot(
                        color = if (resolvedDynamic) colorScheme.secondary else colorScheme.surfaceContainerHighest
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
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(canvasColor)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(11.dp)
                .padding(horizontal = 3.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(cardColor)
        )
    }
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
    if (preferences.settingsStyle == AppSettingsStyle.MATERIAL) {
        parts += context.getString(R.string.app_appearance_summary_material)
    }
    return parts.joinToString(", ")
}
