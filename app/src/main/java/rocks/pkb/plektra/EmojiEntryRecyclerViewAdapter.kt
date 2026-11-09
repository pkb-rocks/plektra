package rocks.pkb.plektra

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import rocks.pkb.plektra.data.emoji.EmojiRepository

/**
 * Adapter for emoji entries with optional variants shown on long-press popup.
 * [textLike] entries (kaomoji) are shown on one line, shrunk to fit.
 */
class EmojiEntryRecyclerViewAdapter(
    private val entries: List<EmojiRepository.EmojiEntry>,
    private val textLike: (String) -> Boolean = { false },
    private val onEmojiSelected: (String) -> Unit
) : RecyclerView.Adapter<EmojiEntryRecyclerViewAdapter.EmojiEntryViewHolder>() {
    class EmojiEntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val emojiText: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiEntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        val holder = EmojiEntryViewHolder(view)

        holder.emojiText.apply {
            textSize = 28.8f // 24 * 1.2 (20% larger)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            val size = (48 * parent.context.resources.displayMetrics.density).toInt()
            minHeight = size
            minWidth = size
            setTypeface(null, Typeface.BOLD)
            val nightModeFlags = parent.context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDarkTheme = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
        }

        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION && position < entries.size) {
                onEmojiSelected(entries[position].base)
            }
        }

        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= entries.size) return@setOnLongClickListener true
            val entry = entries[position]
            if (entry.variants.isEmpty()) return@setOnLongClickListener false
            showVariantsPopup(view, entry)
            true
        }

        return holder
    }

    override fun onBindViewHolder(holder: EmojiEntryViewHolder, position: Int) {
        val entry = entries[position]
        holder.emojiText.apply {
            if (textLike(entry.base)) {
                maxLines = 1
                setTypeface(null, Typeface.NORMAL)
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 8, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP
                )
            } else {
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(
                    this, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE
                )
                maxLines = Int.MAX_VALUE
                textSize = 28.8f
                setTypeface(null, Typeface.BOLD)
            }
        }
        holder.emojiText.text = rocks.pkb.plektra.data.emoji.EmojiRendering.forDisplay(holder.emojiText.context, entry.base)
    }

    override fun getItemCount(): Int = entries.size

    private fun showVariantsPopup(anchor: View, entry: EmojiRepository.EmojiEntry) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (16 * density).toInt()
        val verticalPadding = (12 * density).toInt()
        val itemHorizontalPadding = (12 * density).toInt()
        val itemVerticalPadding = (8 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            gravity = Gravity.CENTER
        }

        var popup: PopupWindow? = null
        val options = listOf(entry.base) + entry.variants
        options.forEach { emoji ->
            val textView = TextView(context).apply {
                text = rocks.pkb.plektra.data.emoji.EmojiRendering.forDisplay(context, emoji)
                gravity = Gravity.CENTER
                textSize = 24f
                setPadding(itemHorizontalPadding, itemVerticalPadding, itemHorizontalPadding, itemVerticalPadding)
                val nightModeFlags = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isDarkTheme = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
            }
            textView.setOnClickListener {
                onEmojiSelected(emoji)
                popup?.dismiss()
            }
            container.addView(textView)
        }

        val maxPopupWidth = context.resources.displayMetrics.widthPixels - (16 * density).toInt()
        val popupContent = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(container)
        }

        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        popup = PopupWindow(
            popupContent,
            minOf(popupContent.measuredWidth, maxPopupWidth),
            popupContent.measuredHeight,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#DDFFFFFF")))
            isOutsideTouchable = true
            elevation = 8f
        }

        popup.showAsDropDown(anchor, 0, -anchor.height / 2)
    }
}
