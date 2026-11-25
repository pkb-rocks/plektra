package rocks.pkb.plektra.inputmethod.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarCallbacks

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HamburgerMenuSizingTest {
    @Test
    fun menuButtonsKeepTheHeightAndSpacingOfTheRowTheyCover() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame, ViewGroup.LayoutParams(1080, 144))
        val menu = HamburgerMenuView(activity, StatusBarButtonRegistry())
        menu.rowButtonMetrics = { HamburgerMenuView.RowButtonMetrics(heightPx = 120, spacingPx = 7) }
        menu.attachTo(frame)
        menu.show(StatusBarCallbacks()) {}
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            frame.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(144, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1080, 144)
        }
        val row = (frame.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout
        assertTrue(row.childCount > 1)
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            assertEquals("button $index height", 120, child.height)
            assertEquals("button $index is centered", (144 - 120) / 2, child.top)
            val params = child.layoutParams as LinearLayout.LayoutParams
            assertEquals(if (index == row.childCount - 1) 0 else 7, params.marginEnd)
        }
    }
}
