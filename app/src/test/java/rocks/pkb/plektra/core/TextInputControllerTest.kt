package rocks.pkb.plektra.core

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.View
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.AutoCapitalizeHelper
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
class TextInputControllerTest {

    private lateinit var context: Context
    private lateinit var modifierStateController: ModifierStateController
    private lateinit var controller: TextInputController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        modifierStateController = ModifierStateController(500L)
        controller = TextInputController(
            context = context,
            modifierStateController = modifierStateController,
            doubleTapThreshold = 500L
        )
    }

    @Test
    fun doubleSpaceToPeriod_enabled_replacesVirtualKeyboardSecondSpace() {
        SettingsManager.setDoubleSpaceToPeriod(context, true)
        val inputConnection = FakeInputConnection(context, "hello")

        val firstHandled = controller.handleDoubleSpaceToPeriod(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableDoubleSpaceToPeriod = false,
            shouldDisableAutoCapitalize = true,
            onStatusBarUpdate = {}
        )
        if (!firstHandled) inputConnection.commitText(" ", 1)

        val secondHandled = controller.handleDoubleSpaceToPeriod(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableDoubleSpaceToPeriod = false,
            shouldDisableAutoCapitalize = true,
            onStatusBarUpdate = {}
        )

        assertFalse(firstHandled)
        assertTrue(secondHandled)
        assertEquals("hello. ", inputConnection.text)
    }

    @Test
    fun doubleSpaceToPeriod_addsNoFullStopWhereNothingWasTyped() {
        SettingsManager.setDoubleSpaceToPeriod(context, true)
        // The field's start, a new line, and only spaces before
        for (start in listOf("", "First line\n", "   ")) {
            val inputConnection = FakeInputConnection(context, start)

            assertFalse(typeTwoSpaces(inputConnection))
            assertEquals(start + "  ", inputConnection.text)
        }
    }

    @Test
    fun doubleSpaceToPeriod_addsNoFullStopAfterPunctuationThatContinuesTheSentence() {
        SettingsManager.setDoubleSpaceToPeriod(context, true)
        for (before in listOf("Hello,", "Note:", "see (", "red/")) {
            val inputConnection = FakeInputConnection(context, before)

            assertFalse(typeTwoSpaces(inputConnection))
            assertEquals(before + "  ", inputConnection.text)
        }
    }

    @Test
    fun doubleSpaceToPeriod_stillEndsTheSentenceAfterAWordOrClosingBracket() {
        SettingsManager.setDoubleSpaceToPeriod(context, true)
        for (before in listOf("hello", "(see above)", "42")) {
            val inputConnection = FakeInputConnection(context, before)

            assertTrue(typeTwoSpaces(inputConnection))
            assertEquals("$before. ", inputConnection.text)
        }
    }

    /** Two presses of Space, each committed as the IME does when the controller doesn't handle it. */
    private fun typeTwoSpaces(inputConnection: FakeInputConnection): Boolean {
        var handled = false
        repeat(2) {
            handled = controller.handleDoubleSpaceToPeriod(
                keyCode = KeyEvent.KEYCODE_SPACE,
                inputConnection = inputConnection,
                shouldDisableDoubleSpaceToPeriod = false,
                shouldDisableAutoCapitalize = true,
                onStatusBarUpdate = {}
            )
            if (!handled) inputConnection.commitText(" ", 1)
        }
        // A fresh controller state for the next sample
        controller = TextInputController(context, modifierStateController, 500L)
        return handled
    }

    @Test
    fun doubleSpaceToPeriod_settingDisabled_keepsVirtualKeyboardSpaces() {
        SettingsManager.setDoubleSpaceToPeriod(context, false)
        val inputConnection = FakeInputConnection(context, "hello")

        repeat(2) {
            val handled = controller.handleDoubleSpaceToPeriod(
                keyCode = KeyEvent.KEYCODE_SPACE,
                inputConnection = inputConnection,
                shouldDisableDoubleSpaceToPeriod = false,
                shouldDisableAutoCapitalize = true,
                onStatusBarUpdate = {}
            )
            if (!handled) inputConnection.commitText(" ", 1)
        }

        assertEquals("hello  ", inputConnection.text)
    }

    @Test
    fun doubleSpaceToPeriod_fieldDisabled_keepsVirtualKeyboardSpacesEvenWhenSettingEnabled() {
        SettingsManager.setDoubleSpaceToPeriod(context, true)
        val inputConnection = FakeInputConnection(context, "hello")

        repeat(2) {
            val handled = controller.handleDoubleSpaceToPeriod(
                keyCode = KeyEvent.KEYCODE_SPACE,
                inputConnection = inputConnection,
                shouldDisableDoubleSpaceToPeriod = true,
                shouldDisableAutoCapitalize = true,
                onStatusBarUpdate = {}
            )
            if (!handled) inputConnection.commitText(" ", 1)
        }

        assertEquals("hello  ", inputConnection.text)
    }

    @Test
    fun autoCapAfterEnter_requestsFreshShiftAfterNewline_whenEnabled() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        val inputConnection = FakeInputConnection(context, "hello\n")

        controller.handleAutoCapAfterEnter(
            keyCode = KeyEvent.KEYCODE_ENTER,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            onStatusBarUpdate = {}
        )

        assertTrue(modifierStateController.shiftOneShot)
    }

    @Test
    fun autoCapAfterEnter_keepsShiftOffAfterNewline_whenDisabled() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        val inputConnection = FakeInputConnection(context, "hello\n")

        controller.handleAutoCapAfterEnter(
            keyCode = KeyEvent.KEYCODE_ENTER,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            onStatusBarUpdate = {}
        )

        assertFalse(modifierStateController.shiftOneShot)
    }

    @Test
    fun spacedHyphenToEnDash_enabled_replacesMidSentenceHyphenWhenSpaceIsPressed() {
        SettingsManager.setSpacedHyphenToEnDash(context, true)
        val inputConnection = FakeInputConnection(context, "hello -")

        val handled = controller.handleSpacedHyphenToEnDash(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("hello – ", inputConnection.text)
    }

    @Test
    fun spacedHyphenToEnDash_enabled_canUseEmDash() {
        SettingsManager.setSpacedHyphenToEnDash(context, true)
        SettingsManager.setSpacedHyphenDashStyle(context, SettingsManager.DASH_STYLE_EM)
        val inputConnection = FakeInputConnection(context, "hello -")

        val handled = controller.handleSpacedHyphenToEnDash(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("hello — ", inputConnection.text)
    }

    @Test
    fun spacedHyphenToEnDash_enabled_doesNotReplaceLineStartHyphen() {
        SettingsManager.setSpacedHyphenToEnDash(context, true)
        val inputConnection = FakeInputConnection(context, "  -")

        val handled = controller.handleSpacedHyphenToEnDash(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText(" ", 1)

        assertFalse(handled)
        assertEquals("  - ", inputConnection.text)
    }

    @Test
    fun spacedHyphenToEnDash_settingDisabled_keepsSpacedHyphen() {
        SettingsManager.setSpacedHyphenToEnDash(context, false)
        val inputConnection = FakeInputConnection(context, "hello -")

        val handled = controller.handleSpacedHyphenToEnDash(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText(" ", 1)

        assertFalse(handled)
        assertEquals("hello - ", inputConnection.text)
    }

    @Test
    fun spacedHyphenToEnDash_fieldDisabled_keepsSpacedHyphenEvenWhenSettingEnabled() {
        SettingsManager.setSpacedHyphenToEnDash(context, true)
        val inputConnection = FakeInputConnection(context, "hello -")

        val handled = controller.handleSpacedHyphenToEnDash(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = true
        )
        if (!handled) inputConnection.commitText(" ", 1)

        assertFalse(handled)
        assertEquals("hello - ", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_enabled_waitsForFollowingLetter() {
        SettingsManager.setMidWordQuoteToApostrophe(context, true)
        val inputConnection = FakeInputConnection(context, "qu")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = "\"",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText("\"", 1)

        assertFalse(handled)
        assertEquals("qu\"", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_enabled_replacesPendingQuoteBeforeLetter() {
        SettingsManager.setMidWordQuoteToApostrophe(context, true)
        val inputConnection = FakeInputConnection(context, "qu\"")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = "o",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("qu'o", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_enabled_keepsOpeningQuote() {
        SettingsManager.setMidWordQuoteToApostrophe(context, true)
        val inputConnection = FakeInputConnection(context, "\"")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = "w",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText("w", 1)

        assertFalse(handled)
        assertEquals("\"w", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_enabled_keepsQuoteBeforePunctuation() {
        SettingsManager.setMidWordQuoteToApostrophe(context, true)
        val inputConnection = FakeInputConnection(context, "qu\"")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = ",",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText(",", 1)

        assertFalse(handled)
        assertEquals("qu\",", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_enabled_supportsUnicodeLetters() {
        SettingsManager.setMidWordQuoteToApostrophe(context, true)
        val inputConnection = FakeInputConnection(context, "dé\"")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = "à",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("dé'à", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_settingDisabled_keepsQuote() {
        SettingsManager.setMidWordQuoteToApostrophe(context, false)
        val inputConnection = FakeInputConnection(context, "qu\"")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = "o",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText("o", 1)

        assertFalse(handled)
        assertEquals("qu\"o", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_fieldDisabled_keepsQuoteEvenWhenSettingEnabled() {
        SettingsManager.setMidWordQuoteToApostrophe(context, true)
        val inputConnection = FakeInputConnection(context, "qu\"")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = "o",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = true
        )
        if (!handled) inputConnection.commitText("o", 1)

        assertFalse(handled)
        assertEquals("qu\"o", inputConnection.text)
    }

    @Test
    fun midWordQuoteToApostrophe_enabled_replacesQuoteBeforeLetterWhenEditing() {
        SettingsManager.setMidWordQuoteToApostrophe(context, true)
        val inputConnection = FakeInputConnection(context, "qu\"", "n")

        val handled = controller.handlePendingMidWordQuoteToApostrophe(
            typedText = "o",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("qu'on", inputConnection.text)
    }

    @Test
    fun smartQuotes_enabled_commitsConfiguredOpeningAndClosingQuotes() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS)
        val inputConnection = FakeInputConnection(context, "\"Hallo\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = " ",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("»Hallo« ", inputConnection.text)
    }

    @Test
    fun smartQuotes_enabled_supportsAllConfiguredStyles() {
        val styles = listOf(
            SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS to "»Hallo« ",
            SettingsManager.SMART_QUOTES_STYLE_FRENCH_GUILLEMETS to "«Hallo» ",
            SettingsManager.SMART_QUOTES_STYLE_FRENCH_GUILLEMETS_NARROW_SPACED to "« Hallo » ",
            SettingsManager.SMART_QUOTES_STYLE_GERMAN_LOW_HIGH to "„Hallo“ ",
            SettingsManager.SMART_QUOTES_STYLE_ENGLISH_CURLY to "“Hallo” "
        )

        SettingsManager.setSmartQuotes(context, true)
        styles.forEach { (style, expected) ->
            SettingsManager.setSmartQuotesStyle(context, style)
            val inputConnection = FakeInputConnection(context, "\"Hallo\"")

            val handled = controller.handleSmartQuoteReplacement(
                typedText = " ",
                inputConnection = inputConnection,
                shouldDisableSmartPunctuation = false
            )

            assertTrue("Expected style $style to be handled", handled)
            assertEquals(expected, inputConnection.text)
        }
    }

    @Test
    fun smartQuotes_enabled_replacesBeforeHyphenAfterClosingQuote() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS)
        val inputConnection = FakeInputConnection(context, "Sogenannter \"Hooligang\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = "-",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("Sogenannter »Hooligang«-", inputConnection.text)
    }

    @Test
    fun smartQuotes_enabled_supportsMultiWordQuotes() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS)
        val inputConnection = FakeInputConnection(context, "Er sagte \"Man kann das testen\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = " ",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("Er sagte »Man kann das testen« ", inputConnection.text)
    }

    @Test
    fun smartQuotes_enabled_ignoresEmbeddedQuotesInsideWords() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS)
        val inputConnection = FakeInputConnection(context, "foo\"bar\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = " ",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText(" ", 1)

        assertFalse(handled)
        assertEquals("foo\"bar\" ", inputConnection.text)
    }

    @Test
    fun smartQuotes_enabled_waitsForDelimiterAfterClosingQuote() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS)
        val inputConnection = FakeInputConnection(context, "\"Hallo\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = "a",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText("a", 1)

        assertFalse(handled)
        assertEquals("\"Hallo\"a", inputConnection.text)
    }

    @Test
    fun smartQuotes_enabled_supportsNarrowSpacedGuillemets() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_FRENCH_GUILLEMETS_NARROW_SPACED)
        val inputConnection = FakeInputConnection(context, "\"Bonjour\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = " ",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )

        assertTrue(handled)
        assertEquals("« Bonjour » ", inputConnection.text)
    }

    @Test
    fun smartQuotes_enabled_waitsForFollowingCharacterAfterClosingQuote() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS)
        val inputConnection = FakeInputConnection(context, "\"Hallo")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = "\"",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText("\"", 1)

        assertFalse(handled)
        assertEquals("\"Hallo\"", inputConnection.text)
    }

    @Test
    fun smartQuotes_settingDisabled_keepsPlainQuote() {
        SettingsManager.setSmartQuotes(context, false)
        val inputConnection = FakeInputConnection(context, "\"Hallo\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = " ",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = false
        )
        if (!handled) inputConnection.commitText(" ", 1)

        assertFalse(handled)
        assertEquals("\"Hallo\" ", inputConnection.text)
    }

    @Test
    fun smartQuotes_fieldDisabled_keepsPlainQuoteEvenWhenSettingEnabled() {
        SettingsManager.setSmartQuotes(context, true)
        SettingsManager.setSmartQuotesStyle(context, SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS)
        val inputConnection = FakeInputConnection(context, "\"Hallo\"")

        val handled = controller.handleSmartQuoteReplacement(
            typedText = " ",
            inputConnection = inputConnection,
            shouldDisableSmartPunctuation = true
        )
        if (!handled) inputConnection.commitText(" ", 1)

        assertFalse(handled)
        assertEquals("\"Hallo\" ", inputConnection.text)
    }

    @Test
    fun autoCapAfterPeriod_armsShiftOnSpaceKeyDownBeforeTheSpaceIsCommitted() {
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        val inputConnection = FakeInputConnection(context, "Hi.")

        // The app inserts the Space after this key-down check; the next key can arrive first.
        controller.handleAutoCapAfterPeriod(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            onStatusBarUpdate = {}
        )

        assertTrue(modifierStateController.shiftOneShot)
        assertEquals("Hi.", inputConnection.text)
    }

    @Test
    fun autoCapAfterPeriod_spaceAfterWordOrDisabledSetting_leavesShiftOff() {
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        controller.handleAutoCapAfterPeriod(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = FakeInputConnection(context, "Hi"),
            shouldDisableAutoCapitalize = false,
            onStatusBarUpdate = {}
        )
        assertFalse(modifierStateController.shiftOneShot)

        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        controller.handleAutoCapAfterPeriod(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = FakeInputConnection(context, "Hi."),
            shouldDisableAutoCapitalize = false,
            onStatusBarUpdate = {}
        )
        assertFalse(modifierStateController.shiftOneShot)
    }

    @Test
    fun autoCapAfterEnter_multiLineField_armsShiftBeforeTheNewlineIsCommitted() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        val inputConnection = FakeInputConnection(context, "Ok so")

        controller.handleAutoCapAfterEnter(
            keyCode = KeyEvent.KEYCODE_ENTER,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            insertsNewline = true,
            onStatusBarUpdate = {}
        )

        assertTrue(modifierStateController.shiftOneShot)
        assertEquals("Ok so", inputConnection.text)
    }

    @Test
    fun autoCapAfterEnter_spaceAtStartOfLine_clearsTheArmedShift() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        val inputConnection = FakeInputConnection(context, "Ok so")
        controller.handleAutoCapAfterEnter(
            keyCode = KeyEvent.KEYCODE_ENTER,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            insertsNewline = true,
            onStatusBarUpdate = {}
        )
        inputConnection.commitText("\n", 1)
        assertTrue(modifierStateController.shiftOneShot)

        // The selection update after the Space would clear it too; a fast next key must not see it.
        controller.handleAutoCapAfterPeriod(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            onStatusBarUpdate = {}
        )

        assertFalse(modifierStateController.shiftOneShot)
    }

    @Test
    fun autoCapAfterEnter_singleLineField_doesNotAssumeANewline() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)

        controller.handleAutoCapAfterEnter(
            keyCode = KeyEvent.KEYCODE_ENTER,
            inputConnection = FakeInputConnection(context, "query"),
            shouldDisableAutoCapitalize = false,
            insertsNewline = false,
            onStatusBarUpdate = {}
        )

        assertFalse(modifierStateController.shiftOneShot)
    }

    @Test
    fun manualShift_withoutPriorAutoCap_survivesSpaceAfterWord() {
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        val inputConnection = FakeInputConnection(context, "Hi")
        tapShift()
        assertTrue(modifierStateController.shiftOneShot)

        pressSpace(inputConnection)

        assertTrue(modifierStateController.shiftOneShot)
        assertFalse(modifierStateController.shiftOneShotAutomatic)
    }

    @Test
    fun manualShift_afterConsumedAutoCap_survivesSpaceAfterWord() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        val inputConnection = FakeInputConnection(context, "Ok")
        enterNewline(inputConnection)
        assertTrue(modifierStateController.shiftOneShotAutomatic)
        // A letter consumes the automatic one-shot; the selection update that would
        // re-evaluate auto-cap has not arrived yet.
        typeLetter(inputConnection, "A")
        assertFalse(modifierStateController.shiftOneShot)

        tapShift()
        assertTrue(modifierStateController.shiftOneShot)
        assertFalse(modifierStateController.shiftOneShotAutomatic)
        pressSpace(inputConnection)

        assertTrue(modifierStateController.shiftOneShot)
    }

    @Test
    fun manualShift_takingOverAnArmedAutoCap_survivesSpaceAtStartOfLine() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        val inputConnection = FakeInputConnection(context, "Ok")
        enterNewline(inputConnection)
        assertTrue(modifierStateController.shiftOneShotAutomatic)

        // First tap cancels the automatic one-shot, a later tap sets a manual one.
        tapShift()
        assertFalse(modifierStateController.shiftOneShot)
        modifierStateController.registerNonModifierKey() // slower than a double tap
        tapShift()
        assertTrue(modifierStateController.shiftOneShot)
        assertFalse(modifierStateController.shiftOneShotAutomatic)
        pressSpace(inputConnection)

        assertTrue(modifierStateController.shiftOneShot)
    }

    @Test
    fun capsLock_survivesEnterThenSpace() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        val inputConnection = FakeInputConnection(context, "OK.")
        modifierStateController.capsLockEnabled = true

        enterNewline(inputConnection)
        pressSpace(inputConnection)
        typeLetter(inputConnection, "A")
        pressSpace(inputConnection)

        assertEquals(ShiftState.CAPS, modifierStateController.shiftState)
    }

    @Test
    fun selectionUpdateAfterConsumedAutoCap_keepsManualShift() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        val inputConnection = FakeInputConnection(context, "Ok")
        enterNewline(inputConnection)
        typeLetter(inputConnection, "A")
        tapShift()

        // The delayed selection update for the letter arrives after the manual Shift.
        AutoCapitalizeHelper.checkAutoCapitalizeOnSelectionChange(
            context, inputConnection, false, 4, 4, 4, 4,
            enableShift = { modifierStateController.requestShiftOneShotFromAutoCap() },
            disableShift = { modifierStateController.clearAutomaticShiftOneShot() },
            onUpdateStatusBar = {}
        )

        assertTrue(modifierStateController.shiftOneShot)
        assertFalse(modifierStateController.shiftOneShotAutomatic)
    }

    @Test
    fun fieldReset_dropsAutomaticOrigin() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        val inputConnection = FakeInputConnection(context, "Ok")
        enterNewline(inputConnection)
        assertTrue(modifierStateController.shiftOneShotAutomatic)

        modifierStateController.resetModifiers(preserveNavMode = false, onNavModeCancelled = {})
        assertFalse(modifierStateController.shiftOneShotAutomatic)
        tapShift()
        pressSpace(FakeInputConnection(context, "Ab"))

        assertTrue(modifierStateController.shiftOneShot)
        assertFalse(modifierStateController.shiftOneShotAutomatic)
    }

    private fun tapShift() {
        modifierStateController.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT)
        modifierStateController.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT)
    }

    private fun typeLetter(inputConnection: FakeInputConnection, letter: String) {
        modifierStateController.registerNonModifierKey()
        modifierStateController.consumeShiftOneShot()
        inputConnection.commitText(letter, 1)
    }

    private fun enterNewline(inputConnection: FakeInputConnection) {
        modifierStateController.registerNonModifierKey()
        controller.handleAutoCapAfterEnter(
            keyCode = KeyEvent.KEYCODE_ENTER,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            insertsNewline = true,
            onStatusBarUpdate = {}
        )
        inputConnection.commitText("\n", 1)
    }

    private fun pressSpace(inputConnection: FakeInputConnection) {
        modifierStateController.registerNonModifierKey()
        controller.handleAutoCapAfterPeriod(
            keyCode = KeyEvent.KEYCODE_SPACE,
            inputConnection = inputConnection,
            shouldDisableAutoCapitalize = false,
            onStatusBarUpdate = {}
        )
        inputConnection.commitText(" ", 1)
    }

    private class FakeInputConnection(
        context: Context,
        initialText: String,
        initialAfterCursor: String = ""
    ) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder(initialText)
        private val afterCursor = StringBuilder(initialAfterCursor)

        val text: String
            get() = buffer.toString() + afterCursor.toString()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            return buffer.takeLast(n)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            return afterCursor.take(n)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val deleteStart = (buffer.length - beforeLength).coerceAtLeast(0)
            buffer.delete(deleteStart, buffer.length)
            if (afterLength > 0) {
                afterCursor.delete(0, afterLength.coerceAtMost(afterCursor.length))
            }
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }
    }
}
