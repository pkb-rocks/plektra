package rocks.pkb.plektra.inputmethod

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeHitBoundsReportTest {
    @Test
    fun reportsTheLowestClickableTargetsInScreenCoordinates() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        fun child(left: Int, top: Int, right: Int, bottom: Int, clickable: Boolean, description: String) =
            View(activity).apply {
                isClickable = clickable
                contentDescription = description
                root.addView(this, FrameLayout.LayoutParams(right - left, bottom - top).apply {
                    leftMargin = left; topMargin = top
                })
            }
        child(0, 100, 200, 160, clickable = true, description = "far above")
        child(0, 300, 200, 368, clickable = true, description = "corner")
        child(200, 300, 400, 368, clickable = false, description = "label")
        child(400, 320, 600, 368, clickable = true, description = "space")
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 400)
        val origin = IntArray(2).also(root::getLocationOnScreen)

        val targets = ImeHitBoundsReport.targets(root, contentBottom = origin[1] + 368, bandPx = 100)

        assertEquals(listOf("desc:corner", "desc:space"), targets.map { it.name })
        assertEquals(Rect(origin[0], origin[1] + 300, origin[0] + 200, origin[1] + 368), targets.first().bounds)
        assertEquals(
            "windowBottom=400 contentBottom=368 mandatoryGestureTop=336 corners=60,60 " +
                "targets=desc:corner@0,300,200,368",
            ImeHitBoundsReport.format(400, 368, 336, 60 to 60,
                listOf(ImeHitBoundsReport.Target("desc:corner", Rect(0, 300, 200, 368))))
        )
    }
}
