package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * Sym + C / V / X / A as copy, paste, cut and select-all inside a text field.
 *
 * Copy and paste otherwise live on Ctrl, which on the Titan is the Fn key only once the user
 * has remapped it; people reach for Sym first and got an emoji or an app launch instead.
 */
object SymEditShortcuts {
    /** The context-menu action for [keyCode], or null when the key is not an edit shortcut. */
    fun actionFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_C -> android.R.id.copy
        KeyEvent.KEYCODE_V -> android.R.id.paste
        KeyEvent.KEYCODE_X -> android.R.id.cut
        KeyEvent.KEYCODE_A -> android.R.id.selectAll
        else -> null
    }

    /**
     * Runs the edit shortcut for [keyCode] and returns its action, or null when the key is not
     * ours: it is not an edit key, Alt is held, latched, one-shot or tracked by the IME
     * ([altActive]), or there is no connection.
     *
     * The IME cannot see whether the app accepted the action: `performContextMenuAction` on an
     * IME's connection is a one-way call, so its result only says the connection is still alive.
     * An applicable Sym+C/V/X/A is therefore always consumed. Known limitation: an app that
     * ignores context-menu actions (some terminals) gets nothing for these keys while the
     * opt-in setting is on.
     */
    fun perform(keyCode: Int, altActive: Boolean, inputConnection: InputConnection?): Int? {
        if (altActive) return null
        val action = actionFor(keyCode) ?: return null
        if (inputConnection == null) return null
        inputConnection.performContextMenuAction(action)
        return action
    }
}
