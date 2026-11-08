package rocks.pkb.plektra

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager

/** A native text editor with synthetic content; the keyboard remains the real IME. */
class ShowcaseNotesActivity : android.app.Activity() {
    private lateinit var editor: android.widget.EditText
    private lateinit var heading: android.widget.EditText
    private lateinit var root: android.widget.LinearLayout
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 247, 243))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(dp(24) + bars.left, dp(24) + bars.top,
                dp(24) + bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        root.addView(android.widget.TextView(this).apply {
            text = "NOTES"
            textSize = 11f
            letterSpacing = .15f
            setTextColor(Color.rgb(100, 100, 96))
        })
        // Ids let Android restore edits after recreation (rotation, fold, process death).
        heading = android.widget.EditText(this).apply {
            id = R.id.notes_heading
            background = null
            textSize = 24f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(Color.rgb(30, 34, 36))
            setPadding(0, dp(20), 0, dp(12))
        }
        root.addView(heading)
        editor = android.widget.EditText(this).apply {
            id = R.id.notes_body
            textSize = 16f
            setTextColor(Color.rgb(30, 34, 36))
            gravity = android.view.Gravity.TOP
            background = null
            setPadding(0, dp(8), 0, 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(editor, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = true
        applyBackground(intent)
        // A recreated activity keeps the restored edits instead of replaying the capture text.
        if (state == null) update(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        update(intent)
    }
    private fun applyBackground(intent: Intent) {
        val contrast = intent.getBooleanExtra("contrastBackground", false)
        val background = if (contrast) CaptureBackground() else android.graphics.drawable.ColorDrawable(Color.rgb(248, 247, 243))
        window.setBackgroundDrawable(background)
        if (contrast) root.background = null
    }
    private fun update(intent: Intent) {
        applyBackground(intent)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (intent.getBooleanExtra("restoreClipboard", false) && clipboardSaved) {
            val history = rocks.pkb.plektra.clipboard.ClipboardHistory.getInstance(this)
            val retention = rocks.pkb.plektra.clipboard.ClipboardRetention.fromSettings(this)
            history.snapshot.entries.filter { it.id !in originalClipboardIds }.forEach { entry ->
                history.delete(entry.id, force = true, retention = retention)
            }
            val previous = originalClipboard
            if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            clipboardSaved = false
        }
        intent.getStringExtra("clipboard")?.let { text ->
            if (!clipboardSaved) {
                originalClipboard = clipboard.primaryClip
                originalClipboardIds = rocks.pkb.plektra.clipboard.ClipboardHistory.getInstance(this)
                    .snapshot.entries.map { it.id }.toSet()
                clipboardSaved = true
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Notes", text))
        }
        intent.getStringExtra("title")?.let { heading.setText(it) }
        if (intent.hasExtra("text")) editor.setText(intent.getStringExtra("text"))
        val start = intent.getIntExtra("selectionStart", editor.length()).coerceIn(0, editor.length())
        val end = intent.getIntExtra("selectionEnd", start).coerceIn(start, editor.length())
        editor.setSelection(start, end)
        editor.requestFocus()
        editor.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }, 250)
    }
    private class CaptureBackground : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: android.graphics.Canvas) {
            paint.color = Color.WHITE
            paint.shader = android.graphics.LinearGradient(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
                intArrayOf(Color.rgb(209, 226, 237), Color.rgb(220, 210, 189)), null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null
            paint.color = Color.argb(55, 72, 95, 114)
            paint.strokeWidth = 1.5f
            val step = 64f
            var x = 0f
            while (x < bounds.width()) { canvas.drawLine(x, 0f, x, bounds.height().toFloat(), paint); x += step }
            var y = 0f
            while (y < bounds.height()) { canvas.drawLine(0f, y, bounds.width().toFloat(), y, paint); y += step }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }
    companion object {
        private var clipboardSaved = false
        private var originalClipboard: android.content.ClipData? = null
        private var originalClipboardIds = emptySet<Long>()
    }
}
