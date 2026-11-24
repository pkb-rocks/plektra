package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.*
import android.widget.*
import rocks.pkb.plektra.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class FieldTranslationPanelTest {
 private class Fake : TranslationEngine {
  var installed=false;var downloads=0;var wifi=false;var closed=false
  var pending:((String)->Unit)?=null;var fail:(()->Unit)?=null;var received:String?=null
  override fun available(done:(Boolean)->Unit,failed:()->Unit){done(installed)}
  override fun download(wifiOnly:Boolean,done:()->Unit,failed:()->Unit){downloads++;wifi=wifiOnly;installed=true;done()}
  override fun translate(text:String,done:(String)->Unit,failed:()->Unit){received=text;pending=done;fail=failed}
  override fun close(){closed=true}
 }
 private val input=mock(InputConnection::class.java)
 private var text="Bonjour";private var end=7;private var focused=true
 private lateinit var host:LinearLayout;private lateinit var panel:FieldTranslationPanel
 private val fake=Fake()
 private fun start() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
  activity.getSharedPreferences("translation_preferences",0).edit().clear().commit()
  host=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL};activity.setContentView(host)
  `when`(input.getExtractedText(any(ExtractedTextRequest::class.java),anyInt())).thenAnswer {
   ExtractedText().also{it.text=text;it.startOffset=0;it.partialStartOffset=-1;it.selectionStart=0;it.selectionEnd=end}
  }
  `when`(input.commitText(any(CharSequence::class.java),anyInt())).thenAnswer{text=it.getArgument<CharSequence>(0).toString();true}
  val info=EditorInfo().apply{packageName="fictional.editor";inputType=InputType.TYPE_CLASS_TEXT}
  val session=FieldTranslationSession.capture(info,input)!!
  panel=FieldTranslationPanel(activity,host,session,Locale.FRENCH,{focused},{},{_,_->fake})
  panel.start();shadowOf(Looper.getMainLooper()).idle()
 }
 private fun view()=panel.contentView
 private fun choose()=view().findViewById<TextView>(R.id.field_translation_english).performClick()
 private fun download()=view().findViewById<Button>(R.id.field_translation_run).performClick()
 private fun accept()=view().findViewById<Button>(R.id.field_translation_replace).performClick()
 private fun hidden()=org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing==false
 @Test fun downloadRequiresExplicitConsentAndDefaultsToWifi() {
  start();assertNull(fake.received);choose();assertEquals(0,fake.downloads)
  download();assertEquals(1,fake.downloads);assertTrue(fake.wifi);assertEquals("Bonjour",fake.received)
  fake.pending!!("Hello");assertEquals("Bonjour",text)
  accept();assertEquals("Hello",text);assertTrue(hidden())
  verify(input,never()).performEditorAction(anyInt());verify(input,never()).sendKeyEvent(any())
 }
 @Test fun installedLanguagesNeedNoDownloadAndPreviewDoesNotWrite() {
  fake.installed=true;start();choose();assertEquals(0,fake.downloads);fake.pending!!("Hello")
  assertEquals("Hello",view().findViewById<TextView>(R.id.field_translation_preview).text.toString())
  assertEquals("Bonjour",text);verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
 @Test fun correctingAndAcceptingInsertsTheUsersRevisionOnly() {
  fake.installed=true;start();choose();fake.pending!!("Hello")
  view().findViewById<Button>(R.id.field_translation_edit).performClick()
  view().findViewById<EditText>(R.id.field_translation_editor).setText("Good morning!")
  assertEquals("Bonjour",text);accept();assertEquals("Good morning!",text)
 }
 @Test fun cancellingEditsRestoresTheGeneratedProposalWithoutWriting() {
  fake.installed=true;start();choose();fake.pending!!("Hello")
  view().findViewById<Button>(R.id.field_translation_edit).performClick()
  view().findViewById<EditText>(R.id.field_translation_editor).setText("other")
  view().findViewById<Button>(R.id.field_translation_edit).performClick()
  assertEquals("Hello",view().findViewById<TextView>(R.id.field_translation_preview).text.toString())
  assertEquals("Bonjour",text)
 }
 @Test fun cancellingIgnoresLateResultsAndDoesNotWrite() {
  fake.installed=true;start();choose();panel.close();fake.pending!!("Hello");assertTrue(fake.closed);assertTrue(hidden())
  verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
 @Test fun editedSelectionCannotBeReplacedAfterPreview() {
  fake.installed=true;start();choose();fake.pending!!("Hello");end=0;accept()
  assertEquals("Bonjour",text);assertTrue(hidden());verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
 @Test fun switchingFieldsIgnoresTranslationResult() {
  fake.installed=true;start();choose();focused=false;fake.pending!!("Hello");assertTrue(hidden())
  verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
 @Test fun engineFailureKeepsOriginalAndAllowsRetry() {
  fake.installed=true;start();choose();fake.fail!!();assertEquals("Bonjour",text)
  assertTrue(view().findViewById<Button>(R.id.field_translation_run).isEnabled)
 }
 @Test fun mobileDataIsUsedOnlyAfterTheUserChecksIt() {
  start();choose();view().findViewById<CheckBox>(R.id.field_translation_mobile).isChecked=true
  download();assertFalse(fake.wifi);assertEquals(1,fake.downloads)
 }
 @Test fun otherLanguagesAreOpenedOnlyOnRequest() {
  start();assertNotNull(view().findViewById<TextView>(R.id.field_translation_other))
  view().findViewById<TextView>(R.id.field_translation_other).performClick()
  assertNull(view().findViewById<TextView>(R.id.field_translation_other));assertNull(fake.received)
 }
 @Test fun verticalTrackpadMovesHighlightAndDoubleTapChooses() {
  start()
  val metrics=view().resources.displayMetrics
  fun contact(action:Int,y:Float,time:Long) {
   val event=android.view.MotionEvent.obtain(time,time,action,.4f*metrics.widthPixels,y*metrics.heightPixels,0)
   event.source=android.view.InputDevice.SOURCE_TOUCHPAD
   assertTrue(panel.motion(event));event.recycle()
  }
  fun english()=view().findViewById<TextView>(R.id.field_translation_english)
  assertTrue(english().isSelected)
  contact(0,.4f,100);contact(2,.51f,150);contact(1,.51f,180);assertFalse(english().isSelected)
  contact(0,.6f,400);contact(2,.49f,450);contact(1,.49f,480);assertTrue(english().isSelected)
  contact(0,.5f,1000);contact(1,.5f,1040);contact(0,.5f,1100);contact(1,.5f,1140)
  assertNotNull(view().findViewById<CheckBox>(R.id.field_translation_mobile))
  assertEquals(0,fake.downloads);verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
 @Test fun ordinaryScreenTouchesAreNotMistakenForKeyboardSwipes() {
  start();val event=android.view.MotionEvent.obtain(100,100,0,20f,20f,0)
  event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertFalse(panel.motion(event));event.recycle()
  assertTrue(view().findViewById<TextView>(R.id.field_translation_english).isSelected)
 }

 @Test fun physicalKeyRoutedToCorrectionDoesNotTouchOriginal() {
  fake.installed=true;start();choose();fake.pending!!("Hello")
  view().findViewById<Button>(R.id.field_translation_edit).performClick()
  panel.key(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_L))
  panel.key(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_L))
  assertEquals("Hellol",view().findViewById<EditText>(R.id.field_translation_editor).text.toString())
  assertEquals("Bonjour",text);verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }

 @Test fun ctrlAndDeletionOnlyChangeTheProposalUntilAccept() {
  fake.installed=true;start();choose();fake.pending!!("Hello world")
  view().findViewById<Button>(R.id.field_translation_edit).performClick()
  fun key(code:Int,action:Int=0){panel.key(android.view.KeyEvent(action,code))}
  key(android.view.KeyEvent.KEYCODE_CTRL_LEFT);key(android.view.KeyEvent.KEYCODE_CTRL_LEFT,1)
  key(android.view.KeyEvent.KEYCODE_A);key(android.view.KeyEvent.KEYCODE_A,1)
  val edit=view().findViewById<EditText>(R.id.field_translation_editor)
  assertEquals(0,edit.selectionStart);assertEquals(edit.length(),edit.selectionEnd)
  rocks.pkb.plektra.SettingsManager.setSwipeToDelete(edit.context,true)
  rocks.pkb.plektra.SettingsManager.setTrackpadDeleteSwipeThreshold(edit.context,120f)
  for((action,x,time) in listOf(Triple(0,600f,100L),Triple(1,300f,180L))) {
   val event=android.view.MotionEvent.obtain(100,time,action,x,100f,0)
   event.source=android.view.InputDevice.SOURCE_TOUCHPAD;assertTrue(panel.motion(event));event.recycle()
  }
  assertEquals("",edit.text.toString());assertEquals("Bonjour",text)
  verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
 @Test fun resizingReflowsWithoutScalingTextOrAttributionOrChangingOriginal() {
  fake.installed=true;start();choose();fake.pending!!("A quiet river crosses the green valley. ".repeat(6))
  val preview=view().findViewById<TextView>(R.id.field_translation_preview)
  val badge=view().findViewById<ImageView>(R.id.field_translation_attribution)
  val before=preview.text.toString();val size=preview.textSize
  val badgeWidth=badge.layoutParams.width;val badgeHeight=badge.layoutParams.height
  panel.resizeWindow(android.graphics.Rect(0,100,300,700),TranslationWindowResize.Handle.BOTTOM_RIGHT,false)
  shadowOf(Looper.getMainLooper()).idle()
  assertEquals(before,preview.text.toString());assertEquals(size,preview.textSize,0f)
  assertEquals(1f,preview.scaleX,0f);assertEquals(1f,preview.scaleY,0f)
  assertEquals(badgeWidth,badge.layoutParams.width);assertEquals(badgeHeight,badge.layoutParams.height)
  assertEquals(1f,badge.scaleX,0f);assertEquals(1f,badge.scaleY,0f)
  assertNotNull(view().findViewById<FrameLayout>(R.id.field_translation_move_handle))
  assertEquals("Bonjour",text);verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
 @Test fun heightOnlyResizeInvalidatesTheBodyMeasurementCache() {
  fake.installed=true;start();choose();fake.pending!!("A quiet river crosses the green valley. ".repeat(4))
  val preview=view().findViewById<TextView>(R.id.field_translation_preview)
  val body=preview.parent as ScrollView
  body.measure(android.view.View.MeasureSpec.makeMeasureSpec(300,android.view.View.MeasureSpec.EXACTLY),
    android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.AT_MOST))
  body.layout(0,0,300,body.measuredHeight)
  assertFalse(body.isLayoutRequested)
  panel.resizeWindow(android.graphics.Rect(0,100,300,400),TranslationWindowResize.Handle.BOTTOM,true)
  assertTrue(body.isLayoutRequested)
  assertEquals("Bonjour",text)
 }
 @Test fun physicalPrefixFiltersAllLanguagesAndEnterChoosesWithoutWritingSource() {
  val previous=rocks.pkb.plektra.data.layout.LayoutMappingRepository.getLayout()
  rocks.pkb.plektra.data.layout.LayoutMappingRepository.setLayout(emptyMap())
  try {
   fake.installed=true;start()
   for(code in listOf(android.view.KeyEvent.KEYCODE_E,android.view.KeyEvent.KEYCODE_N,android.view.KeyEvent.KEYCODE_G)) {
    panel.key(android.view.KeyEvent(0,code));panel.key(android.view.KeyEvent(1,code))
   }
   assertEquals("eng",view().findViewById<EditText>(R.id.field_translation_search).text.toString())
   assertTrue(view().findViewById<TextView>(R.id.field_translation_english).isSelected)
   assertEquals(1,view().findViewById<LinearLayout>(R.id.field_translation_language_list).childCount)
   assertEquals("Bonjour",text);assertNull(fake.received)
   panel.key(android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DEL))
   assertEquals("en",view().findViewById<EditText>(R.id.field_translation_search).text.toString())
   panel.key(android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_ENTER))
   assertEquals("Bonjour",fake.received);assertEquals("Bonjour",text)
   verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
  } finally { rocks.pkb.plektra.data.layout.LayoutMappingRepository.setLayout(previous) }
 }
 @Test fun noMatchesDoesNotAccidentallyChooseOtherLanguagesOrStartTranslation() {
  start();val search=view().findViewById<EditText>(R.id.field_translation_search)
  search.setText("zzzzzz");assertNotNull(view().findViewById<TextView>(R.id.field_translation_search_empty))
  panel.key(android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_ENTER))
  assertSame(search,view().findViewById<EditText>(R.id.field_translation_search));assertNull(fake.received)
  assertEquals("Bonjour",text)
 }
 @Test fun clearingThePrefixRestoresChoicesWithoutRebuildingTheSearchField() {
  start();val search=view().findViewById<EditText>(R.id.field_translation_search)
  search.setText("eng");assertNull(view().findViewById<TextView>(R.id.field_translation_other))
  view().findViewById<ImageButton>(R.id.field_translation_search_clear).performClick()
  assertSame(search,view().findViewById<EditText>(R.id.field_translation_search))
  assertEquals("",search.text.toString());assertNotNull(view().findViewById<TextView>(R.id.field_translation_other))
 }
 @Test fun sourceLanguageCanAlsoBeFilteredAndChangingItClearsTheOldPrefix() {
  start();view().findViewById<TextView>(R.id.field_translation_source).performClick()
  view().findViewById<EditText>(R.id.field_translation_search).setText("eng")
  val list=view().findViewById<LinearLayout>(R.id.field_translation_language_list)
  assertEquals(1,list.childCount);list.getChildAt(0).performClick()
  assertEquals("",view().findViewById<EditText>(R.id.field_translation_search).text.toString())
  assertNull(view().findViewById<TextView>(R.id.field_translation_english))
  view().findViewById<EditText>(R.id.field_translation_search).setText("fr")
  assertTrue(view().findViewById<LinearLayout>(R.id.field_translation_language_list).childCount>0)
  assertNull(fake.received);assertEquals("Bonjour",text)
 }
 @Test fun highlightedRowsBeyondBengaliScrollIntoViewAndBackToTheTop() {
  start();view().findViewById<TextView>(R.id.field_translation_other).performClick()
  val root=view();root.measure(android.view.View.MeasureSpec.makeMeasureSpec(300,android.view.View.MeasureSpec.EXACTLY),
    android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.AT_MOST))
  root.layout(0,0,300,root.measuredHeight);shadowOf(Looper.getMainLooper()).idle()
  panel.moveChoice(15);shadowOf(Looper.getMainLooper()).idle()
  val viewport=root.findViewById<ScrollView>(R.id.field_translation_languages_scroll)
  val list=root.findViewById<LinearLayout>(R.id.field_translation_language_list)
  val selected=list.getChildAt(15)
  assertTrue(selected.isSelected);assertTrue(viewport.scrollY>0);assertEquals(0,viewport.scrollX)
  assertTrue(selected.top>=viewport.scrollY);assertTrue(selected.bottom<=viewport.scrollY+viewport.height)
  panel.moveChoice(-1000);shadowOf(Looper.getMainLooper()).idle();assertEquals(0,viewport.scrollY)
 }
 @Test fun prefixUsesTheActiveAzertyMappingRatherThanRawPhysicalLetter() {
  val previous=rocks.pkb.plektra.data.layout.LayoutMappingRepository.getLayout()
  try {
   rocks.pkb.plektra.data.layout.LayoutMappingRepository.setLayout(mapOf(
    android.view.KeyEvent.KEYCODE_Q to rocks.pkb.plektra.data.layout.LayoutMapping("a","A")))
   start();panel.key(android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_Q))
   assertEquals("a",view().findViewById<EditText>(R.id.field_translation_search).text.toString())
   assertEquals("Bonjour",text)
  } finally { rocks.pkb.plektra.data.layout.LayoutMappingRepository.setLayout(previous) }
 }
 @Test fun doubleTapChoosesTheFilteredLanguageWithoutChangingTheSourceField() {
  fake.installed=true;start();view().findViewById<EditText>(R.id.field_translation_search).setText("eng")
  val metrics=view().resources.displayMetrics
  for((action,time) in listOf(0 to 1000L,1 to 1040L,0 to 1100L,1 to 1140L)) {
   val event=android.view.MotionEvent.obtain(1000,time,action,.4f*metrics.widthPixels,.5f*metrics.heightPixels,0)
   event.source=android.view.InputDevice.SOURCE_TOUCHPAD
   assertTrue(panel.motion(event));event.recycle()
  }
  assertEquals("Bonjour",fake.received);assertEquals("Bonjour",text)
  verify(input,never()).commitText(any(CharSequence::class.java),anyInt())
 }
}
