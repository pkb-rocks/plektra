package rocks.pkb.plektra.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import rocks.pkb.plektra.SettingsManager

enum class AppThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

enum class AppDarkSurfaceStyle(val storageValue: String) {
    STANDARD("standard"),
    OLED("oled");

    companion object {
        fun fromStorage(value: String?): AppDarkSurfaceStyle =
            entries.firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

data class AppAppearancePreferences(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val darkSurfaceStyle: AppDarkSurfaceStyle = AppDarkSurfaceStyle.STANDARD,
    val dynamicColor: Boolean = true
)

data class ResolvedAppTheme(
    val useDarkTheme: Boolean,
    val useOledBlack: Boolean,
    val useDynamicColor: Boolean
)

object AppThemeResolver {
    val OledCanvas: Color = Color(0xFF000000)
    val OledSurfaceContainerLow: Color = Color(0xFF0E0E0E)
    val OledSurfaceContainer: Color = Color(0xFF141414)
    val OledSurfaceContainerHigh: Color = Color(0xFF1A1A1A)
    val OledSurfaceContainerHighest: Color = Color(0xFF222222)
    val OledSurfaceBright: Color = Color(0xFF282828)
    val OledSurfaceVariant: Color = Color(0xFF1A1A1A)

    fun isDynamicColorSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.S

    fun resolve(
        preferences: AppAppearancePreferences,
        systemInDarkTheme: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): ResolvedAppTheme {
        val useDarkTheme = when (preferences.themeMode) {
            AppThemeMode.SYSTEM -> systemInDarkTheme
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
        }
        val useOledBlack = useDarkTheme && preferences.darkSurfaceStyle == AppDarkSurfaceStyle.OLED
        val useDynamicColor = preferences.dynamicColor && isDynamicColorSupported(sdkInt)
        return ResolvedAppTheme(
            useDarkTheme = useDarkTheme,
            useOledBlack = useOledBlack,
            useDynamicColor = useDynamicColor
        )
    }

    fun resolve(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): ResolvedAppTheme = resolve(
        preferences = SettingsManager.getAppAppearancePreferences(context),
        systemInDarkTheme = SettingsManager.isSystemDarkTheme(context),
        sdkInt = sdkInt
    )

    fun applySurfaceStyle(
        baseScheme: ColorScheme,
        resolved: ResolvedAppTheme
    ): ColorScheme = if (resolved.useOledBlack) {
        baseScheme.copy(
            background = OledCanvas,
            surface = OledCanvas,
            surfaceDim = OledCanvas,
            surfaceContainerLowest = OledCanvas,
            surfaceContainerLow = OledSurfaceContainerLow,
            surfaceContainer = OledSurfaceContainer,
            surfaceContainerHigh = OledSurfaceContainerHigh,
            surfaceContainerHighest = OledSurfaceContainerHighest,
            surfaceBright = OledSurfaceBright,
            surfaceVariant = OledSurfaceVariant,
            surfaceTint = Color.Transparent
        )
    } else {
        baseScheme.copy(background = baseScheme.surface)
    }

    fun platformAlertDialogThemeRes(useDarkTheme: Boolean): Int = if (useDarkTheme) {
        android.R.style.Theme_DeviceDefault_Dialog_Alert
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }

    fun isAppearancePreferenceKey(key: String?): Boolean = key == null ||
        key == SettingsManager.KEY_APP_THEME_MODE ||
        key == SettingsManager.KEY_APP_DARK_SURFACE_STYLE ||
        key == SettingsManager.KEY_APP_DYNAMIC_COLOR
}
