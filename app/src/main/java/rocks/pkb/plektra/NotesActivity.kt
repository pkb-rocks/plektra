package rocks.pkb.plektra

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.ext.tasklist.TaskListSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

open class NotesActivity : Activity() {
    protected lateinit var editor: EditText
    protected lateinit var heading: EditText
    protected lateinit var root: LinearLayout
    protected open val notesPreferencesName: String = "notes"
    private val notePrefs by lazy { getSharedPreferences(notesPreferencesName, MODE_PRIVATE) }
    private val notes by lazy { NotesRepository(notePrefs) }
    private lateinit var selectedNote: NotesRepository.Note
    private val noteCollectionListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "collection") closeRemovedNote()
    }
    private var loadingNote = false
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var lastBody = ""
    private var restoringHistory = false
    private var continuingList = false
    private lateinit var preview: TextView
    private lateinit var previewScroll: ScrollView
    private lateinit var formatBar: HorizontalScrollView
    private lateinit var panes: LinearLayout
    private lateinit var paneDivider: View
    private val modeTabs = mutableListOf<Pair<ViewMode, TextView>>()
    private val actionIcons = mutableMapOf<TextView, Drawable>()
    private lateinit var undoButton: TextView
    private lateinit var redoButton: TextView
    private var editingSelectionStart = 0
    private var editingSelectionEnd = 0
    private var viewMode = ViewMode.MARKDOWN
    private var inkColor = Color.BLACK
    private var accentColor = Color.rgb(0, 119, 122)
    private var dividerColor = Color.LTGRAY
    private enum class ViewMode { MARKDOWN, BOTH, PREVIEW }
    private var restoreHeadingFocus = false
    private var horizontalGutter = 24
    private val markwon by lazy {
        Markwon.builder(this).usePlugin(TablePlugin.create(this))
            .usePlugin(TaskListPlugin.create(inkColor, dividerColor, Color.WHITE))
            .usePlugin(StrikethroughPlugin.create()).build()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedNote = notes.active()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val paper = if (dark) Color.rgb(28, 29, 31) else Color.rgb(250, 250, 248)
        val ink = if (dark) Color.rgb(237, 237, 235) else Color.rgb(30, 34, 36)
        inkColor = ink
        accentColor = if (dark) Color.rgb(101, 211, 208) else Color.rgb(0, 119, 122)
        dividerColor = if (dark) Color.rgb(65, 66, 68) else Color.rgb(218, 219, 216)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(dp(horizontalGutter) + bars.left, dp(12) + bars.top, dp(horizontalGutter) + bars.right, dp(8) + maxOf(bars.bottom, ime.bottom))
            insets
        }
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        navigation.addView(iconAction(R.drawable.ic_notes_back, R.string.settings_back_content_description) { finish() })
        heading = EditText(this).apply {
            id = R.id.notes_heading
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink)
            setHintTextColor(if (dark) Color.LTGRAY else Color.GRAY)
            hint = getString(R.string.notes_untitled)
            background = null
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            setText(selectedNote.title)
            setPadding(dp(8), dp(8), 0, dp(8))
        }
        navigation.addView(heading, LinearLayout.LayoutParams(0, dp(56), 1f))
        if (resources.configuration.screenWidthDp >= 600) navigation.addView(TextView(this).apply {
            text = "✓ ${getString(R.string.notes_saved)}"
            setTextColor(if (dark) Color.LTGRAY else Color.GRAY)
            textSize = 12f
            setPadding(dp(12), 0, dp(12), 0)
        })
        root.addView(navigation)
        val tabs = LinearLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), dividerColor)
            }
            clipToOutline = true
        }
        val modes = listOf(
            Triple(ViewMode.MARKDOWN, R.drawable.ic_notes_edit, R.string.notes_markdown),
            Triple(ViewMode.BOTH, R.drawable.ic_notes_split, R.string.notes_both),
            Triple(ViewMode.PREVIEW, R.drawable.ic_notes_preview, R.string.notes_preview),
        )
        modes.forEachIndexed { index, (mode, icon, label) ->
            if (index > 0) tabs.addView(View(this).apply { setBackgroundColor(dividerColor) },
                LinearLayout.LayoutParams(dp(1), dp(48)))
            val tab = iconAction(icon, label) { setViewMode(mode, showInput = true) }
            tab.id = when (mode) {
                ViewMode.MARKDOWN -> R.id.notes_markdown_toggle
                ViewMode.BOTH -> R.id.notes_both_toggle
                ViewMode.PREVIEW -> R.id.notes_preview_toggle
            }
            modeTabs += mode to tab
            tabs.addView(tab)
        }
        navigation.addView(tabs, LinearLayout.LayoutParams(-2, dp(48)))
        navigation.addView(iconAction(R.drawable.ic_notes_add, R.string.notes_new) { switchNote(notes.create(), focusHeading = true) })
        navigation.addView(iconAction(R.drawable.ic_notes_more, R.string.notes_title) { showNotes() })
        var titleOnSeparateLine = false
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val leftInset = root.paddingLeft - dp(horizontalGutter)
            val rightInset = root.paddingRight - dp(horizontalGutter)
            val narrow = root.width - leftInset - rightInset < dp(400)
            val gutter = if (narrow) 8 else 24
            if (horizontalGutter != gutter) {
                horizontalGutter = gutter
                root.setPadding(dp(gutter) + leftInset, root.paddingTop, dp(gutter) + rightInset, root.paddingBottom)
            }
            if (narrow != titleOnSeparateLine) {
                val headingHadFocus = heading.hasFocus()
                (heading.parent as ViewGroup).removeView(heading)
                if (narrow) root.addView(heading, 0, LinearLayout.LayoutParams(-1, dp(56)))
                else navigation.addView(heading, 1, LinearLayout.LayoutParams(0, dp(56), 1f))
                titleOnSeparateLine = narrow
                if (headingHadFocus || (viewMode != ViewMode.PREVIEW && restoreHeadingFocus && currentFocus == null)) {
                    heading.requestFocus()
                }
            }
        }
        editor = object : EditText(this) {
            override fun isSuggestionsEnabled() = false
            private val gutterPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.LTGRAY else Color.GRAY
                textSize = dp(11).toFloat()
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val textLayout = layout ?: return
                var number = 1
                for (line in 0 until textLayout.lineCount) {
                    val offset = textLayout.getLineStart(line)
                    if (offset == 0 || text.getOrNull(offset - 1) == '\n') {
                        canvas.drawText(number++.toString(), dp(28).toFloat(),
                            (textLayout.getLineBaseline(line) + totalPaddingTop).toFloat(), gutterPaint)
                    }
                }
            }
        }.apply {
            id = R.id.notes_body
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(ink)
            setHintTextColor(if (dark) Color.LTGRAY else Color.GRAY)
            hint = getString(R.string.notes_body_hint)
            background = null
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(selectedNote.body)
            setPadding(dp(44), dp(16), dp(16), dp(16))
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        preview = TextView(this).apply {
            id = R.id.notes_preview_body
            textSize = 17f
            setTextColor(ink)
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(8), dp(16))
            setOnTouchListener { _, event ->
                val layout = layout
                val source = text as? android.text.Spanned
                if (layout == null || source == null) false else {
                    val line = layout.getLineForVertical((event.y - totalPaddingTop).toInt())
                    val offset = layout.getLineStart(line)
                    val span = source.getSpans(offset, minOf(offset + 1, source.length), TaskListSpan::class.java).firstOrNull()
                    if (span == null || event.x > totalPaddingLeft + span.getLeadingMargin(true) + dp(8)) false else {
                        if (event.actionMasked == MotionEvent.ACTION_UP) togglePreviewCheckbox(span)
                        true
                    }
                }
            }
        }
        previewScroll = ScrollView(this).apply { addView(preview); visibility = View.GONE }

        val formats = LinearLayout(this)
        fun format(icon: Int, label: Int, before: String, after: String = "") {
            formats.addView(iconAction(icon, label) { insertMarkup(before, after) })
        }
        undoButton = iconAction(R.drawable.ic_undo_24, R.string.notes_undo) { restoreHistory(undo, redo) }.apply { id = R.id.notes_undo_button }
        redoButton = iconAction(R.drawable.ic_redo_24, R.string.notes_redo) { restoreHistory(redo, undo) }.apply { id = R.id.notes_redo_button }
        formats.addView(undoButton)
        formats.addView(redoButton)
        val headingButton = iconAction(R.drawable.ic_notes_heading, R.string.notes_format_heading) {}
        headingButton.setOnClickListener {
            PopupMenu(this, headingButton).apply {
                (1..3).forEach { menu.add(0, it, it, "H$it") }
                setOnMenuItemClickListener { prefixLines("#".repeat(it.itemId) + " "); true }
                show()
            }
        }
        formats.addView(headingButton)
        format(R.drawable.ic_notes_bold, R.string.notes_format_bold, "**", "**")
        format(R.drawable.ic_notes_italic, R.string.notes_format_italic, "*", "*")
        format(R.drawable.ic_notes_strike, R.string.notes_strike, "~~", "~~")
        formats.addView(iconAction(R.drawable.ic_notes_list, R.string.notes_format_list) { toggleList(MarkdownListEditing.Kind.BULLET) })
        formats.addView(iconAction(R.drawable.ic_notes_numbered, R.string.notes_numbered) { toggleList(MarkdownListEditing.Kind.NUMBERED) })
        formats.addView(iconAction(R.drawable.ic_notes_checklist, R.string.notes_checklist) { toggleList(MarkdownListEditing.Kind.CHECKBOX) })
        formats.addView(iconAction(R.drawable.ic_notes_quote, R.string.notes_quote) { prefixLines("> ") })
        formats.addView(iconAction(R.drawable.ic_notes_link, R.string.notes_format_link) { insertMarkup("[", "](https://)") })
        formats.addView(iconAction(R.drawable.ic_notes_code, R.string.notes_format_code) { insertMarkup("`", "`") })
        format(R.drawable.ic_notes_code_block, R.string.notes_code_block, "\n```\n", "\n```\n")
        formats.addView(iconAction(R.drawable.ic_notes_table, R.string.notes_table) { insertMarkup("\n| Column | Column |\n| --- | --- |\n| ", " |  |\n") })
        formatBar = HorizontalScrollView(this).apply {
            addView(formats)
            isHorizontalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        updateHistoryButtons()
        root.addView(formatBar)
        panes = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp >= 600) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        paneDivider = View(this).apply { setBackgroundColor(dividerColor) }
        panes.addView(editor)
        panes.addView(paneDivider)
        panes.addView(previewScroll)
        root.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (BuildConfig.DEBUG) root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            android.util.Log.d("PlektraNotesFocus", "focus ${old?.id} -> ${new?.id} editor=${editor.hasFocus()} heading=${heading.hasFocus()}")
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        heading.doAfterTextChanged { saveNote() }
        lastBody = editor.text.toString()
        editor.doAfterTextChanged {
            if (continuingList) return@doAfterTextChanged
            if (!loadingNote && !restoringHistory) {
                MarkdownListEditing.continueAfterEnter(lastBody, editor.text.toString(), editor.selectionStart)?.let { edit ->
                    continuingList = true
                    editor.text.replace(0, editor.length(), edit.text)
                    editor.setSelection(edit.start)
                    continuingList = false
                }
            }
            val current = editor.text.toString()
            if (!loadingNote && !restoringHistory && current != lastBody) {
                undo.addLast(lastBody)
                if (undo.size > 100) undo.removeFirst()
                redo.clear()
            }
            lastBody = current
            updateHistoryButtons()
            saveNote()
            if (viewMode != ViewMode.MARKDOWN) markwon.setMarkdown(preview, editor.text.toString())
        }
        val focusId = state?.getInt("focus", R.id.notes_body) ?: if (selectedNote.headingFocused) R.id.notes_heading else R.id.notes_body
        restoreHeadingFocus = focusId == R.id.notes_heading
        val target = if (restoreHeadingFocus) heading else editor
        target.requestFocus()
        val selection = selectedNote.cursor.coerceIn(0, target.length())
        val selectionStart = state?.getInt("editing_selection_start", selection) ?: selection
        val selectionEnd = state?.getInt("editing_selection_end", selectionStart) ?: selectionStart
        editingSelectionStart = selectionStart.coerceIn(0, target.length())
        editingSelectionEnd = selectionEnd.coerceIn(0, target.length())
        target.setSelection(editingSelectionStart, editingSelectionEnd)
        val mode = state?.getInt("view_mode") ?: notePrefs.getInt("view_mode", 0)
        setViewMode(ViewMode.entries.getOrElse(mode) { ViewMode.MARKDOWN }, showInput = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("focus", if (heading.hasFocus() || (viewMode == ViewMode.PREVIEW && restoreHeadingFocus)) R.id.notes_heading else R.id.notes_body)
        outState.putInt("view_mode", viewMode.ordinal)
        val target = if (heading.hasFocus()) heading else editor
        outState.putInt("editing_selection_start", if (viewMode == ViewMode.PREVIEW && !heading.hasFocus()) editingSelectionStart else target.selectionStart)
        outState.putInt("editing_selection_end", if (viewMode == ViewMode.PREVIEW && !heading.hasFocus()) editingSelectionEnd else target.selectionEnd)
        saveNote()
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        notePrefs.registerOnSharedPreferenceChangeListener(noteCollectionListener)
    }

    override fun onStop() {
        notePrefs.unregisterOnSharedPreferenceChangeListener(noteCollectionListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        closeRemovedNote()
    }

    private fun closeRemovedNote() {
        if (!isFinishing && (!notePrefs.contains("collection") || notes.all().none { it.id == selectedNote.id })) finish()
    }

    override fun onPause() {
        saveNote()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val target = currentFocus as? EditText
        val start = target?.selectionStart
        val end = target?.selectionEnd
        super.onConfigurationChanged(newConfig)
        if (viewMode != ViewMode.PREVIEW && target != null) {
            target.requestFocus()
            target.setSelection((start ?: 0).coerceIn(0, target.length()), (end ?: 0).coerceIn(0, target.length()))
        }
        if (BuildConfig.DEBUG) android.util.Log.d("PlektraNotesFocus", "configuration keyboard=${newConfig.keyboard} hidden=${newConfig.hardKeyboardHidden} focus=${currentFocus?.id}")
    }

    private fun saveNote() {
        if (loadingNote || (!notePrefs.contains("collection") || notes.all().none { it.id == selectedNote.id })) return
        val target = if (heading.hasFocus() || (viewMode == ViewMode.PREVIEW && restoreHeadingFocus)) heading else editor
        notePrefs.edit().putString("heading", heading.text.toString()).putString("body", editor.text.toString())
            .putInt("focus", target.id).putInt("cursor", target.selectionStart.coerceAtLeast(0))
            .putInt("view_mode", viewMode.ordinal).apply()
        selectedNote = selectedNote.copy(title = heading.text.toString(), body = editor.text.toString(),
            cursor = target.selectionStart.coerceAtLeast(0), headingFocused = target == heading)
        notes.save(selectedNote)
        title = selectedNote.title.ifBlank { getString(R.string.notes_untitled) }
    }

    private fun setViewMode(mode: ViewMode, showInput: Boolean) {
        if (showInput && (viewMode != ViewMode.PREVIEW || heading.hasFocus())) {
            restoreHeadingFocus = heading.hasFocus()
            val target = if (restoreHeadingFocus) heading else editor
            editingSelectionStart = target.selectionStart.coerceAtLeast(0)
            editingSelectionEnd = target.selectionEnd.coerceAtLeast(0)
        }
        if (mode == ViewMode.PREVIEW) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
            root.isFocusableInTouchMode = true
            root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            root.requestFocus()
        } else {
            root.isFocusableInTouchMode = false
            root.isFocusable = false
            root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        viewMode = mode
        val editing = mode != ViewMode.PREVIEW
        editor.visibility = if (editing) View.VISIBLE else View.GONE
        formatBar.visibility = if (editing) View.VISIBLE else View.GONE
        previewScroll.visibility = if (mode == ViewMode.MARKDOWN) View.GONE else View.VISIBLE
        paneDivider.visibility = if (mode == ViewMode.BOTH) View.VISIBLE else View.GONE
        val horizontal = panes.orientation == LinearLayout.HORIZONTAL
        editor.layoutParams = LinearLayout.LayoutParams(if (horizontal) 0 else -1, if (horizontal) -1 else 0, 1f)
        previewScroll.layoutParams = LinearLayout.LayoutParams(if (horizontal) 0 else -1, if (horizontal) -1 else 0, 1f)
        paneDivider.layoutParams = LinearLayout.LayoutParams(if (horizontal) dp(1) else -1, if (horizontal) -1 else dp(1))
        modeTabs.forEach { (tabMode, tab) ->
            val selected = tabMode == mode
            tab.isSelected = selected
            actionIcons[tab]?.setTint(if (selected) accentColor else inkColor)
        }
        if (mode != ViewMode.MARKDOWN) markwon.setMarkdown(preview, editor.text.toString())
        saveNote()
        if (editing && showInput) {
            val target = if (restoreHeadingFocus) heading else editor
            target.requestFocus()
            target.setSelection(editingSelectionStart.coerceIn(0, target.length()),
                editingSelectionEnd.coerceIn(0, target.length()))
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun insertMarkup(before: String, after: String) {
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
        val selected = editor.text.subSequence(start, end).toString()
        editor.text.replace(start, end, before + selected + after)
        editor.requestFocus()
        editor.setSelection(start + before.length, start + before.length + selected.length)
    }

    private fun toggleList(kind: MarkdownListEditing.Kind) {
        val edit = MarkdownListEditing.toggle(editor.text.toString(), editor.selectionStart, editor.selectionEnd, kind)
        editor.text.replace(0, editor.length(), edit.text)
        editor.requestFocus()
        editor.setSelection(edit.start, edit.end)
    }

    private fun togglePreviewCheckbox(span: TaskListSpan) {
        val rendered = preview.text as? android.text.Spanned ?: return
        val spans = rendered.getSpans(0, rendered.length, TaskListSpan::class.java).sortedBy { rendered.getSpanStart(it) }
        val lines = MarkdownListEditing.checkboxLines(editor.text.toString())
        if (lines.size != spans.size) return
        val line = lines.getOrNull(spans.indexOf(span)) ?: return
        val start = editor.selectionStart
        val end = editor.selectionEnd
        editor.text.replace(0, editor.length(), MarkdownListEditing.toggleCheckbox(editor.text.toString(), line))
        editor.setSelection(start.coerceAtLeast(0), end.coerceAtLeast(0))
    }

    private fun showNotes() {
        saveNote()
        val items = notes.all()
        AlertDialog.Builder(this).setTitle(R.string.notes_title)
            .setItems(items.map { it.title.ifBlank { getString(R.string.notes_untitled) } }.toTypedArray()) { _, index ->
                switchNote(items[index])
            }.setPositiveButton(R.string.notes_new) { _, _ -> switchNote(notes.create(), focusHeading = true) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun switchNote(note: NotesRepository.Note, focusHeading: Boolean = note.headingFocused) {
        saveNote()
        loadingNote = true
        selectedNote = note
        notes.select(note.id)
        heading.setText(note.title)
        editor.setText(note.body)
        undo.clear()
        redo.clear()
        updateHistoryButtons()
        restoreHeadingFocus = focusHeading
        val target = if (focusHeading) heading else editor
        target.requestFocus()
        target.setSelection(note.cursor.coerceIn(0, target.length()))
        editingSelectionStart = target.selectionStart
        editingSelectionEnd = target.selectionEnd
        loadingNote = false
        setViewMode(if (viewMode == ViewMode.PREVIEW) ViewMode.MARKDOWN else viewMode, showInput = true)
    }

    private fun restoreHistory(from: ArrayDeque<String>, to: ArrayDeque<String>) {
        if (from.isEmpty()) return
        to.addLast(editor.text.toString())
        restoringHistory = true
        editor.setText(from.removeLast())
        editor.setSelection(editor.length())
        restoringHistory = false
        updateHistoryButtons()
        editor.requestFocus()
    }

    private fun updateHistoryButtons() {
        listOf(undoButton to undo, redoButton to redo).forEach { (button, history) ->
            button.isEnabled = history.isNotEmpty()
            button.alpha = if (button.isEnabled) 1f else 0.38f
        }
    }

    private fun tintedIcon(resource: Int) = getDrawable(resource)?.mutate()?.apply {
        setTint(inkColor)
        setBounds(0, 0, dp(24), dp(24))
    }

    private fun iconAction(icon: Int, description: Int, onClick: () -> Unit): TextView =
        action("", getString(description), inkColor, onClick).apply {
            val glyph = requireNotNull(tintedIcon(icon))
            actionIcons[this] = glyph
            foreground = LayerDrawable(arrayOf(glyph)).apply {
                setLayerSize(0, dp(24), dp(24))
                setLayerGravity(0, Gravity.CENTER)
            }
        }

    private fun actionBackground(): Drawable {
        val selected = GradientDrawable().apply {
            setColor((accentColor and 0x00FFFFFF) or (0x20 shl 24))
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        return RippleDrawable(ColorStateList.valueOf((inkColor and 0x00FFFFFF) or (0x24 shl 24)),
            states, GradientDrawable().apply { setColor(Color.WHITE) })
    }

    private fun prefixLines(prefix: String) {
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
        val lineStart = editor.text.toString().lastIndexOf('\n', (start - 1).coerceAtLeast(-1)) + 1
        val content = editor.text.subSequence(lineStart, end).toString()
        editor.text.replace(lineStart, end, prefix + content.replace("\n", "\n$prefix"))
        editor.requestFocus()
        editor.setSelection(end + prefix.length * (content.count { it == '\n' } + 1))
    }

    private fun action(label: String, description: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        contentDescription = description
        textSize = 14f
        includeFontPadding = false
        setTextColor(color)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(0, 0, 0, 0)
        minimumWidth = dp(48)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        background = actionBackground()
        isClickable = true
        isFocusable = false
        tooltipText = description
        setOnClickListener { onClick() }
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
