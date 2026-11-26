package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SettingsTwoPaneTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setup() { SettingsManager.getPreferences(context).edit().clear().commit() }
    @After fun tearDown() { scenario?.close() }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, SettingsActivity::class.java))
        compose.waitForIdle()
    }

    private val back get() = context.getString(R.string.settings_back_content_description)

    @Test
    @Config(sdk = [33], qualifiers = "w720dp-h800dp")
    fun aWideWindowShowsTheListAndTheSelectedCategoryAsDetailRoot() {
        launch()
        // List and detail are visible together; only the list has Back.
        compose.onNodeWithText(context.getString(R.string.settings_group_typing)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.keyboards_devices_title))[1].assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithContentDescription(back).fetchSemanticsNodes().size)

        compose.onNodeWithText(context.getString(R.string.settings_category_auto_correction)).performClick()
        compose.waitForIdle()
        scenario!!.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        compose.onNodeWithText(context.getString(R.string.auto_correct_section_suggestions)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.settings_category_auto_correction))[0].assertIsSelected()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w720dp-h800dp")
    fun foldingAndRecreatingKeepsTheSelectedCategoryAndItsBackDestination() {
        launch()
        val category = context.getString(R.string.settings_category_auto_correction)
        val detail = context.getString(R.string.auto_correct_section_suggestions)
        compose.onNodeWithText(category).performClick()
        org.robolectric.RuntimeEnvironment.setQualifiers("w411dp-h800dp")
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(detail).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(back).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_group_typing)).assertIsDisplayed()
        org.robolectric.RuntimeEnvironment.setQualifiers("w720dp-h800dp")
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onAllNodesWithText(category)[0].assertIsSelected()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h800dp")
    fun aNarrowWindowOpensTheCategoryInPlaceWithBackToTheList() {
        launch()
        compose.onNodeWithText(context.getString(R.string.settings_category_auto_correction)).performClick()
        compose.waitForIdle()
        scenario!!.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        compose.onNodeWithText(context.getString(R.string.auto_correct_section_suggestions)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription(back).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.settings_group_typing)).assertIsDisplayed()
    }
}
