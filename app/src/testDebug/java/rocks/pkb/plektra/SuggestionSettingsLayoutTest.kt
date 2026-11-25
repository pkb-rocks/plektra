package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h2400dp")
class SuggestionSettingsLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun startWordsHaveTheirOwnSwitchIndependentOfPersonalization() {
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        compose.setContent { AutoCorrectionCategoryScreen(onBack = {}) }
        val title = context.getString(R.string.auto_correct_starter_words_title)
        compose.onNodeWithText(title).performScrollTo().assertIsOn()
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        assertFalse(SettingsManager.getSuggestionStarterWordsEnabled(context))
        assertTrue(SettingsManager.getSuggestionsPersonalizationEnabled(context))
    }

    @Test
    fun spaceAfterSuggestionsIsASecondEntryForTheSameStoredSetting() {
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        val before = SettingsManager.getAutoSpaceAfterSuggestion(context)
        compose.setContent { AutoCorrectionCategoryScreen(onBack = {}) }
        val title = context.getString(R.string.auto_space_after_suggestion_title)
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(!before, SettingsManager.getAutoSpaceAfterSuggestion(context))
        // Changed on its home page, the second entry follows.
        SettingsManager.setAutoSpaceAfterSuggestion(context, before)
        compose.waitForIdle()
        if (before) compose.onNodeWithText(title).assertIsOn() else compose.onNodeWithText(title).assertIsOff()
        assertEquals(1, compose.onAllNodesWithText(context.getString(R.string.status_bar_buttons_title)).fetchSemanticsNodes().size)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun autoCorrectionTextIsNeverCutAtLargeFontInGerman() {
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        val german = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.GERMAN)
        }
        val localized = context.createConfigurationContext(german)
        compose.setContent {
            val host = androidx.compose.ui.platform.LocalContext.current
            // Wraps the host activity, which the settings page navigates with.
            val germanHost = androidx.compose.runtime.remember(host) {
                android.view.ContextThemeWrapper(host, 0).apply { applyOverrideConfiguration(german) }
            }
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides germanHost,
                androidx.compose.ui.platform.LocalConfiguration provides german,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.3f)
            ) { AutoCorrectionCategoryScreen(onBack = {}) }
        }
        assertNoTextOverflow(localized.getString(R.string.experimental_suggestions_title))
    }

    /** Every laid-out text keeps all its lines; [mustAppear] guards against an untranslated run. */
    private fun assertNoTextOverflow(mustAppear: String) {
        compose.onNodeWithText(mustAppear).assertExists()
        val nodes = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.forEach { layout ->
                // Lines beyond maxLines or a too small height; the width flag misreports under Robolectric.
                val cut = layout.didOverflowHeight || (0 until layout.lineCount).any(layout::isLineEllipsized)
                assertFalse("Cut text: ${layout.layoutInput.text}", cut)
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun layoutAwareCtrlSummaryStaysWithinTwoLines() {
        SettingsManager.setNavModeEnabled(context, true)
        compose.setContent { NavModeSettingsScreen(onBack = {}) }
        val summary = context.getString(R.string.layout_aware_ctrl_shortcuts_description)
        val node = compose.onNodeWithText(summary).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue(layouts.single().lineCount <= 2)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [33], qualifiers = "de-w411dp-h2400dp")
    fun layoutAwareCtrlSummaryStaysWithinTwoLinesInGerman() = layoutAwareCtrlSummaryStaysWithinTwoLines()

    @Test
    fun navModeGuideOpensFromTheTopBarHelpAction() {
        compose.setContent { NavModeSettingsScreen(onBack = {}) }
        val guide = context.getString(R.string.nav_mode_guide_title)
        assertTrue(compose.onAllNodesWithText(guide).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(guide).performClick()
        compose.onNodeWithText(guide).assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [33], qualifiers = "w411dp-h1500dp-xhdpi")
    fun renderSettingsScreenshotsWhenRequested() {
        val outputPath = System.getenv("PLEKTRA_SETTINGS_SCREENSHOT_DIR")
        assumeNotNull("Set PLEKTRA_SETTINGS_SCREENSHOT_DIR to render PNG previews", outputPath)
        val outputDir = File(outputPath!!).apply { mkdirs() }

        fun capture(fileName: String, page: SettingsPage, dark: Boolean, fontScale: Float = 1f, localeTag: String? = null) {
            SettingsManager.getPreferences(context).edit().clear().commit()
            SettingsManager.setExperimentalSuggestionsEnabled(context, true)
            SettingsManager.setAppLanguageTag(context, localeTag)
            SettingsManager.setAppAppearancePreferences(
                context,
                AppAppearancePreferences(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT, AppDarkSurfaceStyle.OLED, dynamicColor = false)
            )
            val config = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            val scenario = ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java).putSettingsPage(page))
            try {
                compose.waitForIdle()
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val dm = activity.resources.displayMetrics
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                    )
                    decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
                    val bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bitmap))
                    FileOutputStream(File(outputDir, fileName)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            } finally {
                scenario.close()
            }
        }

        capture("auto-correction-light.png", SettingsPage(SettingsDestination.AutoCorrection), dark = false)
        capture("auto-correction-dark-large-font-de.png", SettingsPage(SettingsDestination.AutoCorrection), dark = true, fontScale = 1.3f, localeTag = "de")
        capture("smart-features-light.png", SettingsPage(SettingsDestination.TextInput), dark = false)
        capture("nav-mode-dark.png", SettingsPage(SettingsDestination.NavMode), dark = true)
    }
}
