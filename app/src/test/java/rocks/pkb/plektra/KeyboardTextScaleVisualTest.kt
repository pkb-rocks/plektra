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

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33],qualifiers="fr")
class KeyboardTextScaleVisualTest {
 private val context=RuntimeEnvironment.getApplication()
 @Before fun clear(){SettingsManager.getPreferences(context).edit().clear().commit()}
 private fun render(root:View) {
  repeat(4) {
   root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.AT_MOST))
   root.layout(0,0,root.measuredWidth,root.measuredHeight)
   root.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(root.measuredWidth,root.measuredHeight,android.graphics.Bitmap.Config.ARGB_8888)))
  }
 }
 private fun labels(root:View,words:List<String>):List<TextView> = buildList {
  fun visit(v:View){if(v is TextView && v.text.toString() in words)add(v);if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))};visit(root)
 }
 // Actual glyph-size ratios are asserted by the native Android probe. Robolectric's legacy
 // graphics do not reproduce TextView autosizing; here we test preview wiring and persistence.
 @Test fun previewChangesOnlyTheSelectedRowAndNeverSavesWhileDragging() {
  KeyboardBarTypography.setScale(context,1.10f)
  val root=KeyboardTextScalePreview(context);root.setScale(1.25f);render(root)
  val words=context.getString(R.string.keyboard_text_size_preview_words).split("|")
  val views=labels(root,words);assertEquals(6,views.size)
  for(word in words){val pair=views.filter{it.text.toString()==word};assertEquals(2,pair.size);assertTrue(TextViewCompat.getAutoSizeMaxTextSize(pair[1])>TextViewCompat.getAutoSizeMaxTextSize(pair[0]))}
  assertEquals(1.10f,KeyboardBarTypography.scale(context),0f)
 }
 @Test fun exactPixelCeilingIncludesTheRequestedEndpointAndPreservesTheFittingFloor() {
  val v=TextView(context);KeyboardBarTypography.applyAutoSize(v,7,14,1f)
  val minimum=TextViewCompat.getAutoSizeMinTextSize(v);val baseline=TextViewCompat.getAutoSizeMaxTextSize(v)
  KeyboardBarTypography.applyAutoSize(v,7,14,1.25f)
  val max=TextViewCompat.getAutoSizeMaxTextSize(v)
  assertEquals(minimum,TextViewCompat.getAutoSizeMinTextSize(v))
  assertEquals(kotlin.math.round(baseline*1.25f).toInt(),max)
  assertTrue(TextViewCompat.getAutoSizeTextAvailableSizes(v).contains(max))
 }
}
