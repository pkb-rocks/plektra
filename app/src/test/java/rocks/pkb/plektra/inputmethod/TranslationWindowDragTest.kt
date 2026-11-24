package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class TranslationWindowDragTest {
    @Test fun clampsAllEdgesInsideVisibleArea() {
        val bounds=Rect(10,40,1000,900)
        assertEquals(10 to 40,TranslationWindowDrag.constrain(-100,-100,300,200,bounds))
        assertEquals(700 to 700,TranslationWindowDrag.constrain(5000,5000,300,200,bounds))
        assertEquals(200 to 300,TranslationWindowDrag.constrain(200,300,300,200,bounds))
    }
    @Test fun oversizedWindowDoesNotCreateAnInvalidRange() {
        assertEquals(10 to 40,TranslationWindowDrag.constrain(200,300,2000,2000,Rect(10,40,1000,900)))
    }
    @Test fun movesOnlyAfterLongPressAndStopsAtTheEdge() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val title=TextView(activity);var moved:Pair<Int,Int>?=null
        val drag=TranslationWindowDrag(title,{100 to 80},{Rect(10,40,1000,900)},{300 to 200},{x,y->moved=x to y})
        fun event(action:Int,x:Float,y:Float) { val e=MotionEvent.obtain(100,100,action,x,y,0);drag.onTouch(title,e);e.recycle() }
        event(0,50f,50f);event(2,500f,500f);assertNull(moved)
        event(1,500f,500f);event(0,50f,50f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()+1))
        event(2,5000f,5000f);assertEquals(700 to 700,moved)
        event(2,-5000f,-5000f);assertEquals(10 to 40,moved)
        event(1,-5000f,-5000f)
    }
    @Test fun cancellationRemovesPendingLongPress() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val title=TextView(activity)
        var moves=0
        val drag=TranslationWindowDrag(title,{0 to 0},{Rect(0,0,1000,1000)},{300 to 200},{_,_->moves++})
        val down=MotionEvent.obtain(100,100,0,50f,50f,0);drag.onTouch(title,down);down.recycle();drag.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val move=MotionEvent.obtain(100,1100,2,300f,300f,0);drag.onTouch(title,move);move.recycle();assertEquals(0,moves)
    }
    @Test fun bottomGripMovesImmediatelyWithoutASecondLongPress() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val grip=TextView(activity)
        var moved:Pair<Int,Int>?=null
        val drag=TranslationWindowDrag(grip,{100 to 80},{Rect(0,0,1000,900)},{300 to 200},{x,y->moved=x to y},requireLongPress=false)
        val down=MotionEvent.obtain(100,100,0,50f,50f,0);drag.onTouch(grip,down);down.recycle()
        val move=MotionEvent.obtain(100,110,2,150f,100f,0);drag.onTouch(grip,move);move.recycle()
        assertEquals(200 to 130,moved);drag.cancel()
    }
}
