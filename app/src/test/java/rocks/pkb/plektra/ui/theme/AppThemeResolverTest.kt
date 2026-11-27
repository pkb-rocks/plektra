package rocks.pkb.plektra.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.keyboardThemePresets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppThemeResolverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPreferences() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun defaultPreferencesMatchExistingBehavior() {
        val prefs = SettingsManager.getAppAppearancePreferences(context)
        assertEquals(AppThemeMode.SYSTEM, prefs.themeMode)
        assertEquals(AppDarkSurfaceStyle.STANDARD, prefs.darkSurfaceStyle)
        assertTrue(prefs.dynamicColor)
        assertEquals(AppSettingsStyle.COMPACT_DIVIDERS, prefs.settingsStyle)

        val resolvedLight = AppThemeResolver.resolve(prefs, systemInDarkTheme = false, sdkInt = Build.VERSION_CODES.S)
        assertFalse(resolvedLight.useDarkTheme)
        assertFalse(resolvedLight.useOledBlack)
        assertTrue(resolvedLight.useDynamicColor)
        assertEquals(AppSettingsStyle.COMPACT_DIVIDERS, resolvedLight.settingsStyle)

        val resolvedDark = AppThemeResolver.resolve(prefs, systemInDarkTheme = true, sdkInt = Build.VERSION_CODES.S)
        assertTrue(resolvedDark.useDarkTheme)
        assertFalse(resolvedDark.useOledBlack)
        assertTrue(resolvedDark.useDynamicColor)
    }

    @Test
    fun explicitThemeModesOverrideSystemSetting() {
        val lightPrefs = AppAppearancePreferences(
            themeMode = AppThemeMode.LIGHT,
            darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
            dynamicColor = true
        )
        val resolvedLightOnDarkSystem = AppThemeResolver.resolve(
            preferences = lightPrefs,
            systemInDarkTheme = true,
            sdkInt = Build.VERSION_CODES.TIRAMISU
        )
        assertFalse(resolvedLightOnDarkSystem.useDarkTheme)
        assertFalse(resolvedLightOnDarkSystem.useOledBlack)
        assertTrue(resolvedLightOnDarkSystem.useDynamicColor)

        val darkPrefs = AppAppearancePreferences(
            themeMode = AppThemeMode.DARK,
            darkSurfaceStyle = AppDarkSurfaceStyle.STANDARD,
            dynamicColor = false
        )
        val resolvedDarkOnLightSystem = AppThemeResolver.resolve(
            preferences = darkPrefs,
            systemInDarkTheme = false,
            sdkInt = Build.VERSION_CODES.TIRAMISU
        )
        assertTrue(resolvedDarkOnLightSystem.useDarkTheme)
        assertFalse(resolvedDarkOnLightSystem.useOledBlack)
        assertFalse(resolvedDarkOnLightSystem.useDynamicColor)
    }

    @Test
    fun oledBlackOnlyActivatesExplicitlyInDarkMode() {
        val oledSystemPrefs = AppAppearancePreferences(
            themeMode = AppThemeMode.SYSTEM,
            darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
            dynamicColor = true
        )
        assertFalse(AppThemeResolver.resolve(oledSystemPrefs, systemInDarkTheme = false, sdkInt = 33).useOledBlack)
        assertTrue(AppThemeResolver.resolve(oledSystemPrefs, systemInDarkTheme = true, sdkInt = 33).useOledBlack)

        val oledDarkPrefs = AppAppearancePreferences(
            themeMode = AppThemeMode.DARK,
            darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
            dynamicColor = false
        )
        assertTrue(AppThemeResolver.resolve(oledDarkPrefs, systemInDarkTheme = false, sdkInt = 33).useOledBlack)

        val oledLightPrefs = AppAppearancePreferences(
            themeMode = AppThemeMode.LIGHT,
            darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
            dynamicColor = true
        )
        assertFalse(AppThemeResolver.resolve(oledLightPrefs, systemInDarkTheme = true, sdkInt = 33).useOledBlack)
    }

    @Test
    fun dynamicColorRespectsPreferenceAndSdkFloor() {
        val enabledPrefs = AppAppearancePreferences(dynamicColor = true)
        val disabledPrefs = AppAppearancePreferences(dynamicColor = false)

        assertFalse(AppThemeResolver.resolve(enabledPrefs, systemInDarkTheme = false, sdkInt = Build.VERSION_CODES.Q).useDynamicColor)
        assertFalse(AppThemeResolver.resolve(enabledPrefs, systemInDarkTheme = false, sdkInt = Build.VERSION_CODES.R).useDynamicColor)
        assertTrue(AppThemeResolver.resolve(enabledPrefs, systemInDarkTheme = false, sdkInt = Build.VERSION_CODES.S).useDynamicColor)
        assertFalse(AppThemeResolver.resolve(disabledPrefs, systemInDarkTheme = false, sdkInt = Build.VERSION_CODES.S).useDynamicColor)
        assertFalse(AppThemeResolver.resolve(disabledPrefs, systemInDarkTheme = true, sdkInt = Build.VERSION_CODES.TIRAMISU).useDynamicColor)
    }

    @Test
    fun standardDarkKeepsRegularMaterialSurfaceAndOledPreservesElevatedHierarchyAndAccents() {
        val standardDarkResolved = ResolvedAppTheme(useDarkTheme = true, useOledBlack = false, useDynamicColor = false)
        val standardScheme = AppThemeResolver.applySurfaceStyle(DarkColorScheme, standardDarkResolved)
        assertEquals(DarkColorScheme.surface, standardScheme.background)
        assertNotEquals(Color.Black, standardScheme.background)
        assertNotEquals(Color.Black, standardScheme.surface)

        val oledResolved = ResolvedAppTheme(useDarkTheme = true, useOledBlack = true, useDynamicColor = false)
        val oledScheme = AppThemeResolver.applySurfaceStyle(DarkColorScheme, oledResolved)
        assertEquals(Color.Black, oledScheme.background)
        assertEquals(Color.Black, oledScheme.surface)
        assertEquals(Color.Black, oledScheme.surfaceDim)
        assertEquals(Color.Black, oledScheme.surfaceContainerLowest)
        assertEquals(Color.Transparent, oledScheme.surfaceTint)
        // Dialogs, sheets, and cards stay visually distinct from pure black
        assertNotEquals(Color.Black, oledScheme.surfaceContainerLow)
        assertNotEquals(Color.Black, oledScheme.surfaceContainer)
        assertNotEquals(Color.Black, oledScheme.surfaceContainerHigh)
        assertNotEquals(Color.Black, oledScheme.surfaceContainerHighest)
        assertNotEquals(Color.Black, oledScheme.surfaceVariant)
        // Accent, semantic, and text colors remain untouched
        assertEquals(DarkColorScheme.primary, oledScheme.primary)
        assertEquals(DarkColorScheme.onPrimary, oledScheme.onPrimary)
        assertEquals(DarkColorScheme.primaryContainer, oledScheme.primaryContainer)
        assertEquals(DarkColorScheme.secondary, oledScheme.secondary)
        assertEquals(DarkColorScheme.tertiary, oledScheme.tertiary)
        assertEquals(DarkColorScheme.error, oledScheme.error)
        assertEquals(DarkColorScheme.onSurface, oledScheme.onSurface)
        assertEquals(DarkColorScheme.onSurfaceVariant, oledScheme.onSurfaceVariant)

        val lightResolved = ResolvedAppTheme(useDarkTheme = false, useOledBlack = false, useDynamicColor = false)
        val lightScheme = AppThemeResolver.applySurfaceStyle(LightColorScheme, lightResolved)
        assertEquals(LightColorScheme.surface, lightScheme.background)
        assertNotEquals(Color.Black, lightScheme.background)
    }

    @Test
    fun invalidStoredValuesFallBackSafelyToDefaults() {
        SettingsManager.getPreferences(context).edit()
            .putString(SettingsManager.KEY_APP_THEME_MODE, "invalid_mode")
            .putString(SettingsManager.KEY_APP_DARK_SURFACE_STYLE, "unknown_surface")
            .putString(SettingsManager.KEY_APP_SETTINGS_STYLE, "unknown_style")
            .commit()

        val prefs = SettingsManager.getAppAppearancePreferences(context)
        assertEquals(AppThemeMode.SYSTEM, prefs.themeMode)
        assertEquals(AppDarkSurfaceStyle.STANDARD, prefs.darkSurfaceStyle)
        assertTrue(prefs.dynamicColor)
        assertEquals(AppSettingsStyle.COMPACT_DIVIDERS, prefs.settingsStyle)
    }

    @Test
    fun appAppearanceDoesNotAlterKeyboardThemeOrCloudTapDefaults() {
        val defaultHardwareBefore = SettingsManager.getEffectiveKeyboardTheme(
            context,
            SettingsManager.KeyboardThemeTarget.HARDWARE
        )
        val defaultSoftwareBefore = SettingsManager.getEffectiveKeyboardTheme(
            context,
            SettingsManager.KeyboardThemeTarget.SOFTWARE
        )
        val cloudTapBefore = keyboardThemePresets().first { it.name == "Cloud Tap" }

        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(
                themeMode = AppThemeMode.DARK,
                darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
                dynamicColor = false
            )
        )

        assertEquals(
            defaultHardwareBefore,
            SettingsManager.getEffectiveKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE)
        )
        assertEquals(
            defaultSoftwareBefore,
            SettingsManager.getEffectiveKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.SOFTWARE)
        )
        assertEquals(cloudTapBefore, keyboardThemePresets().first { it.name == "Cloud Tap" })
        assertEquals(0xFFE1E3E7.toInt(), cloudTapBefore.background)
    }

    @Test
    fun platformDialogThemeMatchesResolvedPolarity() {
        assertEquals(
            android.R.style.Theme_DeviceDefault_Dialog_Alert,
            AppThemeResolver.platformAlertDialogThemeRes(useDarkTheme = true)
        )
        assertEquals(
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
            AppThemeResolver.platformAlertDialogThemeRes(useDarkTheme = false)
        )
    }
}
