package rocks.pkb.plektra.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymLayoutControllerPickerGateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var pickersAllowed = true

    private fun controller(): SymLayoutController {
        val prefs = context.getSharedPreferences("sym_picker_gate_test", Context.MODE_PRIVATE)
        return SymLayoutController(context, prefs, AlternateCharacterManager(context.assets, prefs, context)) {
            pickersAllowed
        }
    }

    @Test
    fun blockedPickersDoNotOpenButKeyLayersStillCycle() {
        SettingsManager.setSymPagesConfig(
            context,
            SymPagesConfig(deviceEnabled = true, emojiEnabled = false, symbolsEnabled = false,
                clipboardEnabled = true, emojiPickerEnabled = true)
        )
        val sym = controller()
        pickersAllowed = false

        assertFalse(sym.openClipboardPage())
        assertFalse(sym.openEmojiPickerPage())
        assertEquals(0, sym.currentSymPage())
        // The cycle skips clipboard and emoji picker: device layer, then closed again.
        assertEquals(5, sym.toggleSymPage())
        assertEquals(0, sym.toggleSymPage())
        assertTrue(sym.openSymbolsPage())
    }

    @Test
    fun pickerOpenedBeforeTheBlockClosesOnNextRead() {
        val sym = controller()
        assertTrue(sym.openEmojiPickerPage())
        assertEquals(4, sym.currentSymPage())
        pickersAllowed = false
        assertEquals(0, sym.currentSymPage())
        pickersAllowed = true
        assertTrue(sym.openClipboardPage())
        assertEquals(3, sym.currentSymPage())
    }

    @Test
    fun blockedPickersStayClosedForTheChooserAndThePeekedCycle() {
        SettingsManager.setSymPagesConfig(
            context,
            SymPagesConfig(deviceEnabled = false, emojiEnabled = false, symbolsEnabled = true,
                clipboardEnabled = true, emojiPickerEnabled = false)
        )
        val sym = controller()
        pickersAllowed = false
        assertFalse(sym.openPage(3))
        assertFalse(sym.openPage(4))
        assertTrue(sym.openPage(5))
        sym.closeSymPage()
        assertEquals(2, sym.peekNextSymPage())
        assertTrue(sym.openPage(2))
        // Unblocked, the clipboard would follow the symbols page.
        assertEquals(0, sym.peekNextSymPage())
    }
}
