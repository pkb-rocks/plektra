package rocks.pkb.plektra

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsManagerNumberSuggestionsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun offByDefaultLikeTheOtherOptInSuggestionTweaks() {
        assertFalse(SettingsManager.getNumberSuggestionsEnabled(context))
        assertEquals(emptySet<String>(), SettingsManager.getNumberSuggestionsExcludedPackages(context))
        assertFalse(SettingsManager.isNumberSuggestionsActive(context, editor(TEXT)))
    }

    @Test
    fun exclusionListRoundTripsIndependentlyOfOtherPerAppLists() {
        SettingsManager.setNumberSuggestionsExcludedApp(context, "com.termux", true)
        assertTrue(SettingsManager.isNumberSuggestionsExcludedApp(context, "com.termux"))
        assertFalse(SettingsManager.isNumberSuggestionsExcludedApp(context, "com.android.chrome"))
        assertFalse(SettingsManager.isNumberSuggestionsExcludedApp(context, null))
        assertFalse(SettingsManager.isRawModeApp(context, "com.termux"))
        assertFalse(SettingsManager.isKeyboardBarHiddenInApp(context, "com.termux"))

        SettingsManager.setNumberSuggestionsExcludedApp(context, "com.termux", false)
        assertFalse(SettingsManager.isNumberSuggestionsExcludedApp(context, "com.termux"))
    }

    @Test
    fun webApkExclusionAlsoCoversItsHostBrowser() {
        SettingsManager.setNumberSuggestionsExcludedApp(context, "org.chromium.webapk.x", true)
        assertTrue(SettingsManager.isNumberSuggestionsExcludedApp(context, "com.android.chrome"))
    }

    private data class Case(
        val label: String,
        val enabled: Boolean,
        val excluded: Boolean,
        val inputType: Int,
        val rawMode: Boolean = false,
        val expected: Boolean
    )

    @Test
    fun activationMatrix() {
        val cases = listOf(
            Case("on, plain text", true, false, TEXT, expected = true),
            Case("on, multi-line text", true, false, TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, expected = true),
            Case("off, plain text", false, false, TEXT, expected = false),
            Case("on, app excluded", true, true, TEXT, expected = false),
            Case("off, app excluded", false, true, TEXT, expected = false),
            Case("on, password", true, false, TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, expected = false),
            Case("on, visible password", true, false, TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, expected = false),
            Case("on, web password", true, false, TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, expected = false),
            Case("on, number password", true, false, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, expected = false),
            // The IME itself sets NO_SUGGESTIONS on every editable field before onStartInputView.
            Case("on, no-suggestions flag", true, false, TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, expected = true),
            Case(
                "on, WhatsApp message field after onStartInput",
                true,
                false,
                TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                expected = true
            ),
            Case("on, email", true, false, TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, expected = false),
            Case("on, exact typing app", true, false, TEXT, rawMode = true, expected = false)
        )
        val failures = cases.mapNotNull { case ->
            SettingsManager.getPreferences(context).edit().clear().commit()
            SettingsManager.setNumberSuggestionsEnabled(context, case.enabled)
            if (case.excluded) SettingsManager.setNumberSuggestionsExcludedApp(context, PACKAGE, true)
            if (case.rawMode) SettingsManager.setRawModeApp(context, PACKAGE, true)
            val actual = SettingsManager.isNumberSuggestionsActive(context, editor(case.inputType))
            if (actual != case.expected) "${case.label}: expected ${case.expected}, got $actual" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun noEditorMeansInactive() {
        SettingsManager.setNumberSuggestionsEnabled(context, true)
        assertFalse(SettingsManager.isNumberSuggestionsActive(context, null))
    }

    private fun editor(inputType: Int) = EditorInfo().apply {
        this.inputType = inputType
        packageName = PACKAGE
    }

    private companion object {
        const val PACKAGE = "com.example.notes"
        const val TEXT = InputType.TYPE_CLASS_TEXT
    }
}
