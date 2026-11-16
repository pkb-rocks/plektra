package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class SuggestionSwipeAnimationTest {
    private fun popup(effect: SuggestionSwipeAnimation) =
        SuggestionSwipeAnimation::class.java.getDeclaredField("active").apply { isAccessible = true }.get(effect) as? PopupWindow
    private fun host(): Pair<org.robolectric.android.controller.ActivityController<Activity>, TextView> {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        val source = TextView(activity).apply { text = "école"; setTextColor(Color.WHITE) }
        frame.addView(source, FrameLayout.LayoutParams(240, 80).apply { topMargin = 300 })
        frame.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, 800, 1000)
        SuggestionSwipeAnimation.setEnabled(activity, true)
        return controller to source
    }
    @Test fun optInPreferenceRoundTripsAndDefaultsToExistingFeedback() {
        val context = RuntimeEnvironment.getApplication()
        rocks.pkb.plektra.SettingsManager.getPreferences(context).edit().remove(SuggestionSwipeAnimation.KEY).commit()
        assertFalse(SuggestionSwipeAnimation.enabled(context))
        SuggestionSwipeAnimation.setEnabled(context, true); assertTrue(SuggestionSwipeAnimation.enabled(context))
        SuggestionSwipeAnimation.setEnabled(context, false); assertFalse(SuggestionSwipeAnimation.enabled(context))
    }
    @Test fun snapshotHasHeadroomAndNeverTakesTouchOrInputFocus() {
        val (controller, source) = host()
        val effect = SuggestionSwipeAnimation(controller.get())
        assertTrue(effect.show(source))
        val window = requireNotNull(popup(effect))
        assertFalse(window.isFocusable); assertFalse(window.isTouchable); assertFalse(window.isClippingEnabled)
        val stage = window.contentView as FrameLayout
        val label = stage.getChildAt(0) as SuggestionSwipeAnimation.OutlinedLabel
        val rise = (label.layoutParams as FrameLayout.LayoutParams).topMargin
        assertTrue(rise > 0); assertEquals(source.height + rise, window.height)
        assertEquals(Color.BLACK, label.outlineColor)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, stage.importantForAccessibility)
        source.text = "changed after commit"
        assertEquals("école", label.text.toString())
        effect.clear(); effect.clear(); assertNull(popup(effect))
        controller.pause().stop().destroy()
    }
    @Test fun popupCoordinatesDoNotAddTheImeScreenOriginTwice() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val frame = FrameLayout(activity); activity.setContentView(frame)
        val source = object : TextView(activity) {
            override fun getLocationOnScreen(out: IntArray) { super.getLocationOnScreen(out); out[1] += 1000 }
        }.apply { text = "word"; setTextColor(Color.BLACK) }
        frame.addView(source, FrameLayout.LayoutParams(240, 80).apply { topMargin = 300 })
        frame.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)); frame.layout(0, 0, 800, 1000)
        SuggestionSwipeAnimation.setEnabled(activity, true)
        val effect = SuggestionSwipeAnimation(activity)
        assertTrue(effect.show(source))
        val window = requireNotNull(popup(effect)); val stage = window.contentView as FrameLayout
        val rise = (stage.getChildAt(0).layoutParams as FrameLayout.LayoutParams).topMargin
        val location = IntArray(2); source.getLocationInWindow(location)
        assertEquals(location[1] - rise, (stage.rootView.layoutParams as WindowManager.LayoutParams).y)
        assertEquals(Color.WHITE, (stage.getChildAt(0) as SuggestionSwipeAnimation.OutlinedLabel).outlineColor)
        effect.clear(); controller.pause().stop().destroy()
    }
    @Test fun anotherSwipeReplacesTheOldWindowAndDestroyCleansUp() {
        val (controller, source) = host(); val effect = SuggestionSwipeAnimation(controller.get())
        assertTrue(effect.show(source)); val old = requireNotNull(popup(effect))
        assertTrue(effect.show(source)); assertFalse(old.isShowing); assertNotSame(old, popup(effect))
        controller.pause().stop().destroy(); assertNull(popup(effect))
    }
    @Test fun disabledHiddenAndUnmeasuredViewsKeepTheExistingFallback() {
        val (controller, source) = host(); val effect = SuggestionSwipeAnimation(controller.get())
        SuggestionSwipeAnimation.setEnabled(controller.get(), false); assertFalse(effect.show(source))
        SuggestionSwipeAnimation.setEnabled(controller.get(), true); source.visibility = View.GONE; assertFalse(effect.show(source))
        assertFalse(effect.show(TextView(controller.get()).apply { text = "word" }))
        assertNull(popup(effect)); controller.pause().stop().destroy()
    }
    @Test fun actionIconsAndInvisibleInkAreNotAnimatedAsSuggestions() {
        val (controller, source) = host(); val effect = SuggestionSwipeAnimation(controller.get())
        source.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_input_add, 0, 0, 0)
        assertFalse(effect.show(source))
        source.setCompoundDrawables(null, null, null, null); source.setTextColor(Color.TRANSPARENT)
        assertFalse(effect.show(source)); assertNull(popup(effect))
        controller.pause().stop().destroy()
    }
}
