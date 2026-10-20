package rocks.pkb.plektra

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager

/** A native text editor with synthetic content; the keyboard remains the real IME. */
class ShowcaseNotesActivity : NotesActivity() {
    override val notesPreferencesName: String = "showcase_notes"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (state == null) update(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        update(intent)
    }
    private fun update(intent: Intent) {
        val contrast = intent.getBooleanExtra("contrastBackground", false)
        val background = if (contrast) CaptureBackground() else android.graphics.drawable.ColorDrawable(Color.rgb(248, 247, 243))
        window.setBackgroundDrawable(background)
        if (contrast) root.background = null
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (intent.getBooleanExtra("restoreClipboard", false) && clipboardSaved) {
            val dao = requireNotNull(rocks.pkb.plektra.clipboard.ClipboardDao.getInstance(this))
            (dao.count() - 1 downTo 0).forEach { index ->
                dao.getAt(index)?.takeIf { it.id !in originalClipboardIds }?.let { dao.deleteClip(it.id) }
            }
            val previous = originalClipboard
            if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            clipboardSaved = false
        }
        intent.getStringExtra("clipboard")?.let { text ->
            if (!clipboardSaved) {
                originalClipboard = clipboard.primaryClip
                val dao = requireNotNull(rocks.pkb.plektra.clipboard.ClipboardDao.getInstance(this))
                originalClipboardIds = (0 until dao.count()).mapNotNull { dao.getAt(it)?.id }.toSet()
                clipboardSaved = true
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Notes", text))
        }
        findViewById<android.view.View>(R.id.notes_markdown_toggle).performClick()
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
