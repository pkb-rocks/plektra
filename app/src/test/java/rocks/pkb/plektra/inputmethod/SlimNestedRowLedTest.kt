package rocks.pkb.plektra.inputmethod

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SlimNestedRowLedTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private companion object {
        val SUGGESTIONS = listOf("Alpha", "Beta", "Gamma")
    }

    private class Setup(
        val bar: FullSuggestionsBar,
        val chrome: ImeChromeLayout,
        val frame: View,
        val indicators: View
    )

    @Before fun configureSlots() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setStatusBarSlotsLeft(context, listOf("emoji"))
        SettingsManager.setStatusBarSlotsRight(context, listOf("none"))
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf("settings"))
        SettingsManager.setSlimStatusBarSlotsRight(context, listOf("none"))
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
    }

    @Test fun slimRowKeepsItsHeightWithLedIndicators() {
        for (contour in listOf(false, true)) {
            val case = "contour=$contour"
            val setup = setup(contour, slim = true)
            val row = rowPx(slim = true)
            assertEquals(case, row, setup.frame.height)
            assertTrue("$case the LED band sits below the row", setup.indicators.bottom > row)
            assertEquals(case, setup.chrome.height, setup.indicators.bottom)
            val pills = pills(setup.frame)
            assertEquals(case, SUGGESTIONS.size, pills.size)
            pills.forEach { assertEquals("$case ${it.text}", row, it.height) }
            buttons(setup.frame).forEach {
                assertTrue("$case ${it.contentDescription} ${it.height} > $row", it.height <= row)
            }
        }
    }

    @Test fun normalRowStillFillsTheLedContour() {
        for (contour in listOf(false, true)) {
            val case = "contour=$contour"
            val setup = setup(contour, slim = false)
            assertTrue("$case stretched past the row", setup.frame.height > rowPx(slim = false))
            val pills = pills(setup.frame)
            assertEquals(case, SUGGESTIONS.size, pills.size)
            pills.forEach { assertEquals("$case ${it.text}", setup.frame.height, it.height) }
        }
    }

    @Test fun switchingProfilesWithLedIndicatorsLeavesNoStaleMinimumHeight() {
        for (contour in listOf(false, true)) {
            val case = "contour=$contour"
            val setup = setup(contour, slim = false)
            for ((name, slim) in listOf("slim" to true, "normal again" to false, "slim again" to true)) {
                setup.bar.setMinimalUiActive(slim)
                setup.chrome.compactNestedRow = slim
                measure(setup.chrome)
                assertEquals("$case $name nested", rowPx(slim), setup.frame.minimumHeight)
                setup.indicators.visibility = View.GONE
                measure(setup.chrome)
                assertEquals("$case $name restored", rowPx(slim), setup.frame.minimumHeight)
                assertEquals("$case $name height", rowPx(slim), setup.frame.layoutParams.height)
                setup.indicators.visibility = View.VISIBLE
            }
        }
    }

    private fun setup(contour: Boolean, slim: Boolean): Setup {
        val chrome = ImeChromeLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            bottomCornerRadiiPx = 100 to 100
            contourIntegratedIndicators = contour
        }
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        val indicators = View(context)
        val expanded = View(context).apply { visibility = View.GONE }
        chrome.addView(frame)
        chrome.addView(indicators, LinearLayout.LayoutParams(-1, 101))
        chrome.surfaceView = indicators
        chrome.indicatorView = indicators
        chrome.expandedSurfaceView = expanded
        chrome.compactStatusRow = frame
        bar.setMinimalUiActive(slim)
        chrome.compactNestedRow = slim
        bar.setContentEnabled(suggestions = true, menuButtons = true)
        bar.update(SUGGESTIONS, true, null, null, false, null, null, null, null, null, null, null)
        measure(chrome)
        return Setup(bar, chrome, frame, indicators)
    }

    private fun measure(chrome: ImeChromeLayout) {
        chrome.measure(
            View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        chrome.layout(0, 0, chrome.measuredWidth, chrome.measuredHeight)
    }

    private fun rowPx(slim: Boolean): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        FullSuggestionsBar.rowHeightDp(slim, 1f),
        context.resources.displayMetrics
    ).toInt()

    private fun pills(frame: View) =
        visibleViews(frame).filterIsInstance<TextView>().filter { it.text.toString() in SUGGESTIONS }

    private fun buttons(frame: View) = visibleViews(frame).filter { it.contentDescription != null }

    private fun visibleViews(view: View): List<View> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { visibleViews(view.getChildAt(it)) }
        } else emptyList()
    }
}
