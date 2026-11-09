package rocks.pkb.plektra

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils

internal data class AppAppearanceColors(
    val enabled: Boolean = false,
    val main: Int = 0xff8f4f2b.toInt(),
    val selection: Int = 0xffd7a348.toInt()
)

/** Optional UI colours. Keyboard themes have independent preferences. */
internal object AppAppearance {
    const val ENABLED = "app_appearance_enabled"
    const val MAIN = "app_appearance_main"
    const val SELECTION = "app_appearance_selection"
    val keys = setOf(ENABLED, MAIN, SELECTION)

    fun opaque(value: Int): Int = value or 0xff000000.toInt()

    fun read(context: Context): AppAppearanceColors {
        val prefs = SettingsManager.getPreferences(context)
        val defaults = AppAppearanceColors()
        return AppAppearanceColors(
            enabled = runCatching { prefs.getBoolean(ENABLED, false) }.getOrDefault(false),
            main = opaque(runCatching { prefs.getInt(MAIN, defaults.main) }.getOrDefault(defaults.main)),
            selection = opaque(runCatching {
                prefs.getInt(SELECTION, defaults.selection)
            }.getOrDefault(defaults.selection))
        )
    }

    fun save(context: Context, value: AppAppearanceColors) {
        SettingsManager.getPreferences(context).edit()
            .putBoolean(ENABLED, value.enabled)
            .putInt(MAIN, opaque(value.main))
            .putInt(SELECTION, opaque(value.selection))
            .apply()
    }

    fun reset(context: Context) {
        SettingsManager.getPreferences(context).edit()
            .remove(ENABLED).remove(MAIN).remove(SELECTION).apply()
    }

    fun readable(background: Int): Int = if (
        ColorUtils.calculateContrast(AndroidColor.BLACK, opaque(background)) >=
        ColorUtils.calculateContrast(AndroidColor.WHITE, opaque(background))
    ) AndroidColor.BLACK else AndroidColor.WHITE

    private fun blend(a: Int, b: Int, amount: Float): Int = ColorUtils.blendARGB(a, b, amount)

    private fun accent(value: Int, surface: Int): Int {
        val ink = readable(surface)
        return (0..20).asSequence()
            .map { blend(value, ink, it / 20f) }
            .first { ColorUtils.calculateContrast(it, surface) >= 4.5 }
    }

    fun palette(base: ColorScheme, dark: Boolean, value: AppAppearanceColors): ColorScheme {
        if (!value.enabled) return base
        val main = opaque(value.main)
        val selected = opaque(value.selection)
        val neutral = if (dark) 0xff121212.toInt() else 0xfffffbf7.toInt()
        fun surface(amount: Float) = Color(blend(neutral, main, amount))
        val background = surface(if (dark) .08f else .025f)
        val primary = accent(main, background.toArgb())
        val secondary = accent(selected, background.toArgb())
        val primaryContainer = surface(if (dark) .35f else .18f)

        return base.copy(
            primary = Color(primary),
            onPrimary = Color(readable(primary)),
            primaryContainer = primaryContainer,
            onPrimaryContainer = Color(readable(primaryContainer.toArgb())),
            secondary = Color(secondary),
            onSecondary = Color(readable(secondary)),
            secondaryContainer = Color(selected),
            onSecondaryContainer = Color(readable(selected)),
            tertiary = Color(primary),
            onTertiary = Color(readable(primary)),
            tertiaryContainer = primaryContainer,
            onTertiaryContainer = Color(readable(primaryContainer.toArgb())),
            background = background,
            onBackground = Color(readable(background.toArgb())),
            surface = background,
            onSurface = Color(readable(background.toArgb())),
            surfaceVariant = surface(.13f),
            onSurfaceVariant = Color(blend(readable(background.toArgb()), background.toArgb(), .2f)),
            surfaceDim = surface(if (dark) .035f else .12f),
            surfaceBright = surface(if (dark) .18f else .005f),
            surfaceContainerLowest = surface(0f),
            surfaceContainerLow = surface(.035f),
            surfaceContainer = surface(.06f),
            surfaceContainerHigh = surface(.10f),
            surfaceContainerHighest = surface(.15f),
            surfaceTint = Color(primary)
        )
    }
}

@Composable
internal fun rememberAppAppearance(): AppAppearanceColors {
    val context = LocalContext.current
    val prefs = remember(context) { SettingsManager.getPreferences(context) }
    var value by remember(prefs) { mutableStateOf(AppAppearance.read(context)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in AppAppearance.keys) value = AppAppearance.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}
