package rocks.pkb.plektra.inputmethod.statusbar.button

import android.content.Context
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.statusbar.ButtonCreationResult
import rocks.pkb.plektra.inputmethod.statusbar.ButtonState
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarCallbacks
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonStyles

/**
 * Opens Android’s input method picker.
 */
class KeyboardPickerButtonFactory : StatusBarButtonFactory {

    override fun create(context: Context, size: Int, callbacks: StatusBarCallbacks): ButtonCreationResult {
        val button = createButton(context, size)

        // Use the IME context so Android associates the picker with this display.
        button.setOnClickListener {
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            rocks.pkb.plektra.inputmethod.ImeSwitcher.open(context)
        }

        return ButtonCreationResult(view = button)
    }

    override fun update(view: View, state: ButtonState) {
        // No state to update for settings button
    }

    private fun createButton(context: Context, size: Int): ImageView {
        return ImageView(context).apply {
            setImageResource(rocks.pkb.plektra.inputmethod.ImeSwitcherPolicy.ICON)
            setColorFilter(Color.WHITE)
            contentDescription = context.getString(R.string.status_bar_button_keyboard_picker_description)
            background = StatusBarButtonStyles.createButtonDrawable(size)
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            // layoutParams will be set by VariationBarView for consistency
        }
    }
}
