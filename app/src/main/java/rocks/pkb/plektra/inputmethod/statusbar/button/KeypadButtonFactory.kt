package rocks.pkb.plektra.inputmethod.statusbar.button

import android.content.Context
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.statusbar.ButtonCreationResult
import rocks.pkb.plektra.inputmethod.statusbar.ButtonState
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonStyles
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarCallbacks

/** Contextual entry to the on-screen keypad in number and phone fields; dimmed to stay unobtrusive. */
class KeypadButtonFactory : StatusBarButtonFactory {
    override fun create(context: Context, size: Int, callbacks: StatusBarCallbacks): ButtonCreationResult {
        val button = ImageView(context).apply {
            setImageResource(R.drawable.ic_keyboard_24)
            setColorFilter(Color.WHITE)
            imageAlpha = 170
            contentDescription = context.getString(R.string.software_keyboard_keypad_button_description)
            background = StatusBarButtonStyles.createButtonDrawable(size)
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                callbacks.onKeypadRequested?.invoke()
            }
        }
        return ButtonCreationResult(view = button)
    }

    override fun update(view: View, state: ButtonState) = Unit
}
