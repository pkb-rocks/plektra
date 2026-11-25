package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-rUS-w411dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AutoCorrectionSuggestionSettingsUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    private fun openAutoCorrectionSettings() {
        val intent = Intent(context, SettingsActivity::class.java)
            .putSettingsPage(SettingsPage(SettingsDestination.AutoCorrection))
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    private fun switchRow(titleRes: Int): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
            .and(hasText(context.getString(titleRes), substring = true))

    @Test
    fun bothTogglesAreIndependentPreserveUserDictionaryAndSyncExternalChanges() {
        val savedWordsJson = """[{"w":"ManualWord","f":5,"u":123}]"""
        SettingsManager.getPreferences(context).edit()
            .putString("user_dictionary_entries", savedWordsJson)
            .commit()

        openAutoCorrectionSettings()

        val personalizationRow = compose.onNode(switchRow(R.string.auto_correct_suggestions_personalization_title))
        val addWordButtonRow = compose.onNode(switchRow(R.string.auto_correct_add_word_button_title))

        personalizationRow.performScrollTo().assertIsEnabled().assertIsOn()
        addWordButtonRow.performScrollTo().assertIsEnabled().assertIsOn()

        // Toggle personalization OFF independently.
        personalizationRow.performClick()
        compose.waitForIdle()
        assertFalse(SettingsManager.getSuggestionsPersonalizationEnabled(context))
        assertTrue(SettingsManager.getSuggestionsAddWordButtonEnabled(context))
        personalizationRow.assertIsOff()
        addWordButtonRow.assertIsOn()
        assertEquals(
            savedWordsJson,
            SettingsManager.getPreferences(context).getString("user_dictionary_entries", null)
        )

        // Toggle add-word button OFF independently.
        addWordButtonRow.performClick()
        compose.waitForIdle()
        assertFalse(SettingsManager.getSuggestionsPersonalizationEnabled(context))
        assertFalse(SettingsManager.getSuggestionsAddWordButtonEnabled(context))
        addWordButtonRow.assertIsOff()

        // External preference change (e.g., backup restore) updates open UI.
        SettingsManager.setSuggestionsPersonalizationEnabled(context, true)
        compose.waitForIdle()
        personalizationRow.assertIsOn()
        addWordButtonRow.assertIsOff()

        // Disabling experimental suggestions disables both toggles without erasing their stored values.
        SettingsManager.setExperimentalSuggestionsEnabled(context, false)
        compose.waitForIdle()
        personalizationRow.assertIsNotEnabled()
        addWordButtonRow.assertIsNotEnabled()
        assertTrue(SettingsManager.getSuggestionsPersonalizationEnabled(context))
        assertFalse(SettingsManager.getSuggestionsAddWordButtonEnabled(context))
    }

    @Test
    fun settingsSearchFindsBothTogglesInEnglishAndGerman() {
        for (query in listOf("personalize", "Personalized suggestions", "empty field", "next word")) {
            val hits = SettingLinkRegistry.search(context, query)
            assertTrue(
                "English search for '$query' should find personalization setting",
                hits.any { it.id == SettingLinkIds.AUTO_CORRECTION_SUGGESTIONS_PERSONALIZATION }
            )
        }
        for (query in listOf("yellow plus", "Add word", "third suggestion")) {
            val hits = SettingLinkRegistry.search(context, query)
            assertTrue(
                "English search for '$query' should find add-word button setting",
                hits.any { it.id == SettingLinkIds.AUTO_CORRECTION_ADD_WORD_BUTTON }
            )
        }

        val germanContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.GERMAN) }
        )
        for (query in listOf("Personalisierung", "Personalisierte Vorschläge", "leeres Feld")) {
            val hits = SettingLinkRegistry.search(germanContext, query)
            assertTrue(
                "German search for '$query' should find personalization setting",
                hits.any { it.id == SettingLinkIds.AUTO_CORRECTION_SUGGESTIONS_PERSONALIZATION }
            )
        }
        for (query in listOf("gelbes Plus", "dritter Vorschlag", "Wort hinzufügen")) {
            val hits = SettingLinkRegistry.search(germanContext, query)
            assertTrue(
                "German search for '$query' should find add-word button setting",
                hits.any { it.id == SettingLinkIds.AUTO_CORRECTION_ADD_WORD_BUTTON }
            )
        }
    }
}
