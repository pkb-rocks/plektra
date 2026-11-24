package rocks.pkb.plektra.inputmethod

import rocks.pkb.plektra.inputmethod.statusbar.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class FieldTranslationButtonTest {
 @Test fun buttonCreatedBeforeTheHandlerIsReadyUsesTheNewHandler() {
  val host=StatusBarButtonHost(RuntimeEnvironment.getApplication(),StatusBarButtonRegistry())
  val old=host.getOrCreateButton(StatusBarButtonId.Translation,40,StatusBarCallbacks(),40,40)!!
  var calls=0
  val ready=host.getOrCreateButton(StatusBarButtonId.Translation,40,StatusBarCallbacks(onTranslationRequested={calls++}),40,40)!!
  assertSame(old.button,ready.button);ready.button.performClick();assertEquals(1,calls)
 }
 @Test fun reusedButtonNeverCallsAnOldHandlerOrFiresTwice() {
  val host=StatusBarButtonHost(RuntimeEnvironment.getApplication(),StatusBarButtonRegistry())
  var oldCalls=0;var newCalls=0
  host.getOrCreateButton(StatusBarButtonId.Translation,40,StatusBarCallbacks(onTranslationRequested={oldCalls++}),40,40)
  val ready=host.getOrCreateButton(StatusBarButtonId.Translation,40,StatusBarCallbacks(onTranslationRequested={newCalls++}),40,40)!!
  ready.button.performClick();assertEquals(0,oldCalls);assertEquals(1,newCalls)
 }
}
