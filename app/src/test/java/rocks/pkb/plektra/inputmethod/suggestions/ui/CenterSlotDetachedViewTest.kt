package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ImeChromeLayout
import rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CenterSlotDetachedViewTest(private val scenario: String, private val contour: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, contour={1}")
        fun rows() = listOf("paste cleared", "long press", "hidden expiry").flatMap {
            listOf(arrayOf(it, false), arrayOf(it, true))
        } + listOf("inactive", "replaced", "preview takeover", "frame tag", "stale row").map { arrayOf(it, false) }
    }

    private val context get() = RuntimeEnvironment.getApplication()

    private inner class Strip(val label: String) {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        val chrome = ImeChromeLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            bottomCornerRadiiPx = 60 to 60
            contourIntegratedIndicators = contour
            addView(frame)
            indicatorView = View(context).also { addView(it) }
        }
        val center: TextView

        init {
            bar.setContentEnabled(suggestions = true, menuButtons = false)
            bar.centerAction = FullSuggestionsBar.CenterAction(label, { bar.centerAction = null }) { fail("unexpected insert") }
            render()
            layout()
            center = taggedSurfaces().filterIsInstance<TextView>().single { it.text == label }
            assertEquals(View.VISIBLE, center.visibility)
            assertTrue(center.width > 0)
        }

        fun render() = bar.update(listOf("first", "second", "third"), true, null, null, false, null,
            null, null, null, null, null, null)

        fun layout() {
            chrome.requestLayout()
            chrome.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST))
            chrome.layout(0, 0, chrome.measuredWidth, chrome.measuredHeight)
        }

        // Inspect the stored Row, not a fresh scan of the (necessarily attached) children.
        fun taggedSurfaces(): List<View> {
            val tag = frame.getTag(R.id.keyboard_bar_row_geometry)
            val surfaces = tag.javaClass.getDeclaredField("surfaces").apply { isAccessible = true }
            return (surfaces.get(tag) as List<*>).map { it as View }
        }

        fun assertRemainingSurfacesUnder(root: ViewGroup) {
            val remaining = taggedSurfaces()
            assertTrue(remaining.all { surface -> generateSequence(surface.parent) { it.parent }.any { it === root } })
            assertEquals(2, remaining.size)
        }
    }

    @Test fun layoutAfterTransition() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        val strip = Strip(if (scenario == "paste cleared") "Paste excerpt" else "123456")
        with(strip) {
            when (scenario) {
                "paste cleared" -> bar.centerAction = null
                "long press" -> {
                    assertTrue(center.performLongClick())
                    assertNull(bar.centerAction)
                }
                "hidden expiry" -> {
                    frame.visibility = View.GONE
                    bar.centerAction = null
                    frame.visibility = View.VISIBLE
                }
                "inactive" -> {
                    val active = Strip("Active paste")
                    bar.centerAction = null
                    active.render()
                    active.layout()
                }
                "replaced" -> {
                    bar.centerAction = FullSuggestionsBar.CenterAction("654321", {}, {})
                    assertFalse(center.hasOnClickListeners())
                    assertFalse(center.hasOnLongClickListeners())
                }
                "preview takeover" -> {
                    // Upstream has no inline-content host; use its real display-only strip renderer.
                    bar.centerAction = null
                    bar.update(listOf("speech preview"), true, null, null, false, null, null, null, null,
                        null, null, null, displayOnly = true, continuousPreview = true)
                    assertEquals("speech preview", (taggedSurfaces().single() as TextView).text.toString())
                }
                "frame tag" -> {
                    bar.centerAction = null
                    assertRemainingSurfacesUnder(frame)
                }
                "stale row" -> {
                    // Bypass the setter to exercise bottomRow's own defense independently.
                    val left = taggedSurfaces().first()
                    KeyboardBarRowGeometry.bind(frame, listOf(left))
                    (left.parent as ViewGroup).removeView(left)
                    assertEquals(View.VISIBLE, left.visibility)
                    assertTrue(left.width > 0)
                    val row = KeyboardBarRowGeometry.bottomRow(chrome)!!
                    assertNull(row.left)
                    assertNull(row.right)
                }
                else -> error("Unknown scenario: $scenario")
            }
            layout()
            if (scenario == "paste cleared") assertRemainingSurfacesUnder(chrome)
        }
    }
}
