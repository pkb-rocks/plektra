package rocks.pkb.plektra.inputmethod

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Selects apps whose TYPE_NULL input accepts characters through key events. */
object KeyEventInputPolicy {
    fun appliesTo(info: EditorInfo?): Boolean =
        info?.inputType == EditorInfo.TYPE_NULL && info.packageName == "com.google.android.calculator"
}

/** Delivers resolved characters without an editor, composing text, or an IME surface. */
class KeyEventTextOutput {
    private val pressedText = mutableMapOf<Int, String>()

    fun hasPressedKey(keyCode: Int): Boolean = pressedText.containsKey(keyCode)

    fun textForRepeat(keyCode: Int, event: KeyEvent?): String? {
        if (event?.repeatCount == 0) pressedText.remove(keyCode)
        return pressedText[keyCode]
    }

    fun sendText(keyCode: Int, text: String, connection: InputConnection): Boolean {
        val events = eventsFor(text) ?: return false
        for (event in events) connection.sendKeyEvent(event)
        pressedText[keyCode] = text
        return true
    }

    fun consumeKeyUp(keyCode: Int): Boolean = pressedText.remove(keyCode) != null

    private fun eventsFor(text: String): Array<KeyEvent>? {
        if (text.isEmpty()) return null
        val events = mutableListOf<KeyEvent>()
        for (char in text) {
            // Numpad operators have their own key codes and need no synthetic Shift chord.
            val keyCode = when (char) {
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
                '+', '＋' -> KeyEvent.KEYCODE_NUMPAD_ADD
                '-', '−' -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
                '*', '×' -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
                '/', '÷' -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
                '(' -> KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
                ')' -> KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
                '.' -> KeyEvent.KEYCODE_PERIOD
                ',' -> KeyEvent.KEYCODE_NUMPAD_COMMA
                '=' -> KeyEvent.KEYCODE_NUMPAD_EQUALS
                else -> null
            }
            if (keyCode != null) {
                val now = SystemClock.uptimeMillis()
                events += KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
                events += KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            } else {
                val characterEvents = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                    .getEvents(charArrayOf(char)) ?: return null
                events.addAll(characterEvents)
            }
        }
        return events.toTypedArray()
    }
}
