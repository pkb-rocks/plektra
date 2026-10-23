package rocks.pkb.plektra

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class KeyboardBarTypographyTest {
 private val context=RuntimeEnvironment.getApplication()
 @Before fun setup() {SettingsManager.getPreferences(context).edit().clear().commit()}
 private fun labels(root:View,words:Set<String>):List<TextView> = buildList {
  fun walk(view:View) {
   if(view is TextView && view.text.toString() in words)add(view)
   if(view is ViewGroup)for(index in 0 until view.childCount)walk(view.getChildAt(index))
  }
  walk(root)
 }
 @Test fun preferenceHasModestBoundsAndRejectsNonFiniteOrMalformedImportedValues() {
  assertEquals(1f,KeyboardBarTypography.scale(context),0f)
  KeyboardBarTypography.setScale(context,.1f);assertEquals(.85f,KeyboardBarTypography.scale(context),0f)
  KeyboardBarTypography.setScale(context,4f);assertEquals(1.25f,KeyboardBarTypography.scale(context),0f)
  for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
   KeyboardBarTypography.setScale(context,value);assertEquals(1f,KeyboardBarTypography.scale(context),0f)
  }
  SettingsManager.getPreferences(context).edit().putString(KeyboardBarTypography.KEY,"bad").commit()
  assertEquals(1f,KeyboardBarTypography.scale(context),0f)
 }
 @Test fun unchangedCandidatesRefreshAfterASizeChangeAndKeepTheirOriginalFittingMinimum() {
  val bar=FullSuggestionsBar(context);val root=bar.ensureView()
  val words=listOf("Bonjour","c\u2019est pourquoi","\u00e0 tout \u00e0 l\u2019heure")
  fun render()=bar.update(words,true,null,null,false,null,null,null,null,null,null,null)
  render()
  val original=labels(root,words.toSet());assertEquals(3,original.size)
  val lower=original.map {TextViewCompat.getAutoSizeMinTextSize(it)}
  val upper=original.map {TextViewCompat.getAutoSizeMaxTextSize(it)}
  KeyboardBarTypography.setScale(context,1.25f);render()
  val enlarged=labels(root,words.toSet());assertEquals(original.map {it.text.toString()},enlarged.map {it.text.toString()})
  assertEquals(lower,enlarged.map {TextViewCompat.getAutoSizeMinTextSize(it)})
  enlarged.forEachIndexed {index,view->assertTrue(TextViewCompat.getAutoSizeMaxTextSize(view)>upper[index])}
  KeyboardBarTypography.setScale(context,.85f);render()
  assertEquals(lower,labels(root,words.toSet()).map {TextViewCompat.getAutoSizeMinTextSize(it)})
 }
 @Test fun enlargedMeasuredSuggestionsKeepWholeWordsAndThreeWordGroups() {
  KeyboardBarTypography.setScale(context,1.25f)
  val words=listOf("anticonstitutionnellement","c\u2019est pourquoi","\u00e0 tout \u00e0 l\u2019heure")
  val bar=FullSuggestionsBar(context);bar.showPreview(words);val root=bar.ensureView()
  // Rounded surface geometry adjusts text padding on the first layout, scheduling another pass.
  repeat(3) {
   root.measure(View.MeasureSpec.makeMeasureSpec(660,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.AT_MOST))
   root.layout(0,0,root.measuredWidth,root.measuredHeight)
  }
  // TextView can rebuild its shaped layout lazily on draw after optical padding changes.
  root.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(root.measuredWidth,root.measuredHeight,android.graphics.Bitmap.Config.ARGB_8888)))
  val candidates=labels(root,words.toSet());assertEquals(3,candidates.size)
  candidates.forEach {view->
   val layout=requireNotNull(view.layout) {"Missing layout: ${view.text}, size ${view.width}x${view.height}, pending ${view.isLayoutRequested}"}
   assertEquals("Candidate was shortened: ${view.text}",0,layout.getEllipsisCount(0))
   assertEquals(view.text.length,layout.getLineEnd(0))
  }
 }
 @Test fun unchangedVariationsRefreshTheirSizeWithoutChangingTheirContent() {
  val words=listOf("Å“", "!", "?")
  val bar=rocks.pkb.plektra.inputmethod.ui.VariationBarView(context, buttonRegistry=rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry())
  bar.forceVariationAreaVisible=true
  val root=bar.ensureView()
  val snapshot=rocks.pkb.plektra.inputmethod.StatusBarController.StatusSnapshot(
   capsLockEnabled=false,shiftPhysicallyPressed=false,shiftOneShot=false,
   ctrlLatchActive=false,ctrlPhysicallyPressed=false,ctrlOneShot=false,ctrlLatchFromNavMode=false,
   altLatchActive=false,altPhysicallyPressed=false,altOneShot=false,symPage=0,variations=words)
  bar.showVariations(snapshot,null)
  val before=labels(root,words.toSet());assertEquals(3,before.size)
  val minimum=before.map {TextViewCompat.getAutoSizeMinTextSize(it)}
  val maximum=before.map {TextViewCompat.getAutoSizeMaxTextSize(it)}
  KeyboardBarTypography.setScale(context,1.25f)
  bar.showVariations(snapshot,null)
  val after=labels(root,words.toSet());assertEquals(words,after.map {it.text.toString()})
  assertEquals(minimum,after.map {TextViewCompat.getAutoSizeMinTextSize(it)})
  after.forEachIndexed {i,v->assertTrue(TextViewCompat.getAutoSizeMaxTextSize(v)>maximum[i])}
 }
}
