package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.inputmethod.InputMethodManager

/** Plektra's IME switcher buttons open Android's input method picker. */
internal object ImeSwitcher {
    /** Replaces the picker in tests; Robolectric does not record it. */
    @Volatile internal var openForTests: ((Context) -> Unit)? = null

    /** Use the IME context so Android associates the picker with the IME's display. */
    fun open(context: Context) {
        openForTests?.let { return it(context) }
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }
}
