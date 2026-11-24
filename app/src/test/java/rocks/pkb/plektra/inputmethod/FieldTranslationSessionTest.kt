package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.inputmethod.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class FieldTranslationSessionTest {
 private val info=EditorInfo().apply { packageName="fictional.editor"; inputType=InputType.TYPE_CLASS_TEXT }
 private val input=mock(InputConnection::class.java)
 private var text="🙂 Bonjour, bonjour ! Fin."
 private var start=3
 private var end=10
 private fun connect():FieldTranslationSession? {
  `when`(input.getExtractedText(any(ExtractedTextRequest::class.java),anyInt())).thenAnswer {
   ExtractedText().also{it.text=text;it.startOffset=0;it.partialStartOffset=-1;it.selectionStart=start;it.selectionEnd=end}
  }
  `when`(input.commitText(any(CharSequence::class.java),anyInt())).thenAnswer {
   text=text.replaceRange(minOf(start,end),maxOf(start,end),it.getArgument<CharSequence>(0).toString());true
  }
  return FieldTranslationSession.capture(info,input)
 }
 @Test fun replacesOnlySelectionAndNeverSubmitsOrSendsKeys() {
  val session=connect()!!;assertEquals("Bonjour",session.text)
  assertTrue(session.replace("Hello"));assertEquals("🙂 Hello, bonjour ! Fin.",text)
  verify(input,never()).performEditorAction(anyInt());verify(input,never()).sendKeyEvent(any())
  verify(input,never()).setSelection(anyInt(),anyInt())
  assertFalse(session.replace("again"))
 }
 @Test fun reversedSelectionUsesUtf16Offsets() {start=10;end=3;val session=connect()!!;assertTrue(session.replace("Hello"));assertTrue(text.startsWith("🙂 Hello,"))}
 @Test fun movedCursorBlocksReplacement() {val session=connect()!!;end=start;assertFalse(session.replace("Hello"));verify(input,never()).commitText(any(CharSequence::class.java),anyInt())}
 @Test fun changedDocumentBlocksOldTranslation() {val session=connect()!!;text+=" More";assertFalse(session.replace("Hello"))}
 @Test fun blankOrOverlongResultNeverWrites() {val session=connect()!!;assertFalse(session.replace(" "));assertFalse(session.replace("x".repeat(32001)));verify(input,never()).commitText(any(CharSequence::class.java),anyInt())}
 @Test fun requiresNonemptySelectionWithinLimit() {end=start;assertNull(connect());text="x".repeat(8001);start=0;end=text.length;assertNull(connect());text="   ";start=0;end=3;assertNull(connect())}
 @Test fun passwordsAreRejectedBeforeReading() {info.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;assertNull(FieldTranslationSession.capture(info,input));verifyNoInteractions(input)}
 @Test fun incompleteSnapshotIsRejected() {`when`(input.getExtractedText(any(ExtractedTextRequest::class.java),anyInt())).thenReturn(ExtractedText().apply{this.text="fragment";startOffset=4;selectionStart=0;selectionEnd=2});assertNull(FieldTranslationSession.capture(info,input))}
 @Test fun unavailableInputIsReportedWithoutWriting() {assertNull(FieldTranslationSession.capture(info,input));verify(input,never()).commitText(any(CharSequence::class.java),anyInt())}

}
