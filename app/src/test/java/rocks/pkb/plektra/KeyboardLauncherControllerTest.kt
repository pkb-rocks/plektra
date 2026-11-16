package rocks.pkb.plektra
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class KeyboardLauncherControllerTest {
 private fun event(action:Int,key:Int,down:Long=SystemClock.uptimeMillis(),time:Long=SystemClock.uptimeMillis(),repeat:Int=0)=KeyEvent(down,time,action,key,repeat,0,7,0)
 private fun advance(ms:Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
 private data class Harness(val controller:KeyboardLauncherController,val ordinary:MutableList<KeyEvent>,val actions:MutableList<LauncherMapping>)
 private fun make(map:Map<Int,LauncherMapping>):Harness {
  val ordinary=mutableListOf<KeyEvent>();val actions=mutableListOf<LauncherMapping>()
  return Harness(KeyboardLauncherController({KeyEvent.KEYCODE_SHIFT_LEFT},{400},{map},{ordinary.add(it)},{actions.add(it)}),ordinary,actions)
 }
 private fun activate(h:Harness) {
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT))
  val down=SystemClock.uptimeMillis();assertTrue(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT,down)))
  advance(450);assertTrue(h.controller.active)
 }
 @Test fun assignedShiftTapNeverProducesCapitalizationOrArmsLayer() {
  val h=make(emptyMap());val down=SystemClock.uptimeMillis()
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT,down));advance(70)
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT,down))
  assertTrue(h.ordinary.isEmpty());assertFalse(h.controller.active)
 }

 @Test fun assignedShiftChordNeverReplaysShiftIntoTheEditor() {
  val h=make(emptyMap());val down=SystemClock.uptimeMillis()
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT,down));advance(30)
  assertTrue(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)))
  assertTrue(h.ordinary.isEmpty())
  advance(600);assertTrue(h.controller.active)
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT,down))
  assertTrue(h.ordinary.isEmpty())
 }

 @Test fun heldTriggerWaitsWithoutExpiryAndReleaseReturnsToTyping() {
  val value=LauncherMapping("app.test")
  val h=make(mapOf(KeyEvent.KEYCODE_A to value));activate(h);advance(8000)
  assertTrue(h.controller.active);assertTrue(h.ordinary.isEmpty())
  assertTrue(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)));assertEquals(listOf(value),h.actions)
  assertTrue(h.controller.active);assertTrue(h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A)))
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT));assertFalse(h.controller.active)
  assertFalse(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_B)))
 }

 @Test fun shortActionRunsOnReleaseAndLongActionOnlyOnceAtThreshold() {
  val short=LauncherMapping("short");val long=LauncherMapping("long")
  val h=make(mapOf(KeyEvent.KEYCODE_A to short.copy(longPress=long)))
  activate(h);var down=SystemClock.uptimeMillis()
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,down));advance(80);assertTrue(h.actions.isEmpty())
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A,down));assertEquals(listOf(short),h.actions)
  activate(h);down=SystemClock.uptimeMillis();h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,down));advance(450)
  assertEquals(listOf(short,long),h.actions)
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,down,repeat=1));h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A,down))
  assertEquals(2,h.actions.size)
 }

 @Test fun switchingChoiceCancelsThePreviousDeferredAction() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("a",longPress=LauncherMapping("long-a")),KeyEvent.KEYCODE_B to LauncherMapping("b")))
  activate(h);h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A));advance(40)
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_B));advance(700)
  assertEquals(listOf("b"),h.actions.map {it.value})
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A));assertEquals(1,h.actions.size)
 }

 @Test fun subsequentShortcutWorksWithoutAnyEditorOrNavState() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("conversation-a"),KeyEvent.KEYCODE_B to LauncherMapping("conversation-b")))
  activate(h);h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A));h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A))
  activate(h);h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_B));h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_B))
  assertEquals(listOf("conversation-a","conversation-b"),h.actions.map {it.value});assertTrue(h.ordinary.isEmpty())
 }

 @Test fun escapeAndCancellationNeverLaunchAnAction() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("a",longPress=LauncherMapping("long"))))
  activate(h);h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A));h.controller.cancel();advance(1000)
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A));assertTrue(h.actions.isEmpty())
  activate(h);h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ESCAPE));assertFalse(h.controller.active)
 }

 @Test fun alternateFnTriggerRetainsItsOrdinaryShortPressAndArmingFeedbackRunsOnce() {
  val ordinary=mutableListOf<KeyEvent>();var confirmations=0
  val controller=KeyboardLauncherController({KeyEvent.KEYCODE_CTRL_LEFT},{400},{emptyMap()},{ordinary.add(it)},{},{confirmations++})
  var down=SystemClock.uptimeMillis()
  controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,down));advance(50)
  controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT,down))
  assertEquals(listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP),ordinary.map {it.action});assertEquals(0,confirmations)
  down=SystemClock.uptimeMillis();controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,down));advance(500)
  controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,down,repeat=1));advance(500)
  controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT,down));assertEquals(1,confirmations)
 }

 @Test fun releasingTriggerCancelsThePendingLetterAndSwallowsItsRelease() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("short",longPress=LauncherMapping("long"))))
  activate(h);val down=SystemClock.uptimeMillis()
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,down));advance(80)
  h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT));advance(900)
  assertFalse(h.controller.active);assertTrue(h.actions.isEmpty())
  assertTrue(h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A,down)))
  assertTrue(h.actions.isEmpty());assertTrue(h.ordinary.isEmpty())
 }

 @Test fun releasingTriggerWithoutChoiceNeverLeavesAnArmedLayer() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("a")))
  activate(h);h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT))
  advance(8000);assertFalse(h.controller.active)
  assertFalse(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)))
  assertTrue(h.actions.isEmpty())
 }

 @Test fun dedicatedShiftChordRunsImmediatelyAndRepeatDoesNotRelaunch() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("a")))
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT));advance(20)
  val down=SystemClock.uptimeMillis()
  assertTrue(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,down)))
  assertEquals(listOf("a"),h.actions.map {it.value})
  h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,down,repeat=1));advance(800)
  assertEquals(1,h.actions.size);assertTrue(h.ordinary.isEmpty())
 }

 @Test fun otherShiftRemainsAvailableForOrdinaryCapitalization() {
  val h=make(emptyMap())
  assertFalse(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_RIGHT)))
  assertFalse(h.controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_RIGHT)))
 }

 @Test fun fnDoubleTapStillReachesTheRealNavModifierState() {
  val state=rocks.pkb.plektra.core.ModifierStateController(300)
  val controller=KeyboardLauncherController({KeyEvent.KEYCODE_CTRL_LEFT},{400},{emptyMap()}, { e ->
   if(e.action==KeyEvent.ACTION_DOWN)state.handleCtrlKeyDown(e.keyCode,true)
   else state.handleCtrlKeyUp(e.keyCode)
  },{})
  repeat(2) {
   val down=SystemClock.uptimeMillis()
   controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,down));advance(40)
   controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT,down));advance(50)
  }
  assertTrue(state.ctrlLatchActive);assertFalse(controller.active)
 }

 @Test fun quickFnChordPassesThroughButHeldFnUsesShortcutUntilRelease() {
  val ordinary=mutableListOf<KeyEvent>();val actions=mutableListOf<String>();var feedback=0
  val controller=KeyboardLauncherController({KeyEvent.KEYCODE_CTRL_LEFT},{400},
   {mapOf(KeyEvent.KEYCODE_A to LauncherMapping("a"))},{ordinary.add(it)},{actions.add(it.value)},{feedback++})
  controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT));advance(30)
  assertFalse(controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)))
  controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT))
  assertEquals(listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP),ordinary.map {it.action})
  ordinary.clear()
  controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT));advance(450)
  assertTrue(controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)))
  controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A))
  assertEquals(listOf("a"),actions);assertEquals(1,feedback);assertTrue(controller.active)
  controller.handle(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT))
  assertFalse(controller.active);assertTrue(ordinary.isEmpty())
 }

 @Test fun delayedTimerUsesFnHoldTimestampWithoutReplayingFn() {
  val ordinary=mutableListOf<KeyEvent>();val actions=mutableListOf<String>()
  val controller=KeyboardLauncherController({KeyEvent.KEYCODE_CTRL_LEFT},{400},
   {mapOf(KeyEvent.KEYCODE_A to LauncherMapping("a"))},{ordinary.add(it)},{actions.add(it.value)})
  val down=SystemClock.uptimeMillis()
  controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,down,down))
  controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,down+450,down+450))
  assertEquals(listOf("a"),actions);assertTrue(ordinary.isEmpty())
 }



 @Test fun rightPhysicalShiftIsNotCapturedWhenLayoutReportsLeftShift() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("app.test")))
  val now=SystemClock.uptimeMillis()
  fun right(action:Int)=KeyEvent(now,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_SHIFT_LEFT,0,0,7,54)
  assertFalse(h.controller.handle(right(KeyEvent.ACTION_DOWN)))
  advance(450)
  assertFalse(h.controller.active)
  assertFalse(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)))
  assertFalse(h.controller.handle(right(KeyEvent.ACTION_UP)))
  assertTrue(h.actions.isEmpty())
 }

 @Test fun choosingRightShiftUsesItsPhysicalSideEvenWithCollapsedKeyCode() {
  val actions=mutableListOf<LauncherMapping>()
  val value=LauncherMapping("app.test")
  val controller=KeyboardLauncherController({KeyEvent.KEYCODE_SHIFT_RIGHT},{400},{mapOf(KeyEvent.KEYCODE_A to value)},{},{actions.add(it)})
  val now=SystemClock.uptimeMillis()
  fun shift(action:Int,scan:Int)=KeyEvent(now,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_SHIFT_LEFT,0,0,7,scan)
  assertFalse(controller.handle(shift(KeyEvent.ACTION_DOWN,42)))
  assertFalse(controller.handle(shift(KeyEvent.ACTION_UP,42)))
  assertTrue(controller.handle(shift(KeyEvent.ACTION_DOWN,54)))
  advance(450)
  assertTrue(controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)))
  assertEquals(listOf(value),actions)
  assertTrue(controller.handle(shift(KeyEvent.ACTION_UP,54)))
  assertFalse(controller.active)
 }

 @Test fun physicalShiftReleaseSurvivesChangedLogicalSide() {
  val h=make(emptyMap())
  val now=SystemClock.uptimeMillis()
  assertTrue(h.controller.handle(KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_RIGHT,0,0,7,42)))
  advance(450);assertTrue(h.controller.active)
  assertTrue(h.controller.handle(KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT,0,0,7,42)))
  assertFalse(h.controller.active)
  assertFalse(h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A)))
 }
 @Test fun disablingConsumesOwnedReleaseWithoutLaunching() {
  val h=make(mapOf(KeyEvent.KEYCODE_A to LauncherMapping("short",LauncherMapping("long"))))
  activate(h);h.controller.handle(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A));h.controller.cancel();advance(500)
  assertTrue(h.controller.consumeCanceled(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A)))
  assertTrue(h.controller.consumeCanceled(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT)))
  assertTrue(h.actions.isEmpty());assertFalse(h.controller.active)
 }
}
