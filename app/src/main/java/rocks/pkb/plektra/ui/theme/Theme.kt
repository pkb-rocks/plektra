package rocks.pkb.plektra.ui.theme

import android.app.Activity
import android.os.Build
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rocks.pkb.plektra.SettingsManager

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

val LocalResolvedAppTheme = staticCompositionLocalOf {
    ResolvedAppTheme(
        useDarkTheme = false,
        useOledBlack = false,
        useDynamicColor = true
    )
}

@Composable
fun rememberAppAppearancePreferences(): AppAppearancePreferences {
    if (LocalInspectionMode.current) {
        return AppAppearancePreferences()
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var preferences by remember(context) {
        mutableStateOf(SettingsManager.getAppAppearancePreferences(context))
    }

    DisposableEffect(context) {
        val sharedPrefs = SettingsManager.getPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (AppThemeResolver.isAppearancePreferenceKey(key)) {
                preferences = SettingsManager.getAppAppearancePreferences(context)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        preferences = SettingsManager.getAppAppearancePreferences(context)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                preferences = SettingsManager.getAppAppearancePreferences(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return preferences
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlektraTheme(
    darkTheme: Boolean? = null,
    darkSurfaceStyle: AppDarkSurfaceStyle? = null,
    dynamicColor: Boolean? = null,
    applySystemBars: Boolean = true,
    statusBarOnDarkSurface: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val storedPreferences = rememberAppAppearancePreferences()
    val effectivePreferences = AppAppearancePreferences(
        themeMode = when (darkTheme) {
            true -> AppThemeMode.DARK
            false -> AppThemeMode.LIGHT
            null -> storedPreferences.themeMode
        },
        darkSurfaceStyle = darkSurfaceStyle ?: storedPreferences.darkSurfaceStyle,
        dynamicColor = dynamicColor ?: storedPreferences.dynamicColor
    )
    val resolved = AppThemeResolver.resolve(
        preferences = effectivePreferences,
        systemInDarkTheme = systemDark
    )

    val baseScheme = when {
        resolved.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (resolved.useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolved.useDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = AppThemeResolver.applySurfaceStyle(baseScheme, resolved)

    if (applySystemBars && !view.isInEditMode) {
        SideEffect {
            val activity = context.findActivity() ?: return@SideEffect
            val window = activity.window ?: return@SideEffect
            // true = light system bar surface (dark icons); false = dark surface (light icons)
            val useLightStatusBar = if (statusBarOnDarkSurface) false else !resolved.useDarkTheme
            val useLightNavigationBar = !resolved.useDarkTheme

            if (activity is ComponentActivity) {
                val statusStyle = if (useLightStatusBar) {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                } else {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                }
                val navigationStyle = if (useLightNavigationBar) {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                } else {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                }
                activity.enableEdgeToEdge(
                    statusBarStyle = statusStyle,
                    navigationBarStyle = navigationStyle
                )
            }

            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = useLightStatusBar
                isAppearanceLightNavigationBars = useLightNavigationBar
            }
            window.setBackgroundDrawable(ColorDrawable(colorScheme.background.toArgb()))
        }
    }

    CompositionLocalProvider(LocalResolvedAppTheme provides resolved) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
