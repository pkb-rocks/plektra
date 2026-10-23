package rocks.pkb.plektra

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar

/** Both rows use the production renderer. Dragging changes only the in-memory preview. */
internal class KeyboardTextScalePreview(context: Context) : LinearLayout(context) {
    private var selectedScale = 1f
    private val selectedLabel = TextView(context)
    private val referenceLabel = TextView(context)
    private val examples = context.getString(R.string.keyboard_text_size_preview_words).split("|")

    private fun previewContext(value: () -> Float) = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun getFloat(key: String?, defValue: Float): Float =
                    if (key == KeyboardBarTypography.KEY) value() else real.getFloat(key, defValue)
            }
        }
    }

    private val reference = FullSuggestionsBar(previewContext { 1f })
    private val selected = FullSuggestionsBar(previewContext { selectedScale })

    init {
        orientation = VERTICAL
        referenceLabel.text = context.getString(R.string.keyboard_text_size_reference)
        referenceLabel.textSize = 12f
        selectedLabel.textSize = 12f
        addView(referenceLabel)
        addView(reference.ensureView())
        addView(selectedLabel)
        addView(selected.ensureView())
        reference.showPreview(examples)
        setScale(1f)
    }

    fun setScale(value: Float) {
        selectedScale = KeyboardBarTypography.normalize(value)
        selectedLabel.text = context.getString(R.string.keyboard_text_size_selected,
            (selectedScale * 100).roundToInt())
        selected.showPreview(examples)
    }

    fun setLabelColor(color: Int) {
        referenceLabel.setTextColor(color)
        selectedLabel.setTextColor(color)
    }
}
