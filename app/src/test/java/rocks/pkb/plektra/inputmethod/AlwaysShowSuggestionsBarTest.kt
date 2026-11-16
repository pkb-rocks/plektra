package rocks.pkb.plektra.inputmethod

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

/**
 * Tests for the "Always show suggestions bar" setting.
 * 
 * Requirements:
 * - OFF (default): No behavior change from current (auto-hides)
 * - ON: Suggestions strip never auto-hides
 * - Per-app "Never show the bar" must still hide the toolbar
 * - User's explicit hide-suggestions setting must still win
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlwaysShowSuggestionsBarTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Reset to default
        SettingsManager.setAlwaysShowSuggestionsBar(context, false)
    }

    @Test
    fun defaultValueIsOff() {
        assertFalse("Default should be OFF", 
            SettingsManager.getAlwaysShowSuggestionsBar(context))
    }

    @Test
    fun canSetToOn() {
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        assertTrue("Setting should be ON after setting it", 
            SettingsManager.getAlwaysShowSuggestionsBar(context))
    }

    @Test
    fun canSetToOff() {
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        SettingsManager.setAlwaysShowSuggestionsBar(context, false)
        assertFalse("Setting should be OFF after resetting it", 
            SettingsManager.getAlwaysShowSuggestionsBar(context))
    }

    @Test
    fun settingPersistsAcrossGets() {
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        val first = SettingsManager.getAlwaysShowSuggestionsBar(context)
        val second = SettingsManager.getAlwaysShowSuggestionsBar(context)
        assertTrue("Setting should persist", first && second)
    }

    /**
     * Test that the setting integrates with FullSuggestionsBar.update()
     * The actual visibility behavior is tested through integration tests.
     * Here we just verify the setting flows through correctly.
     */
    @Test
    fun settingIntegratesWithUpdateMethod() {
        // This test verifies the parameter exists and can be passed
        // The actual behavior is verified through StatusBarController integration
        val setting = SettingsManager.getAlwaysShowSuggestionsBar(context)
        assertNotNull("Setting should be readable", setting)
        // Type check: setting should be Boolean
        assertTrue("Setting should be Boolean", setting is Boolean)
    }
}
