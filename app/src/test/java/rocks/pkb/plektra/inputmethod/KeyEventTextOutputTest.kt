package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyEventTextOutputTest {
    private val received = mutableListOf<KeyEvent>()
    private val connection = object : BaseInputConnection(android.view.View(org.robolectric.RuntimeEnvironment.getApplication()), false) {
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            received += event
            return true
        }
    }

    @Test
    fun arithmeticExpressionHasOneUnmodifiedKeyPairPerCharacter() {
        val output = KeyEventTextOutput()
        assertTrue(output.sendText(KeyEvent.KEYCODE_A, "(1.5+2)*3/4-0,5=", connection))
        assertEquals(32, received.size)
        assertTrue(received.all { it.metaState == 0 })
        assertEquals(1, received.count { it.action == KeyEvent.ACTION_UP && it.keyCode == KeyEvent.KEYCODE_PERIOD })
        assertTrue(output.consumeKeyUp(KeyEvent.KEYCODE_A))
        assertFalse(output.consumeKeyUp(KeyEvent.KEYCODE_A))
    }

    @Test
    fun repeatRemembersTextUntilReleaseOrANewPress() {
        val output = KeyEventTextOutput()
        output.sendText(KeyEvent.KEYCODE_A, "*", connection)
        assertEquals("*", output.textForRepeat(KeyEvent.KEYCODE_A,
            KeyEvent(10L, 20L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 1)))
        assertEquals(null, output.textForRepeat(KeyEvent.KEYCODE_A,
            KeyEvent(30L, 30L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)))
    }
}
