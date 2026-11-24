package rocks.pkb.plektra.inputmethod
import android.app.Activity
import android.graphics.Rect
import android.view.*
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class TranslationWindowResizeTest {
 private val bounds=Rect(0,100,1000,900)
 private val initial=Rect(200,200,700,700)
 @Test fun fourHandlesAreRecognizedButTextAndBottomCenterAreNot() {
  assertEquals(TranslationWindowResize.Handle.TOP_LEFT,TranslationWindowResize.hit(10f,10f,500,500,32f))
  assertEquals(TranslationWindowResize.Handle.TOP_RIGHT,TranslationWindowResize.hit(490f,10f,500,500,32f))
  assertEquals(TranslationWindowResize.Handle.BOTTOM_LEFT,TranslationWindowResize.hit(10f,490f,500,500,32f))
  assertEquals(TranslationWindowResize.Handle.BOTTOM_RIGHT,TranslationWindowResize.hit(490f,490f,500,500,32f))
  assertNull(TranslationWindowResize.hit(-1f,10f,500,500,32f))
  assertNull(TranslationWindowResize.hit(501f,10f,500,500,32f))
  assertNull(TranslationWindowResize.hit(250f,250f,500,500,32f));assertNull(TranslationWindowResize.hit(250f,490f,500,500,32f))
 }
 @Test fun bottomRightWideningKeepsOppositeHandleFixed() {
  assertEquals(Rect(200,200,900,600),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.BOTTOM_RIGHT,200,-100,bounds,260,200))
 }
 @Test fun topLeftKeepsBottomRightFixed() {
  assertEquals(Rect(100,150,700,700),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.TOP_LEFT,-100,-50,bounds,260,200))
 }
 @Test fun otherHandlesKeepTheirOppositesFixed() {
  assertEquals(Rect(200,100,900,700),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.TOP_RIGHT,200,-200,bounds,260,200))
  assertEquals(Rect(0,200,700,800),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.BOTTOM_LEFT,-500,100,bounds,260,200))
 }
 @Test fun resizingCannotCrossEdgesOrCollapseControls() {
  assertEquals(Rect(200,200,1000,900),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.BOTTOM_RIGHT,5000,5000,bounds,260,200))
  assertEquals(Rect(200,200,460,400),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.BOTTOM_RIGHT,-5000,-5000,bounds,260,200))
 }
 @Test fun smallAvailableAreaStillProducesValidFrame() {
  assertEquals(Rect(10,20,110,100),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.TOP_LEFT,-5000,-5000,Rect(10,20,110,100),260,200))
 }
 @Test fun controlsInsideHandleRectangleAreNotStolen() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val view=TextView(activity)
  view.setPadding(18,14,18,8);view.layout(0,0,500,500);var calls=0
  val gesture=TranslationWindowResize(view,{Rect(0,0,500,500)},{bounds},{260 to 200},{_,_,_->calls++})
  val event=MotionEvent.obtain(100,100,0,475f,25f,0)
  assertFalse(gesture.touch(event));event.recycle();assertEquals(0,calls)
 }
 @Test fun cornerGestureNeedsMovementAndIgnoresTrackpad() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val view=TextView(activity)
  view.setPadding(18,14,18,8);view.layout(0,0,500,500);var frame:Rect?=null
  val gesture=TranslationWindowResize(view,{initial},{bounds},{260 to 200},{r,_,_->frame=r})
  fun touch(action:Int,x:Float,y:Float,source:Int=InputDevice.SOURCE_TOUCHSCREEN):Boolean {
   val e=MotionEvent.obtain(100,100,action,x,y,0);e.source=source;return gesture.touch(e).also{e.recycle()}
  }
  assertFalse(touch(0,490f,490f,InputDevice.SOURCE_TOUCHPAD))
  assertTrue(touch(0,490f,490f));assertNull(frame)
  assertTrue(touch(2,650f,550f));assertEquals(Rect(200,200,860,760),frame)
  touch(1,650f,550f)
 }
 @Test fun sidesResizeOneAxisAndLeaveTheOtherUnchanged() {
  assertEquals(Rect(100,200,700,700),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.LEFT,-100,500,bounds,260,200))
  assertEquals(Rect(200,200,800,700),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.RIGHT,100,-500,bounds,260,200))
  assertEquals(Rect(200,150,700,700),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.TOP,400,-50,bounds,260,200))
  assertEquals(Rect(200,200,700,800),TranslationWindowResize.frame(initial,TranslationWindowResize.Handle.BOTTOM,-400,100,bounds,260,200))
  assertEquals(TranslationWindowResize.Handle.LEFT,TranslationWindowResize.hit(10f,250f,500,500,32f))
  assertEquals(TranslationWindowResize.Handle.TOP,TranslationWindowResize.hit(250f,10f,500,500,32f))
  assertEquals(TranslationWindowResize.Handle.BOTTOM,TranslationWindowResize.hit(100f,490f,500,500,32f))
  assertNull(TranslationWindowResize.hit(250f,490f,500,500,32f))
 }
}
