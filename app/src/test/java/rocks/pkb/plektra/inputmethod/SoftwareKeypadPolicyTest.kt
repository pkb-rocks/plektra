package rocks.pkb.plektra.inputmethod

import android.text.InputType
import rocks.pkb.plektra.SymPagesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareKeypadPolicyTest {
    private val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    private val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
        InputType.TYPE_NUMBER_FLAG_SIGNED
    private val phone = InputType.TYPE_CLASS_PHONE
    private val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private val datetime = InputType.TYPE_CLASS_DATETIME

    @Test
    fun inputTypeSelectsKeypad() {
        assertEquals(SoftwareKeypad.NUMBER, SoftwareKeypadPolicy.autoKeypadFor(pin))
        assertEquals(SoftwareKeypad.NUMBER, SoftwareKeypadPolicy.autoKeypadFor(decimal))
        assertEquals(SoftwareKeypad.PHONE, SoftwareKeypadPolicy.autoKeypadFor(phone))
        assertEquals(SoftwareKeypad.FULL, SoftwareKeypadPolicy.autoKeypadFor(text))
        assertEquals(SoftwareKeypad.FULL, SoftwareKeypadPolicy.autoKeypadFor(datetime))
        assertEquals(SoftwareKeypad.FULL, SoftwareKeypadPolicy.autoKeypadFor(InputType.TYPE_NULL))
    }

    @Test
    fun hardwareKeypadOpensOnlyWhenEnabledInNumericFields() {
        assertFalse(SoftwareKeypadPolicy.shouldAutoOpenOnHardware(pin, autoOpenEnabled = false))
        assertFalse(SoftwareKeypadPolicy.shouldAutoOpenOnHardware(phone, autoOpenEnabled = false))
        assertTrue(SoftwareKeypadPolicy.shouldAutoOpenOnHardware(pin, autoOpenEnabled = true))
        assertTrue(SoftwareKeypadPolicy.shouldAutoOpenOnHardware(phone, autoOpenEnabled = true))
        assertFalse(SoftwareKeypadPolicy.shouldAutoOpenOnHardware(text, autoOpenEnabled = true))
    }

    @Test
    fun hardwareAutoOpenDecidesOnTheFirstNewShowRequestOnly() {
        val decision = HardwareKeypadAutoOpen()
        assertFalse(decision.onShowRequested(configChange = false))
        decision.begin()
        assertFalse(decision.onShowRequested(configChange = true))
        assertTrue(decision.onShowRequested(configChange = false))
        assertFalse(decision.onShowRequested(configChange = false))

        decision.begin()
        decision.settle()
        assertFalse(decision.awaitingShowRequest)
        assertFalse(decision.onShowRequested(configChange = false))
    }

    @Test
    fun manualChoiceLastsUntilTheFieldChanges() {
        val session = SoftwareKeypadSession()
        session.onFieldStarted(pin, restarting = false)
        assertEquals(SoftwareKeypad.NUMBER, session.resolved())

        session.select(SoftwareKeypad.FULL)
        session.onFieldStarted(pin, restarting = true)
        assertEquals(SoftwareKeypad.FULL, session.resolved())

        session.onFieldFinished()
        session.onFieldStarted(phone, restarting = false)
        assertEquals(SoftwareKeypad.PHONE, session.resolved())
        assertNull(session.manualKeypad)

        session.select(SoftwareKeypad.NUMBER)
        session.onFieldStarted(text, restarting = false)
        assertEquals(SoftwareKeypad.FULL, session.resolved())
    }

    @Test
    fun chooserListsLayoutsThenCyclePagesAndAlwaysTheEmojiPicker() {
        val labels = SoftwareKeyboardPageChoices.Labels("ABC", "123", "+*#", "E", "#+=", "Sym", "C", "P")
        val config = SymPagesConfig(deviceEnabled = false, emojiEnabled = false, symbolsEnabled = true)
        val ids = SoftwareKeyboardPageChoices.build(config, labels).map { it.id }
        assertEquals(
            listOf("letters", "number", "phone", SoftwareKeyboardPageChoices.pageId(2), SoftwareKeyboardPageChoices.pageId(4)),
            ids
        )
        val withPicker = SoftwareKeyboardPageChoices.build(config.copy(emojiPickerEnabled = true, emojiEnabled = true), labels)
        assertEquals(1, withPicker.count { it.id == SoftwareKeyboardPageChoices.pageId(4) })
        assertTrue(withPicker.any { it.id == SoftwareKeyboardPageChoices.pageId(1) })
    }

    @Test
    fun choiceIdsRoundTrip() {
        assertEquals(SoftwareKeypad.FULL, SoftwareKeyboardPageChoices.keypadFromId(SoftwareKeyboardPageChoices.LETTERS))
        assertEquals(SoftwareKeypad.PHONE, SoftwareKeyboardPageChoices.keypadFromId(SoftwareKeyboardPageChoices.PHONE))
        assertNull(SoftwareKeyboardPageChoices.keypadFromId(SoftwareKeyboardPageChoices.pageId(3)))
        assertEquals(3, SoftwareKeyboardPageChoices.pageFromId(SoftwareKeyboardPageChoices.pageId(3)))
        assertNull(SoftwareKeyboardPageChoices.pageFromId(SoftwareKeyboardPageChoices.NUMBER))
    }
}
