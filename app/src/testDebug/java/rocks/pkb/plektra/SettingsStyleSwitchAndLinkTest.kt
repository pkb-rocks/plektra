package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SettingsStyleSwitchAndLinkTest {
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

    @Test
    fun deepLinkToLayoutStyleOpensAppearanceAndBackFinishesCleanly() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("plektra://setting/${SettingLinkIds.APP_APPEARANCE_SETTINGS_STYLE}"))
            .setClass(context, SettingsActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()

        // Layout style section and both options are reachable and the heading carries heading semantics.
        val styleHeading = context.getString(R.string.app_appearance_layout_style_title)
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
                .and(hasText(styleHeading))
        ).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_compact_dividers))
            .assertIsSelected()

        // Switch live to Material style and verify preference and summary update immediately.
        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_material))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertEquals(AppSettingsStyle.MATERIAL, SettingsManager.getAppSettingsStyle(context))
        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_material))
            .assertIsSelected()
        assertTrue(
            currentAppAppearanceSummary(context).contains(context.getString(R.string.app_appearance_summary_material))
        )

        // Back finishes the deep-linked activity cleanly.
        compose.onNodeWithContentDescription(context.getString(R.string.settings_back_content_description))
            .performClick()
        compose.waitForIdle()
        scenario!!.onActivity { activity ->
            assertTrue("Back on deep-linked Appearance should finish Activity", activity.isFinishing)
        }
    }

    @Test
    fun externalStyleChangeUpdatesOpenSettingsScreenLive() {
        val intent = Intent(context, SettingsActivity::class.java)
            .putSettingsPage(SettingsPage(SettingsDestination.AppAppearance))
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_compact_dividers))
            .assertIsSelected()

        SettingsManager.setAppSettingsStyle(context, AppSettingsStyle.MATERIAL)
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.app_appearance_layout_style_material))
            .assertIsSelected()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [33], qualifiers = "w411dp-h1800dp")
    fun bothLayoutStylesRenderGerman150PercentWithoutClipping() {
        val german = Configuration(context.resources.configuration).apply {
            setLocale(Locale.GERMAN)
            fontScale = 1.5f
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(german, context.resources.displayMetrics)
        SettingsManager.setAppLanguageTag(context, "de")

        for (style in AppSettingsStyle.entries) {
            SettingsManager.setAppAppearancePreferences(
                context,
                AppAppearancePreferences(
                    themeMode = AppThemeMode.DARK,
                    darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
                    dynamicColor = false,
                    settingsStyle = style
                )
            )
            val intent = Intent(context, SettingsActivity::class.java)
                .putSettingsPage(SettingsPage(SettingsDestination.AppAppearance))
            val s = ActivityScenario.launch<SettingsActivity>(intent)
            try {
                compose.waitForIdle()
                val nodes = compose.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
                ).fetchSemanticsNodes()
                assertTrue("Expected laid-out text nodes for $style", nodes.isNotEmpty())
                nodes.forEach { node ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    layouts.forEach { layout ->
                        val cut = layout.didOverflowHeight || (0 until layout.lineCount).any(layout::isLineEllipsized)
                        assertFalse("Cut text in $style: ${layout.layoutInput.text}", cut)
                    }
                }
            } finally {
                s.close()
            }
        }
    }
}
