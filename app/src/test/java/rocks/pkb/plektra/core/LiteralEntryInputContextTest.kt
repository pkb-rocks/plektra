package rocks.pkb.plektra.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteralEntryInputContextTest {
    private fun editor(options: String? = InputContextState.LITERAL_ENTRY_IME_OPTION) = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        privateImeOptions = options
    }

    @Test fun literalTextPreservesSpellingButKeepsAccentedVariantsAvailable() {
        val state = InputContextState.fromEditorInfo(editor())
        assertTrue(state.isReallyEditable)
        assertEquals(InputContextState.RestrictedReason.LITERAL_ENTRY, state.restrictedReason)
        assertTrue(state.shouldDisableSuggestions)
        assertTrue(state.shouldDisableAutoCorrect)
        assertTrue(state.shouldDisableAutoCapitalize)
        assertTrue(state.shouldDisableDoubleSpaceToPeriod)
        assertFalse(state.shouldDisableVariations)
        assertFalse(state.isPasswordField)
    }

    @Test fun movingBackToOrdinaryTextDoesNotRetainLiteralPolicy() {
        InputContextState.fromEditorInfo(editor())
        val ordinary = InputContextState.fromEditorInfo(editor(null))
        assertNull(ordinary.restrictedReason)
        assertFalse(ordinary.shouldDisableSuggestions)
        assertFalse(ordinary.shouldDisableAutoCorrect)
        assertFalse(ordinary.shouldDisableAutoCapitalize)
        assertFalse(ordinary.shouldDisableDoubleSpaceToPeriod)
    }

    @Test fun optionIsAnExactCommaSeparatedTokenNotAPrefixMatch() {
        val token = InputContextState.LITERAL_ENTRY_IME_OPTION
        assertEquals(InputContextState.RestrictedReason.LITERAL_ENTRY,
            InputContextState.fromEditorInfo(editor("other.option, $token ,third.option")).restrictedReason)
        for (options in listOf("", "other.option", "$token.extra", "prefix.$token")) {
            assertNull(options, InputContextState.fromEditorInfo(editor(options)).restrictedReason)
        }
    }

    @Test fun literalMarkerDoesNotMaskPasswordOrEmailRestrictions() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val state = InputContextState.fromEditorInfo(editor().apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation
            })
            assertTrue(state.isPasswordField)
        }
        val email = InputContextState.fromEditorInfo(editor().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        })
        assertTrue(email.isEmailField)
        assertTrue(email.shouldDisableVariations)
    }

    @Test fun nonTextAndMissingEditorsDoNotBecomeLiteralTextFields() {
        for (type in listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            val state = InputContextState.fromEditorInfo(editor().apply { inputType = type })
            assertNotEquals(InputContextState.RestrictedReason.LITERAL_ENTRY, state.restrictedReason)
        }
        assertEquals(InputContextState.EMPTY, InputContextState.fromEditorInfo(null))
    }
}
