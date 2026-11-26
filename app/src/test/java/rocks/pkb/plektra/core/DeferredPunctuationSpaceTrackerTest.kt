package rocks.pkb.plektra.core

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import rocks.pkb.plektra.SettingsManager
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
class DeferredPunctuationSpaceTrackerTest {

    private lateinit var context: Context
    private lateinit var inputConnection: FakeInputConnection

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SettingsManager.setSpaceAfterPunctuation(context, "?!")
        DeferredPunctuationSpaceTracker.startField(null)
        inputConnection = FakeInputConnection(context)
    }

    @Test
    fun configuredPunctuationDefersSpaceUntilNextText() {
        commit("?")

        assertEquals("?", inputConnection.text)
        assertTrue(DeferredPunctuationSpaceTracker.isPending())

        commit("W")

        assertEquals("? W", inputConnection.text)
        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    @Test
    fun explicitSpaceConsumesPendingWithoutDuplication() {
        commit("!")
        commit(" ")

        assertEquals("! ", inputConnection.text)
        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    @Test
    fun punctuationSequenceKeepsSpaceDeferred() {
        commit("?")
        commit("!")
        commit("W")

        assertEquals("?! W", inputConnection.text)
    }

    @Test
    fun clearingBeforeSendLeavesNoTrailingSpace() {
        commit("!")

        DeferredPunctuationSpaceTracker.clear()

        assertEquals("!", inputConnection.text)
        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    @Test
    fun numbersTimesAndDecimalsStayTogether() {
        SettingsManager.setSpaceAfterPunctuation(context, ",:.")

        type("1,000 at 12:30 is 3.14")

        assertEquals("1,000 at 12:30 is 3.14", inputConnection.text)
    }

    @Test
    fun wordsAfterPunctuationStillGetTheirSpace() {
        SettingsManager.setSpaceAfterPunctuation(context, ",:.")

        type("Yes,no:5 items.Then 4,5")

        assertEquals("Yes, no: 5 items. Then 4,5", inputConnection.text)
    }

    @Test
    fun addressAndPasswordFieldsGetNoAutomaticSpaces() {
        SettingsManager.setSpaceAfterPunctuation(context, ".:,!?")
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )) {
            inputConnection = FakeInputConnection(context)
            DeferredPunctuationSpaceTracker.startField(field(InputType.TYPE_CLASS_TEXT or variation))

            type("me.you:pass!Word")

            assertEquals("variation $variation", "me.you:pass!Word", inputConnection.text)
        }
    }

    @Test
    fun aSingleLineSignInFieldIsKnownByItsHint() {
        SettingsManager.setSpaceAfterPunctuation(context, ".")
        DeferredPunctuationSpaceTracker.startField(field(InputType.TYPE_CLASS_TEXT, hint = "Email or username"))

        type("jane.doe")

        assertEquals("jane.doe", inputConnection.text)
    }

    @Test
    fun aNonAddressHintContainingAddressSubstringKeepsItsSpaces() {
        SettingsManager.setSpaceAfterPunctuation(context, ".")
        DeferredPunctuationSpaceTracker.startField(field(InputType.TYPE_CLASS_TEXT, hint = "Hourly notes"))

        type("Hi.Thanks")

        assertEquals("Hi. Thanks", inputConnection.text)
    }

    @Test
    fun aMessageBodyHintedLikeAnAddressKeepsItsSpaces() {
        SettingsManager.setSpaceAfterPunctuation(context, ".")
        DeferredPunctuationSpaceTracker.startField(
            field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, hint = "Compose email")
        )

        type("Hi.Thanks")

        assertEquals("Hi. Thanks", inputConnection.text)
    }

    @Test
    fun numberFieldsGetNoAutomaticSpacesAndTheNextTextFieldDoes() {
        SettingsManager.setSpaceAfterPunctuation(context, ".")
        DeferredPunctuationSpaceTracker.startField(field(InputType.TYPE_CLASS_NUMBER))
        type("3.x")
        assertEquals("3.x", inputConnection.text)

        inputConnection = FakeInputConnection(context)
        DeferredPunctuationSpaceTracker.startField(field(InputType.TYPE_CLASS_TEXT))
        type("Hi.Thanks")
        assertEquals("Hi. Thanks", inputConnection.text)
    }

    private fun field(inputType: Int, hint: String? = null) = EditorInfo().apply {
        this.inputType = inputType
        hintText = hint
    }

    private fun type(text: String) = text.forEach { commit(it.toString()) }

    private fun commit(text: String) {
        DeferredPunctuationSpaceTracker.prepareForTextCommit(context, inputConnection, text)
        inputConnection.commitText(text, 1)
    }

    private class FakeInputConnection(context: Context) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder()

        val text: String
            get() = buffer.toString()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            buffer.substring(maxOf(0, buffer.length - n))
    }
}
