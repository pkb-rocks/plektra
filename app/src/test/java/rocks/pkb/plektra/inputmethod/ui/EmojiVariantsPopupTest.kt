package rocks.pkb.plektra.inputmethod.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplication
import rocks.pkb.plektra.data.emoji.EmojiRepository

/**
 * The skin-tone popup on a window as narrow as the Galaxy Z Fold6's outer display (968 px at
 * 420 dpi). The device's Noto glyphs make six tones in one row wider than that window; the
 * test font draws emoji narrower, so the larger text size puts the row past the window here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w368dp-h905dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmojiVariantsPopupTest {
    private val thumbsUp = EmojiRepository.EmojiEntry(
        "👍",
        listOf("🏻", "🏼", "🏽", "🏾", "🏿").map { "👍$it" }
    )

    @Test
    fun longPressOnNarrowWindowShowsEveryToneInsideTheWindowAndTypesTheChosenOne() {
        val (picker, anchor, committed) = pickerWithAnchor()
        val windowWidth = anchor.rootView.width

        showVariantsPopup(picker, anchor, thumbsUp)

        val popup = ShadowApplication.getInstance().latestPopupWindow
        assertTrue(popup.isShowing)
        val content = popup.contentView as LinearLayout
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        assertTrue("popup ${content.measuredWidth} px fits the $windowWidth px window", content.measuredWidth <= windowWidth)
        val cells = (0 until content.childCount).flatMap { row ->
            val group = content.getChildAt(row) as ViewGroup
            (0 until group.childCount).map { group.getChildAt(it) as TextView }
        }
        assertEquals(listOf(thumbsUp.base) + thumbsUp.variants, cells.map { it.contentDescription.toString() })

        cells[3].performClick()

        assertEquals(listOf(thumbsUp.variants[2]), committed)
        assertTrue(!popup.isShowing)
    }

    @Test
    fun manyVariantsWrapIntoRowsThatFitTheWindow() {
        val (picker, anchor, _) = pickerWithAnchor()
        val couple = EmojiRepository.EmojiEntry("🧑‍🤝‍🧑", List(25) { thumbsUp.variants[it % 5] })

        showVariantsPopup(picker, anchor, couple)

        val content = ShadowApplication.getInstance().latestPopupWindow.contentView as LinearLayout
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        assertTrue(content.measuredWidth <= anchor.rootView.width)
        assertEquals(26, (0 until content.childCount).sumOf { (content.getChildAt(it) as ViewGroup).childCount })
    }

    private fun pickerWithAnchor(): Triple<EmojiPickerView, TextView, MutableList<String>> {
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val committed = mutableListOf<String>()
        val picker = EmojiPickerView(activity)
        picker.setInputConnection(object : BaseInputConnection(picker, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                committed += text.toString()
                return true
            }
        })
        // A cell at the right edge, where the popup has the least room to its right.
        val anchor = TextView(activity).apply { text = thumbsUp.base }
        val root = FrameLayout(activity).apply {
            addView(picker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600))
            addView(anchor, FrameLayout.LayoutParams(120, 120, android.view.Gravity.BOTTOM or android.view.Gravity.END))
        }
        activity.setContentView(root)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return Triple(picker, anchor, committed)
    }

    private fun showVariantsPopup(picker: EmojiPickerView, anchor: View, entry: EmojiRepository.EmojiEntry) {
        EmojiPickerView::class.java.getDeclaredMethod(
            "showVariantsPopup",
            View::class.java,
            EmojiRepository.EmojiEntry::class.java,
            String::class.java
        ).apply { isAccessible = true }.invoke(picker, anchor, entry, "people")
    }
}
