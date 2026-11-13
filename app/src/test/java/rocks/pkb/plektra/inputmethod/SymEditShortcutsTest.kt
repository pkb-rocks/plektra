package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class SymEditShortcutsTest {
    private fun connection(accepts: Boolean, performed: MutableList<Int> = mutableListOf()): InputConnection =
        Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            if (method.name == "performContextMenuAction") {
                performed.add(args!![0] as Int)
                accepts
            } else {
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        } as InputConnection

    @Test
    fun editKeysMapToTheirActions() {
        assertEquals(android.R.id.copy, SymEditShortcuts.actionFor(KeyEvent.KEYCODE_C))
        assertEquals(android.R.id.paste, SymEditShortcuts.actionFor(KeyEvent.KEYCODE_V))
        assertEquals(android.R.id.cut, SymEditShortcuts.actionFor(KeyEvent.KEYCODE_X))
        assertEquals(android.R.id.selectAll, SymEditShortcuts.actionFor(KeyEvent.KEYCODE_A))
    }

    @Test
    fun otherKeysStayChords() {
        assertNull(SymEditShortcuts.actionFor(KeyEvent.KEYCODE_B))
        assertNull(SymEditShortcuts.actionFor(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun acceptedActionIsPerformedAndConsumed() {
        val performed = mutableListOf<Int>()
        val result = SymEditShortcuts.perform(KeyEvent.KEYCODE_C, false, connection(true, performed))
        assertEquals(android.R.id.copy, result)
        assertEquals(listOf(android.R.id.copy), performed)
    }

    @Test
    fun falseReturnIsStillConsumed() {
        // An IME cannot tell a refusal from success, so the result never decides the outcome.
        val performed = mutableListOf<Int>()
        val result = SymEditShortcuts.perform(KeyEvent.KEYCODE_V, false, connection(false, performed))
        assertEquals(android.R.id.paste, result)
        assertEquals(listOf(android.R.id.paste), performed)
    }

    @Test
    fun missingConnectionFallsThrough() {
        assertNull(SymEditShortcuts.perform(KeyEvent.KEYCODE_X, false, null))
    }

    @Test
    fun nonEditKeyIsNotPerformed() {
        val performed = mutableListOf<Int>()
        assertNull(SymEditShortcuts.perform(KeyEvent.KEYCODE_B, false, connection(true, performed)))
        assertEquals(emptyList<Int>(), performed)
    }

    @Test
    fun heldLatchedOrOneShotAltSkipsTheShortcut() {
        // The caller folds physical, latched, one-shot and IME-tracked Alt into one flag.
        val performed = mutableListOf<Int>()
        assertNull(SymEditShortcuts.perform(KeyEvent.KEYCODE_A, true, connection(true, performed)))
        assertEquals(emptyList<Int>(), performed)
    }
}
