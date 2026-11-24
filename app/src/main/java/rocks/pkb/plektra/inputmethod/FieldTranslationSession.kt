package rocks.pkb.plektra.inputmethod

import android.view.inputmethod.*

/** A selected-range transaction. No keys, send actions, or network access. */
internal class FieldTranslationSession private constructor(val input: InputConnection, val info: EditorInfo,
    private val original: Snapshot) {
    val text = original.text.substring(minOf(original.start, original.end), maxOf(original.start, original.end))
    private var consumed = false
    fun unchanged(): Boolean = !consumed && read(input) == original
    fun replace(value: String): Boolean {
        if (value.isBlank() || value.length > 32000 || !unchanged()) return false
        consumed = true
        input.beginBatchEdit()
        return try { input.commitText(value, 1) } finally { input.endBatchEdit() }
    }
    private data class Snapshot(val text:String,val start:Int,val end:Int)
    companion object {
        private fun eligible(info:EditorInfo?):Boolean {
            if(info==null||info.packageName.isNullOrBlank()||info.inputType and android.text.InputType.TYPE_MASK_CLASS!=android.text.InputType.TYPE_CLASS_TEXT)return false
            return info.inputType and android.text.InputType.TYPE_MASK_VARIATION !in setOf(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        }
        private fun parse(value:ExtractedText?):Snapshot? {
            if(value==null||value.startOffset!=0||value.partialStartOffset != -1)return null
            val text=value.text?.toString() ?: return null
            if(text.length>210000||value.selectionStart !in 0..text.length||value.selectionEnd !in 0..text.length)return null
            return Snapshot(text,value.selectionStart,value.selectionEnd)
        }
        const val MAX_SELECTION = 8000
        private fun read(input: InputConnection) = try {
            parse(input.getExtractedText(ExtractedTextRequest().apply {
                hintMaxChars = 210001; hintMaxLines = 50000
            }, 0))
        } catch (_: RuntimeException) { null }
        fun capture(info: EditorInfo?, input: InputConnection): FieldTranslationSession? {
            if (!eligible(info)) return null
            input.finishComposingText()
            val value = read(input) ?: return null
            val count = kotlin.math.abs(value.end - value.start)
            if (count !in 1..MAX_SELECTION) return null
            return FieldTranslationSession(input, info!!, value).takeIf { it.text.isNotBlank() }
        }
    }
}
