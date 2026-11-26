package rocks.pkb.plektra.uipoc

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.*
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.PlektraTheme

/**
 * Renders the settings screens for visual inspection and comparison.
 * Rendering runs only when PLEKTRA_UIPOC_RENDER names an output label.
 * Set PLEKTRA_UIPOC_STYLE=material to render the Material layout style instead of Compact with dividers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h1800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HybridCRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class Variant(
        val name: String,
        val dark: Boolean,
        val oled: Boolean,
        val locale: Locale,
        val fontScale: Float
    )

    private val variants = listOf(
        Variant("en-light-100", dark = false, oled = false, locale = Locale.ENGLISH, fontScale = 1f),
        Variant("de-oled-100", dark = true, oled = true, locale = Locale.GERMAN, fontScale = 1f),
        Variant("de-dark-150", dark = true, oled = false, locale = Locale.GERMAN, fontScale = 1.5f)
    )

    private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "00-main" to { SettingsScreen() },
        "01-keyboards-devices" to {
            KeyboardsDevicesSettingsScreen(onBack = {}, onNavModeSettingsClick = {}, onOpenKeyboardTheme = {},
                destination = KeyboardsDevicesDestination.Main, onDestinationChange = {})
        },
        "02-keyboards-onscreen" to {
            KeyboardsDevicesSettingsScreen(onBack = {}, onNavModeSettingsClick = {}, onOpenKeyboardTheme = {},
                destination = KeyboardsDevicesDestination.OnScreen, onDestinationChange = {})
        },
        "03-keyboards-builtin" to {
            KeyboardsDevicesSettingsScreen(onBack = {}, onNavModeSettingsClick = {}, onOpenKeyboardTheme = {},
                destination = KeyboardsDevicesDestination.BuiltIn, onDestinationChange = {})
        },
        "04-text-input" to { TextInputSettingsScreen(onBack = {}) },
        "05-autocorrection" to { AutoCorrectionCategoryScreen(onBack = {}) },
        "06-speech" to { SpeechSettingsScreen(onBack = {}) },
        "07-accessibility" to { AccessibilitySettingsScreen(onBack = {}) },
        "08-customization" to { CustomizationSettingsScreen(onBack = {}) },
        "09-nav-mode" to { NavModeSettingsScreen(onBack = {}) },
        "10-advanced" to { AdvancedSettingsScreen(onBack = {}) },
        "11-about" to { AboutScreen(onBack = {}) },
        "12-input-styles" to { CustomInputStylesScreen(onBack = {}) },
        "13-app-language" to { AppLanguageSettingsScreen(onBack = {}) },
        "14-appearance" to { AppAppearanceSettingsScreen(onBack = {}) },
        "15-modifiers" to { ModifierSettingsScreen(onBack = {}, onOpenSymLayers = {}, onOpenSymShortcuts = {}, onOpenNavMode = {}) },
        "16-sym" to { SymCustomizationScreen(onBack = {}) },
        "17-keyboard-bar" to { KeyboardBarSettingsScreen(onBack = {}, onCustomizeVariations = {}) },
        "18-keyboard-bar-buttons" to { KeyboardBarSettingsScreen(onBack = {}, onCustomizeVariations = {}, page = KeyboardBarSettingsPage.Buttons) },
        "19-virtual-keyboard" to { VirtualKeyboardBehaviorSettingsScreen(onBack = {}, onOpenKeyboardTheme = {}) },
        "20-keyboard-theme" to { KeyboardThemeScreen(onBack = {}) },
        "21-hardware-keyboard" to { HardwareKeyboardSettingsScreen(onBack = {}) },
        "22-trackpad" to { TrackpadGestureSettingsScreen(onBack = {}) },
        "23-variations" to { VariationCustomizationScreen(onBack = {}) },
        "24-text-expansion" to { TextExpansionSettingsScreen(onBack = {}) },
        "25-app-enter" to { AppEnterBehaviorScreen(onBack = {}) },
        "26-launcher-shortcuts" to { LauncherShortcutsScreen(onBack = {}) },
        "27-app-icon" to { AppIconSettingsScreen(onBack = {}) },
        "28-main-background" to { MainBackgroundSettingsScreen(onBack = {}) },
        "29-display-geometry" to { DisplayGeometryScreen(onBack = {}) },
        "30-autocorrect-languages" to { AutoCorrectSettingsScreen(onBack = {}) },
        "31-languages" to { LanguagesScreen() },
        "32-clicks-power" to { ClicksPowerKeyboardSettingsScreen(onBack = {}) },
        "33-device-sym-editor" to { DeviceSymLayerEditorStubScreen(onBack = {}) },
        "34-raw-mode-apps" to { AppRawModeScreen(onBack = {}) }
    )

    @Test
    fun renderCatalogCoversUniqueScreens() {
        val ids = screens.map { it.first }
        assertEquals(35, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.contains("00-main"))
        assertTrue(ids.contains("14-appearance"))
        assertTrue(ids.contains("09-nav-mode"))
    }

    @Test
    fun renderScreenshotsWhenRequested() {
        val label = System.getenv("PLEKTRA_UIPOC_RENDER")
        assumeTrue(!label.isNullOrBlank())
        val only = System.getenv("PLEKTRA_UIPOC_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val skip = (System.getenv("PLEKTRA_UIPOC_SKIP") ?: "20-").split(',').filter { it.isNotBlank() }
        val variantFilter = System.getenv("PLEKTRA_UIPOC_VARIANTS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val style = AppSettingsStyle.fromStorage(System.getenv("PLEKTRA_UIPOC_STYLE"))
        val out = File(System.getenv("PLEKTRA_UIPOC_OUT") ?: "build/uipoc-renders", label!!).apply { mkdirs() }

        val targetVariants = variants.filter { v -> variantFilter == null || v.name in variantFilter }
        val targetScreens = screens.filter { (id, _) ->
            (only == null || only.any { id.startsWith(it) }) && skip.none { id.startsWith(it) }
        }

        val currentRender = androidx.compose.runtime.mutableStateOf<Pair<Variant, Pair<String, @Composable () -> Unit>>?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val pair = currentRender.value ?: return@setContent
            val (variant, screen) = pair
            val host = LocalContext.current
            val config = remember(variant) {
                Configuration(host.resources.configuration).apply {
                    setLocale(variant.locale)
                    fontScale = variant.fontScale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (variant.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
            }
            val localized = remember(variant) { ContextThemeWrapper(host, 0).apply { applyOverrideConfiguration(config) } }
            val density = LocalDensity.current
            androidx.compose.runtime.key(variant.name, screen.first) {
                CompositionLocalProvider(
                    LocalContext provides localized,
                    LocalConfiguration provides config,
                    LocalDensity provides Density(density.density, variant.fontScale)
                ) {
                    PlektraTheme(
                        darkTheme = variant.dark,
                        darkSurfaceStyle = if (variant.oled) AppDarkSurfaceStyle.OLED else AppDarkSurfaceStyle.STANDARD,
                        dynamicColor = false,
                        settingsStyle = style,
                        applySystemBars = false
                    ) {
                        androidx.compose.material3.Surface(
                            color = androidx.compose.material3.MaterialTheme.colorScheme.background
                        ) { screen.second() }
                    }
                }
            }
        }

        for (variant in targetVariants) {
            for (screen in targetScreens) {
                SettingsManager.getPreferences(context).edit().clear().commit()
                SettingsManager.setAppSettingsStyle(context, style)
                compose.runOnUiThread { currentRender.value = variant to screen }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                repeat(20) {
                    compose.mainClock.advanceTimeByFrame()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
                compose.runOnUiThread {
                    val root = compose.activity.window.decorView
                    root.requestLayout()
                    root.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(root.width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(root.height, android.view.View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, root.width, root.height)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    File(out, "${screen.first}__${variant.name}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        }
    }
}
