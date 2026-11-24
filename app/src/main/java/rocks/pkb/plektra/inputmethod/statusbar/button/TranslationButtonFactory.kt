package rocks.pkb.plektra.inputmethod.statusbar.button

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.statusbar.*

class TranslationButtonFactory : StatusBarButtonFactory {
    override fun create(context: Context, size: Int, callbacks: StatusBarCallbacks): ButtonCreationResult =
        ButtonCreationResult(view = ImageView(context).apply {
            tag = TAG; id = R.id.field_translation_toggle
            setImageResource(R.drawable.ic_field_translation); setColorFilter(Color.WHITE)
            contentDescription = context.getString(R.string.status_bar_button_translation_description)
            background = StatusBarButtonStyles.createButtonDrawable(size)
            scaleType = ImageView.ScaleType.CENTER; isClickable = true; isFocusable = true
            bind(this,callbacks)
        })
    override fun update(view: View, state: ButtonState) = Unit
    companion object {
        private const val TAG = "plektra.translation.button"
        fun bind(view: View,callbacks: StatusBarCallbacks) {
            view.setOnClickListener {
                callbacks.onHapticFeedback?.invoke(); callbacks.onTranslationRequested?.invoke()
            }
        }
        fun updateState(view: View?, active: Boolean) {
            if (view is ImageView && view.tag == TAG && view.isActivated != active) {
                view.isActivated = active
                view.setImageResource(if (active) android.R.drawable.ic_menu_close_clear_cancel else R.drawable.ic_field_translation)
                view.contentDescription = view.context.getString(if (active) R.string.translation_close else R.string.status_bar_button_translation_description)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) updateState(view.getChildAt(i), active)
        }
    }
}
