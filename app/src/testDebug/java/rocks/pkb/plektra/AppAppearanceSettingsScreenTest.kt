package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.activity.compose.setContent
import org.junit.Assume.assumeNotNull
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AppAppearanceSettingsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before
    fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private fun launchSettingsMain() {
        scenario = ActivityScenario.launch(Intent(context, SettingsActivity::class.java))
        compose.waitForIdle()
    }

    private fun launchAppearanceSettings() {
        val intent = Intent(context, SettingsActivity::class.java)
            .putSettingsPage(SettingsPage(SettingsDestination.AppAppearance))
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    @Test
    fun settingsMainExposesAppearanceEntryAndOpensAppearanceScreen() {
        launchSettingsMain()

        val title = context.getString(R.string.app_appearance_title)
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()

        // The category opens in the same Activity, so folding and unfolding keep it open.
        scenario!!.onActivity { activity ->
            assertEquals(null, shadowOf(activity).nextStartedActivity)
        }
        compose.onNodeWithText(context.getString(R.string.app_appearance_mode_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_title)).assertIsDisplayed()
    }

    @Test
    fun searchIndexFindsAppearanceControlsInEnglishAndGerman() {
        val enQueries = listOf("Appearance", "OLED", "Dynamic", "Dark surfaces", "Layout style", "dividers")
        enQueries.forEach { query ->
            val results = SettingLinkRegistry.search(context, query)
            assertTrue(
                "Expected AppAppearance result for English query '$query'",
                results.any { it.route.destination == SettingsDestination.AppAppearance }
            )
        }
        assertTrue(
            SettingLinkRegistry.search(context, "Layout style")
                .any { it.id == SettingLinkIds.APP_APPEARANCE_SETTINGS_STYLE }
        )

        val deConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.GERMAN)
        }
        val deContext = context.createConfigurationContext(deConfig)
        val deQueries = listOf("Darstellung", "OLED", "Dynamische", "Dunkle Flächen", "Darstellungsstil", "Trennlinien")
        deQueries.forEach { query ->
            val results = SettingLinkRegistry.search(deContext, query)
            assertTrue(
                "Expected AppAppearance result for German query '$query'",
                results.any { it.route.destination == SettingsDestination.AppAppearance }
            )
        }
        assertTrue(
            SettingLinkRegistry.search(deContext, "Darstellungsstil")
                .any { it.id == SettingLinkIds.APP_APPEARANCE_SETTINGS_STYLE }
        )
    }

    @Test
    fun selectingDarkOledMaterialStyleAndDisablingDynamicColorPersistsAcrossRecreation() {
        launchAppearanceSettings()

        // Defaults selected initially
        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_compact_dividers)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.app_appearance_mode_system)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dark_surface_gray)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dynamic_color_title)).assertIsOn()

        // Switch to Material style + Dark + OLED black + Dynamic off
        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_material)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.app_appearance_mode_dark)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dark_surface_oled)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dynamic_color_title)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(
            AppAppearancePreferences(
                themeMode = AppThemeMode.DARK,
                darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
                dynamicColor = false,
                settingsStyle = AppSettingsStyle.MATERIAL
            ),
            SettingsManager.getAppAppearancePreferences(context)
        )

        scenario!!.onActivity { activity ->
            val bg = activity.window.decorView.background as? ColorDrawable
            assertEquals(AndroidColor.BLACK, bg?.color)
            val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(insetsController.isAppearanceLightStatusBars)
            assertFalse(insetsController.isAppearanceLightNavigationBars)
        }

        // Recreate activity and confirm selections and OLED styling persist
        scenario!!.recreate()
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_material)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.app_appearance_mode_dark)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dark_surface_oled)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dynamic_color_title)).assertIsOff()

        scenario!!.onActivity { activity ->
            val bg = activity.window.decorView.background as? ColorDrawable
            assertEquals(AndroidColor.BLACK, bg?.color)
        }
    }

    @Test
    fun lightModeUpdatesSystemBarsDisablesDarkSurfacesAndPreservesStoredOledSelection() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(
                themeMode = AppThemeMode.DARK,
                darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
                dynamicColor = false
            )
        )

        launchAppearanceSettings()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dark_surface_oled))
            .assertIsEnabled()
            .assertIsSelected()

        // Switch to Light mode
        compose.onNodeWithText(context.getString(R.string.app_appearance_mode_light)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(AppThemeMode.LIGHT, SettingsManager.getAppThemeMode(context))
        // Stored dark surface choice is preserved even while disabled in Light mode
        assertEquals(AppDarkSurfaceStyle.OLED, SettingsManager.getAppDarkSurfaceStyle(context))

        compose.onNodeWithText(context.getString(R.string.app_appearance_dark_surface_gray)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dark_surface_oled)).assertIsNotEnabled()

        scenario!!.onActivity { activity ->
            val bg = activity.window.decorView.background as? ColorDrawable
            assertNotEquals(AndroidColor.BLACK, bg?.color)
            val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertTrue(insetsController.isAppearanceLightStatusBars)
            assertTrue(insetsController.isAppearanceLightNavigationBars)
        }

        // Switching back to System re-enables Dark surface options
        compose.onNodeWithText(context.getString(R.string.app_appearance_mode_system)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dark_surface_oled))
            .assertIsEnabled()
            .assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w360dp-h1200dp-notnight")
    fun largeFontScaleRendersAllAppearanceControlsCleanly() {
        val config = Configuration(context.resources.configuration).apply {
            fontScale = 1.6f
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        launchAppearanceSettings()

        listOf(
            R.string.app_appearance_layout_style_compact_dividers,
            R.string.app_appearance_layout_style_material,
            R.string.app_appearance_mode_system,
            R.string.app_appearance_mode_light,
            R.string.app_appearance_mode_dark,
            R.string.app_appearance_dark_surface_gray,
            R.string.app_appearance_dark_surface_oled,
            R.string.app_appearance_dynamic_color_title,
            R.string.app_appearance_scope_note
        ).forEach { resId ->
            compose.onNodeWithText(context.getString(resId)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h915dp-notnight-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun germanLargeFontOledRendersVisibleAndClickableBackArrow() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.OLED, dynamicColor = false)
        )
        SettingsManager.setAppLanguageTag(context, "de")
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.GERMAN)
            fontScale = 1.5f
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        launchAppearanceSettings()

        val deContext = context.createConfigurationContext(config)
        val backDescription = deContext.getString(R.string.settings_back_content_description)
        val backNode = compose.onNodeWithContentDescription(backDescription)
        backNode.assertIsDisplayed().assertIsEnabled()

        val bounds = backNode.fetchSemanticsNode().boundsInRoot
        assertTrue("Back button width should be positive, was $bounds", bounds.width > 0f)
        assertTrue("Back button height should be positive, was $bounds", bounds.height > 0f)
        assertTrue("Back button should be inside viewport top/left, was $bounds", bounds.left >= 0f && bounds.top >= 0f)

        // Verify the rendered bitmap actually contains bright icon pixels inside the back button bounds
        var brightPixelsInBackButton = 0
        scenario!!.onActivity { activity ->
            val decor = activity.window.decorView
            val dm = activity.resources.displayMetrics
            val width = dm.widthPixels.coerceAtLeast(1)
            val height = dm.heightPixels.coerceAtLeast(1)
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            val left = bounds.left.toInt().coerceIn(0, width - 1)
            val top = bounds.top.toInt().coerceIn(0, height - 1)
            val right = bounds.right.toInt().coerceIn(left + 1, width)
            val bottom = bounds.bottom.toInt().coerceIn(top + 1, height)
            for (y in top until bottom) {
                for (x in left until right) {
                    val px = bitmap.getPixel(x, y)
                    if (AndroidColor.red(px) > 128 && AndroidColor.green(px) > 128 && AndroidColor.blue(px) > 128) {
                        brightPixelsInBackButton++
                    }
                }
            }
            bitmap.recycle()
        }
        assertTrue(
            "Expected visible bright back-arrow pixels inside $bounds, found $brightPixelsInBackButton",
            brightPixelsInBackButton > 20
        )

        backNode.performClick()
        compose.waitForIdle()
        scenario!!.onActivity { activity ->
            assertTrue("Clicking back button should finish SettingsActivity", activity.isFinishing)
        }
    }

    @Test
    @Config(sdk = [30], qualifiers = "w411dp-h1200dp")
    fun dynamicColorToggleIsDisabledBelowApi31() {
        launchAppearanceSettings()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dynamic_color_title))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertIsOff()
    }

    @Test
    @Config(sdk = [31], qualifiers = "w411dp-h1200dp")
    fun dynamicColorToggleIsEnabledOnApi31() {
        launchAppearanceSettings()
        compose.onNodeWithText(context.getString(R.string.app_appearance_dynamic_color_title))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertIsOn()
    }

    @Test
    fun darkHeaderVisibilityHeuristicSwitchesAtStatusBarMidpoint() {
        assertTrue(isDarkHeaderBehindStatusBar(headerHeightPx = 0, scrollOffsetPx = 0, statusBarHeightPx = 96))
        assertFalse(isDarkHeaderBehindStatusBar(headerHeightPx = 0, scrollOffsetPx = 20, statusBarHeightPx = 96))
        assertTrue(isDarkHeaderBehindStatusBar(headerHeightPx = 420, scrollOffsetPx = 0, statusBarHeightPx = 96))
        assertTrue(isDarkHeaderBehindStatusBar(headerHeightPx = 420, scrollOffsetPx = 371, statusBarHeightPx = 96))
        assertFalse(isDarkHeaderBehindStatusBar(headerHeightPx = 420, scrollOffsetPx = 372, statusBarHeightPx = 96))
        assertFalse(isDarkHeaderBehindStatusBar(headerHeightPx = 420, scrollOffsetPx = 500, statusBarHeightPx = 96))
        assertTrue(isDarkHeaderBehindStatusBar(headerHeightPx = 300, scrollOffsetPx = 0, statusBarHeightPx = 0))
        assertFalse(isDarkHeaderBehindStatusBar(headerHeightPx = 300, scrollOffsetPx = 300, statusBarHeightPx = 0))
    }

    @Test
    fun darkHeaderModeKeepsReadableLightStatusBarIconsWhileNavBarFollowsLightMode() {
        SettingsManager.setAppThemeMode(context, AppThemeMode.LIGHT)

        val hostScenario = ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            hostScenario.onActivity { activity ->
                activity.setContent {
                    rocks.pkb.plektra.ui.theme.PlektraTheme(statusBarOnDarkSurface = true) {
                        androidx.compose.material3.Surface {
                            androidx.compose.material3.Text("Header Host")
                        }
                    }
                }
            }
            compose.waitForIdle()
            hostScenario.onActivity { activity ->
                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                // Dark header behind status bar keeps status bar icons light
                assertFalse(insetsController.isAppearanceLightStatusBars)
                // Navigation bar sits over Light Scaffold, so nav bar icons are dark
                assertTrue(insetsController.isAppearanceLightNavigationBars)
            }
        } finally {
            hostScenario.close()
        }
    }

    @Test
    fun notesActivityFollowsAppAppearancePreferences() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(
                themeMode = AppThemeMode.DARK,
                darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
                dynamicColor = true
            )
        )
        val oledController = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val oledBg = oledController.get().findViewById<android.view.View>(R.id.notes_body).rootView
            val rootLinear = oledController.get().findViewById<android.view.View>(R.id.notes_heading).parent.parent as android.view.View
            assertEquals(AndroidColor.BLACK, (rootLinear.background as? ColorDrawable)?.color)
        } finally {
            oledController.pause().stop().destroy()
        }

        SettingsManager.setAppThemeMode(context, AppThemeMode.LIGHT)
        val lightController = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val rootLinear = lightController.get().findViewById<android.view.View>(R.id.notes_heading).parent.parent as android.view.View
            assertEquals(AndroidColor.rgb(250, 250, 248), (rootLinear.background as? ColorDrawable)?.color)
        } finally {
            lightController.pause().stop().destroy()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [33], qualifiers = "w411dp-h915dp-xhdpi")
    fun renderAppearanceScreenshotsWhenRequested() {
        val outputPath = System.getenv("PLEKTRA_APPEARANCE_SCREENSHOT_DIR")
        assumeNotNull("Set PLEKTRA_APPEARANCE_SCREENSHOT_DIR to render PNG previews", outputPath)
        val outputDir = File(outputPath!!).apply { mkdirs() }

        fun capture(
            fileName: String,
            preferences: AppAppearancePreferences,
            localeTag: String? = null,
            fontScale: Float = 1.0f
        ) {
            SettingsManager.getPreferences(context).edit().clear().commit()
            SettingsManager.setAppAppearancePreferences(context, preferences)
            SettingsManager.setAppLanguageTag(context, localeTag)
            val config = Configuration(context.resources.configuration).apply {
                this.fontScale = fontScale
            }
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)

            val intent = Intent(context, SettingsActivity::class.java)
                .putSettingsPage(SettingsPage(SettingsDestination.AppAppearance))
            val captureScenario = ActivityScenario.launch<SettingsActivity>(intent)
            try {
                compose.waitForIdle()
                captureScenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val dm = activity.resources.displayMetrics
                    val width = dm.widthPixels.coerceAtLeast(1)
                    val height = dm.heightPixels.coerceAtLeast(1)
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    decor.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    decor.draw(canvas)
                    FileOutputStream(File(outputDir, fileName)).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                }
            } finally {
                captureScenario.close()
            }
        }

        capture(
            fileName = "appearance-light.png",
            preferences = AppAppearancePreferences(AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
        )
        capture(
            fileName = "appearance-dark-gray.png",
            preferences = AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
        )
        capture(
            fileName = "appearance-dark-oled.png",
            preferences = AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.OLED, dynamicColor = false)
        )
        capture(
            fileName = "appearance-de-largefont-oled.png",
            preferences = AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.OLED, dynamicColor = false),
            localeTag = "de",
            fontScale = 1.5f
        )
    }
}
