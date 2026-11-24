package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.view.*
import android.widget.EditText
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class TranslationEditorInputTest {
    private lateinit var activity: Activity
    private lateinit var editor: EditText
    private lateinit var input: TranslationEditorInput
    @Before fun setup() {
        activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        SettingsManager.getPreferences(activity).edit().clear().commit()
        SettingsManager.setSwipeToDelete(activity,true)
        SettingsManager.setTrackpadDeleteSwipeThreshold(activity,120f)
        editor=EditText(activity);activity.setContentView(editor);editor.setText("Hello world");editor.setSelection(editor.length())
        input=TranslationEditorInput(activity,editor)
    }
    private fun key(code:Int,action:Int=KeyEvent.ACTION_DOWN,meta:Int=0) { input.key(KeyEvent(100,100,action,code,0,meta)) }
    private fun contact(action:Int,x:Float,y:Float=100f,time:Long=100) : Boolean {
        val event=MotionEvent.obtain(100,time,action,x,y,0);event.source=InputDevice.SOURCE_TOUCHPAD
        return input.motion(event).also{event.recycle()}
    }
    @Test fun tappedFnSelectsAllAndDoesNotStickOnNextLetter() {
        key(KeyEvent.KEYCODE_CTRL_LEFT);key(KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.ACTION_UP)
        key(KeyEvent.KEYCODE_A);key(KeyEvent.KEYCODE_A,KeyEvent.ACTION_UP)
        assertEquals(0,editor.selectionStart);assertEquals(editor.length(),editor.selectionEnd)
        key(KeyEvent.KEYCODE_L);key(KeyEvent.KEYCODE_L,KeyEvent.ACTION_UP)
        assertEquals("l",editor.text.toString())
    }
    @Test fun heldFnSelectsAllAndReleaseDoesNotArmAnotherShortcut() {
        key(KeyEvent.KEYCODE_CTRL_LEFT);key(KeyEvent.KEYCODE_A);key(KeyEvent.KEYCODE_A,KeyEvent.ACTION_UP)
        key(KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.ACTION_UP);key(KeyEvent.KEYCODE_L)
        assertEquals("l",editor.text.toString())
    }
    @Test fun nativeCtrlAUsesTheSameProposalSelection() {
        key(KeyEvent.KEYCODE_A,meta=KeyEvent.META_CTRL_ON)
        assertEquals(0,editor.selectionStart);assertEquals(editor.length(),editor.selectionEnd)
    }
    @Test fun leftSwipeDeletesOneWordOnlyOncePerContact() {
        assertTrue(contact(0,600f));contact(2,350f,time=160);contact(2,200f,time=180);contact(1,100f,time=200)
        assertEquals("Hello ",editor.text.toString())
    }
    @Test fun leftSwipeDeletesSelectionRatherThanOtherWords() {
        editor.setSelection(0,5);contact(0,600f);contact(1,350f,time=160)
        assertEquals(" world",editor.text.toString())
    }
    @Test fun disabledDeleteAndVerticalSwipesDoNotDelete() {
        contact(0,600f);contact(1,580f,y=600f,time=160);assertEquals("Hello world",editor.text.toString())
        SettingsManager.setSwipeToDelete(activity,false)
        contact(0,600f);contact(1,350f,time=160);assertEquals("Hello world",editor.text.toString())
    }
    @Test fun screenSelectionIsNotConsumedAsAKeyboardGesture() {
        val event=MotionEvent.obtain(100,100,0,20f,20f,0);event.source=InputDevice.SOURCE_TOUCHSCREEN
        assertFalse(input.motion(event));event.recycle()
    }
}
