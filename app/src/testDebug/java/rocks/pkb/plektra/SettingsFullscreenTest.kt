package rocks.pkb.plektra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.PlektraTheme

/** Full-screen settings: status bar, header search and Back in real settings windows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h800dp")
class SettingsFullscreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<out Activity>? = null

    @Before fun setup() { SettingsManager.getPreferences(context).edit().clear().commit() }
    @After fun tearDown() { scenario?.close() }

    private val search get() = context.getString(R.string.settings_search_hint)
    private val closeSearch get() = context.getString(R.string.settings_search_close)
    private val appearanceTitle get() = context.getString(R.string.app_appearance_title)

    private fun fullscreen(on: Boolean) = SettingsManager.setSettingsFullscreenEnabled(context, on)

    private fun launchPage(page: SettingsPage) {
        scenario = ActivityScenario.launch<SettingsActivity>(
            Intent(context, SettingsActivity::class.java).putSettingsPage(page)
        )
        compose.waitForIdle()
    }

    private fun statusBarHidden(): Boolean {
        var hidden = false
        scenario!!.onActivity {
            @Suppress("DEPRECATION")
            hidden = it.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
        }
        return hidden
    }

    private fun ComposeTestRule.displayed(text: String): SemanticsNodeInteraction {
        val nodes = onAllNodes(hasText(text))
        val count = nodes.fetchSemanticsNodes().size
        return (0 until count).map { nodes[it] }.first { it.isDisplayed() }
    }

    /** A row in the search results pane, which lies over the page's own rows. */
    private fun result(text: String) = compose.onNode(
        hasText(text) and hasClickAction() and
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, search))
    )

    private fun scrollValue(): Float =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes().first().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun offByDefaultKeepsTheStatusBarAndOffersNoHeaderSearch() {
        launchPage(SettingsPage(SettingsDestination.AppAppearance))
        assertFalse(statusBarHidden())
        assertEquals(0, compose.onAllNodesWithContentDescription(search).fetchSemanticsNodes().size)
        assertFalse(SettingsManager.getAppAppearancePreferences(context).settingsFullscreen)
    }

    @Test
    fun theAppearanceSwitchHidesAndRestoresTheStatusBarLive() {
        launchPage(SettingsPage(SettingsDestination.AppAppearance))
        val title = context.getString(R.string.app_appearance_settings_fullscreen_title)
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(SettingsManager.getAppAppearancePreferences(context).settingsFullscreen)
        assertTrue(statusBarHidden())
        compose.onNodeWithContentDescription(search).assertIsDisplayed()

        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(statusBarHidden())
        assertEquals(0, compose.onAllNodesWithContentDescription(search).fetchSemanticsNodes().size)
    }

    @Test
    fun hostActivitiesHideItButOtherWindowsKeepTheirStatusBar() {
        fullscreen(true)
        scenario = ActivityScenario.launch(LanguagesActivity::class.java)
        compose.waitForIdle()
        assertTrue(statusBarHidden())
        scenario!!.close()

        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { (it as ComponentActivity).setContent { PlektraTheme { } } }
        compose.waitForIdle()
        assertFalse(statusBarHidden())
    }

    @Test
    fun searchTakesTheTitlesPlaceAndClosingRestoresTitleFocusAndScroll() = searchRoundTrip(AppSettingsStyle.COMPACT_DIVIDERS)

    @Test
    fun searchWorksTheSameInTheMaterialStyle() = searchRoundTrip(AppSettingsStyle.MATERIAL)

    private fun searchRoundTrip(style: AppSettingsStyle) {
        fullscreen(true)
        SettingsManager.setAppSettingsStyle(context, style)
        launchPage(SettingsPage(SettingsDestination.AppAppearance))
        compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        compose.waitForIdle()
        val scrolled = scrollValue()
        assertTrue(scrolled > 0f)
        val backBounds = compose.onNodeWithContentDescription(context.getString(R.string.settings_back_content_description))
            .getBoundsInRoot()

        compose.onNodeWithContentDescription(search).performClick()
        compose.waitForIdle()
        // Same row: the close button takes Back's place, the field takes the title's.
        assertEquals(backBounds, compose.onNodeWithContentDescription(closeSearch).getBoundsInRoot())
        assertTrue(compose.onAllNodes(hasText(appearanceTitle)).fetchSemanticsNodes().none {
            it.config.contains(SemanticsProperties.Heading)
        })

        // Back closes the search first and stays on the page.
        scenario!!.onActivity { (it as ComponentActivity).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        scenario!!.onActivity { assertFalse(it.isFinishing) }
        compose.displayed(appearanceTitle).assertIsDisplayed()
        compose.onNodeWithContentDescription(search).assertIsFocused()
        assertEquals(scrolled, scrollValue(), 0.5f)
    }

    @Test
    @Config(sdk = [33])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    fun globalSearchKeepsTheLocalDictionaryQueryOutOfItsFocusOrder() {
        fullscreen(true)
        scenario = ActivityScenario.launch<SettingsActivity>(
            Intent(context, SettingsActivity::class.java)
                .putSettingsPage(SettingsPage(SettingsDestination.AutoCorrection))
                .putExtra("settings.child.autocorrection", "UserDictionary")
        )
        compose.onNodeWithContentDescription(context.getString(R.string.auto_correct_search_description)).performClick()
        val editable = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        val field = compose.onNode(editable)
        field.performTouchInput { click() }
        field.performTextInput("localword")
        val localMatch = editable and hasText("localword")
        val originalBounds = compose.onNode(localMatch).fetchSemanticsNode().boundsInRoot
        val trigger = compose.onNodeWithContentDescription(search)
        trigger.performClick()
        field.assertIsFocused()
        val oldPosition = originalBounds.center - field.fetchSemanticsNode().boundsInRoot.topLeft
        field.performTouchInput { click(oldPosition) }
        fun assertLocalFieldUnfocused() {
            val localFields = compose.onAllNodes(localMatch, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(localFields.none { it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused] })
        }
        assertLocalFieldUnfocused()
        field.performTouchInput { click() }
        repeat(4) {
            field.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
            assertLocalFieldUnfocused()
        }
        field.performTouchInput { click() }
        field.assertIsFocused().performTextInput("globalword")
        compose.onNode(editable and hasText("globalword")).assertIsDisplayed()
        compose.onNodeWithContentDescription(closeSearch).performClick()
        compose.waitForIdle()
        trigger.assertIsFocused()
        assertEquals(originalBounds, compose.onNode(localMatch).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun aResultOnTheSamePageHighlightsInPlaceAndOthersUseTheSettingLink() {
        fullscreen(true)
        launchPage(SettingsPage(SettingsDestination.AppAppearance))
        val sameTitle = context.getString(R.string.app_appearance_settings_fullscreen_title)
        compose.onNodeWithContentDescription(search).performClick()
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextInput(sameTitle)
        compose.waitForIdle()
        result(sameTitle).performClick()
        compose.waitForIdle()
        scenario!!.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        compose.displayed(appearanceTitle).assertIsDisplayed()

        val other = SettingLinkRegistry.byId(SettingLinkIds.TEXT_INPUT_AUTO_CAPITALIZE)!!
        compose.onNodeWithContentDescription(search).performClick()
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextInput(context.getString(other.titleRes))
        compose.waitForIdle()
        result(context.getString(other.titleRes)).performClick()
        compose.waitForIdle()
        scenario!!.onActivity {
            val started = shadowOf(it).nextStartedActivity
            assertEquals(SettingsActivity::class.java.name, started.component?.className)
            assertEquals("plektra://setting/${SettingLinkRegistry.visibleTarget(context, other).id}", started.dataString)
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h800dp")
    fun theFoldDetailRootLeavesSearchToTheListBesideIt() {
        fullscreen(true)
        scenario = ActivityScenario.launch(Intent(context, SettingsActivity::class.java))
        compose.waitForIdle()
        assertTrue(statusBarHidden())
        compose.onNodeWithText(search).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription(search).fetchSemanticsNodes().size)
    }
}

/** Header geometry around synthetic display cutouts (window px), compact and wide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h800dp-xhdpi")
class SettingsHeaderCutoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val back get() = context.getString(R.string.settings_back_content_description)
    private val action = "Refresh"

    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSettingsFullscreenEnabled(context, true)
    }

    private var cutouts by mutableStateOf<List<Rect>>(emptyList())

    private fun show(subtitle: String? = null) {
        compose.setContent {
            PlektraTheme(settingsWindow = true) {
                CompositionLocalProvider(LocalDisplayCutoutRects provides cutouts) {
                    PlektraSettingsHeader(title = "Kill Switch", subtitle = subtitle, onBack = {}, actions = {
                        IconButton(onClick = {}) { Icon(Icons.Filled.Refresh, contentDescription = action) }
                    })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(description: String) = compose.onNodeWithContentDescription(description).getBoundsInRoot()
    private fun titleBounds() = compose.onNodeWithText("Kill Switch").getBoundsInRoot()
    private fun px(dp: Dp) = (dp.value * 2).toInt()

    @Test
    fun aCutoutBelowTheMinimumHeightClearsTheBackButtonInATallHeader() {
        show(subtitle = "First line\nSecond line\nThird line\nFourth line")
        cutouts = listOf(Rect(0, px(60.dp), px(65.dp), px(90.dp)))
        compose.waitForIdle()
        assertTrue(bounds(back).left >= 65.dp)
    }

    @Test
    fun onlyTheElementsMeetingACutoutMove() {
        show()
        val plainBack = bounds(back)
        val plainTitle = titleBounds()
        val plainAction = bounds(action)

        // A corner cutout over Back: Back and the title shift, the action keeps its place.
        cutouts = listOf(Rect(0, 0, px(60.dp), px(40.dp)))
        compose.waitForIdle()
        assertTrue(bounds(back).left >= 60.dp)
        assertEquals(plainBack.top, bounds(back).top)
        assertEquals(plainAction, bounds(action))
        assertNotEquals(plainTitle.left, titleBounds().left)

        // A centred hole: the short title fits before it, nothing moves.
        cutouts = listOf(Rect(px(220.dp), px(8.dp), px(252.dp), px(40.dp)))
        compose.waitForIdle()
        assertEquals(plainBack, bounds(back))
        assertEquals(plainTitle, titleBounds())
        assertEquals(plainAction, bounds(action))

        // A cutout at the end (rotated device): only the action moves inward.
        cutouts = listOf(Rect(px(370.dp), 0, px(411.dp), px(48.dp)))
        compose.waitForIdle()
        assertTrue(bounds(action).right <= 370.dp)
        assertEquals(plainBack, bounds(back))
        assertEquals(plainTitle, titleBounds())

        // A cutout below the header row, e.g. on a long edge in landscape, changes nothing.
        cutouts = listOf(Rect(0, px(300.dp), px(40.dp), px(360.dp)))
        compose.waitForIdle()
        assertEquals(plainBack, bounds(back))
        assertEquals(plainAction, bounds(action))
    }

    @Test
    @Config(qualifiers = "w840dp-h600dp-xhdpi")
    fun aWideWindowAvoidsACutoutAtTheEndAsWell() {
        show()
        val plainBack = bounds(back)
        cutouts = listOf(Rect(px(800.dp), 0, px(840.dp), px(40.dp)))
        compose.waitForIdle()
        assertTrue(bounds(action).right <= 800.dp)
        assertEquals(plainBack, bounds(back))
    }
}
