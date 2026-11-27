package rocks.pkb.plektra.inputmethod.expansion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextExpansionPopupTest {
    @Test
    fun popupIsOffsetByItsFullHeightAboveTheImeParentFrame() {
        assertEquals(-500, TextExpansionPopup.popupOffsetY(popupHeight = 500))
    }

    @Test
    fun anAnchorWithoutAWindowShowsNothingInsteadOfCrashing() {
        val context = RuntimeEnvironment.getApplication()
        val popup = TextExpansionPopup(context, onSelected = {}, onDismissed = {})
        // The keyboard's view after its window closed: never attached
        val anchor = View(context)

        popup.show(anchor, listOf(ExpansionMatch(ExpansionProvider.SNIPPET, "sig", "Regards", "!sig", 0)))

        assertFalse(popup.isShowing())
    }

    @Test
    fun rejectedWindowRegistration_canShowAgainWithTheSamePopup() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity)
        activity.setContentView(anchor)
        assertTrue(anchor.isAttachedToWindow)
        val windowManager = mock(WindowManager::class.java)
        doThrow(WindowManager.BadTokenException("window just closed"))
            .doNothing()
            .`when`(windowManager).addView(any(View::class.java), any(ViewGroup.LayoutParams::class.java))
        val context = object : ContextWrapper(activity) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) windowManager else super.getSystemService(name)
        }
        var dismissals = 0
        val popup = TextExpansionPopup(context, onSelected = {}, onDismissed = { dismissals++ })
        val matches = listOf(ExpansionMatch(ExpansionProvider.SNIPPET, "sig", "Regards", "!sig", 0))

        popup.show(anchor, matches)

        assertFalse(popup.isShowing())
        assertEquals(0, dismissals)

        popup.show(anchor, matches)

        assertTrue(popup.isShowing())
        assertEquals(0, dismissals)
        verify(windowManager, times(2)).addView(any(View::class.java), any(ViewGroup.LayoutParams::class.java))
        popup.dismiss()
        assertEquals(1, dismissals)
    }
}
