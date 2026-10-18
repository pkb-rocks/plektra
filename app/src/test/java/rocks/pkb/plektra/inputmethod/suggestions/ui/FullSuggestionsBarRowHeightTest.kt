package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ui.KeyboardThemeColors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullSuggestionsBarRowHeightTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private companion object {
        val SUGGESTIONS = listOf("Alpha", "Beta", "Gamma")
    }

    @Before fun configureSlots() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setStatusBarSlotsLeft(context, listOf("emoji"))
        SettingsManager.setStatusBarSlotsRight(context, listOf("none"))
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf("settings"))
        SettingsManager.setSlimStatusBarSlotsRight(context, listOf("none"))
    }

    @Test fun normalKeepsTheThemeScaledHeightAndSlimIsCompact() {
        for (scale in listOf(0.5f, 0.65f, 0.9f, 1f, 1.3f, 1.6f, 2f)) {
            val clamped = scale.coerceIn(0.65f, 1.6f)
            assertEquals(36f * clamped, FullSuggestionsBar.rowHeightDp(slim = false, heightScale = scale), 0.0001f)
            assertEquals(maxOf(28f * clamped, 24f), FullSuggestionsBar.rowHeightDp(slim = true, heightScale = scale), 0.0001f)
            assertTrue(FullSuggestionsBar.rowHeightDp(true, scale) >= 24f)
            if (clamped >= 0.9f) {
                assertTrue(FullSuggestionsBar.rowHeightDp(true, scale) < FullSuggestionsBar.rowHeightDp(false, scale))
            }
        }
    }

    @Test fun slimRowNeverDropsBelowTheMenuButtonFloor() {
        assertEquals(24f, FullSuggestionsBar.rowHeightDp(slim = true, heightScale = 0.65f), 0.0001f)
        assertEquals(24f, FullSuggestionsBar.rowHeightDp(slim = true, heightScale = 0.8f), 0.0001f)
        assertEquals(28f, FullSuggestionsBar.rowHeightDp(slim = true, heightScale = 1f), 0.0001f)
        assertEquals(36f * 0.65f, FullSuggestionsBar.rowHeightDp(slim = false, heightScale = 0.65f), 0.0001f)
    }

    @Test fun everyHeightOwnerSharesTheProfileRowHeight() {
        val flags = listOf(false, true)
        for (slim in flags) for (suggestions in flags) for (menuButtons in flags) for (rounded in flags) {
            for (scale in listOf(0.65f, 1f, 1.6f)) {
                val case = "slim=$slim suggestions=$suggestions menuButtons=$menuButtons " +
                    "rounded=$rounded scale=$scale"
                SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, rounded)
                val bar = FullSuggestionsBar(context)
                bar.themeOverride = theme(scale)
                val frame = bar.ensureView()
                val chrome = chrome(frame)
                bar.setMinimalUiActive(slim)
                bar.setContentEnabled(suggestions, menuButtons)
                update(bar, SUGGESTIONS)
                if (!suggestions && !menuButtons) {
                    assertEquals(case, View.GONE, frame.visibility)
                    continue
                }
                layout(chrome)
                assertRowHeight(case, frame, rowPx(slim, scale), rounded)
            }
        }
    }

    @Test fun switchingProfilesResizesTheExistingRow() {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        val chrome = chrome(frame)
        bar.setMinimalUiActive(false)
        bar.setContentEnabled(suggestions = true, menuButtons = true)
        update(bar, SUGGESTIONS)
        layout(chrome)
        assertRowHeight("normal", frame, rowPx(false, 1f), rounded = false)

        bar.setMinimalUiActive(true)
        update(bar, SUGGESTIONS)
        layout(chrome)
        assertRowHeight("slim", frame, rowPx(true, 1f), rounded = false)
        assertTrue(visibleViews(frame).any {
            it.contentDescription == context.getString(R.string.status_bar_button_settings_description)
        })

        bar.setMinimalUiActive(false)
        update(bar, SUGGESTIONS)
        layout(chrome)
        assertRowHeight("normal again", frame, rowPx(false, 1f), rounded = false)
    }

    @Test fun switchingProfilesResizesTheHeightOwnersWithoutAnUpdate() {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        val owners = (0 until frame.childCount).map { frame.getChildAt(it) as LinearLayout }
        assertEquals(3, owners.size)
        bar.setMinimalUiActive(false)
        assertOwnerHeights("normal", frame, owners, rowPx(false, 1f))

        bar.setMinimalUiActive(true)
        assertOwnerHeights("slim", frame, owners, rowPx(true, 1f))

        bar.setMinimalUiActive(false)
        assertOwnerHeights("normal again", frame, owners, rowPx(false, 1f))
    }

    @Test fun switchingProfilesRestylesTheSuggestionCorners() {
        val bar = FullSuggestionsBar(context)
        bar.themeOverride = theme(1f, cornerRatio = 0.5f)
        val frame = bar.ensureView()
        bar.setMinimalUiActive(false)
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        update(bar, SUGGESTIONS)
        assertSuggestionRadius("normal", frame, 0.5f * rowPx(false, 1f))

        bar.setMinimalUiActive(true)
        update(bar, SUGGESTIONS)
        assertSuggestionRadius("slim", frame, 0.5f * rowPx(true, 1f))

        bar.setMinimalUiActive(false)
        update(bar, SUGGESTIONS)
        assertSuggestionRadius("normal again", frame, 0.5f * rowPx(false, 1f))
    }

    @Test fun actionSlotIconsFitTheSlimRowAndNormalIsUnchanged() {
        for (slim in listOf(false, true)) {
            val bar = openActionSlot(slim)
            assertActionSlot("slim=$slim", bar.ensureView(), slim)
        }
    }

    @Test fun switchingProfilesRestylesAnOpenActionSlot() {
        val bar = openActionSlot(slim = false)
        val frame = bar.ensureView()
        assertActionSlot("normal", frame, slim = false)

        bar.setMinimalUiActive(true)
        assertActionSlot("slim", frame, slim = true)

        bar.setMinimalUiActive(false)
        assertActionSlot("normal again", frame, slim = false)
    }

    private fun openActionSlot(slim: Boolean): FullSuggestionsBar {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        bar.setMinimalUiActive(slim)
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        updateWithActions(bar)
        val pill = visibleViews(frame).filterIsInstance<TextView>().first { it.text.toString() == "Alpha" }
        assertTrue(pill.performLongClick())
        return bar
    }

    private fun assertActionSlot(case: String, frame: View, slim: Boolean) {
        val slot = (frame as ViewGroup).getChildAt(0).let { bar ->
            (0 until (bar as ViewGroup).childCount).map { bar.getChildAt(it) }.filterIsInstance<LinearLayout>().single()
        }
        val icons = (0 until slot.childCount).map { slot.getChildAt(it) }.filterIsInstance<ImageView>()
        assertEquals(case, 2, icons.size)
        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, if (slim) 2f else 4f, context.resources.displayMetrics).toInt()
        listOf(slot.paddingLeft, slot.paddingTop, slot.paddingRight, slot.paddingBottom)
            .forEach { assertEquals("$case padding", padding, it) }
        icons.forEach {
            assertEquals(case, if (slim) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER, it.scaleType)
        }
    }

    private fun assertOwnerHeights(case: String, frame: View, owners: List<LinearLayout>, expected: Int) {
        assertEquals(case, expected, frame.layoutParams.height)
        assertEquals(case, expected, frame.minimumHeight)
        owners.forEach { assertEquals("$case owner", expected, it.layoutParams.height) }
        assertEquals(case, expected, owners.first().minimumHeight)
    }

    private fun assertSuggestionRadius(case: String, frame: View, expected: Float) {
        val pills = visibleViews(frame).filterIsInstance<TextView>().filter { it.text.toString() in SUGGESTIONS }
        assertEquals(case, SUGGESTIONS.size, pills.size)
        pills.forEach {
            val background = (it.background as android.graphics.drawable.StateListDrawable).current as GradientDrawable
            assertEquals("$case ${it.text}", expected, background.cornerRadius, 0.01f)
        }
    }

    private fun assertRowHeight(case: String, frame: View, expected: Int, rounded: Boolean) {
        assertEquals(case, View.VISIBLE, frame.visibility)
        assertEquals(case, expected, frame.height)
        val views = visibleViews(frame)
        views.filterIsInstance<TextView>().filter { it.text.toString() in SUGGESTIONS }
            .forEach { assertEquals("$case suggestion ${it.text}", expected, it.height) }
        views.filter { it.contentDescription != null }.forEach {
            if (rounded) assertEquals("$case ${it.contentDescription}", expected, it.height)
            else assertTrue("$case ${it.contentDescription} ${it.height} > $expected", it.height <= expected)
        }
        assertTrue(case, views.all { it.height <= expected })
    }

    private fun rowPx(slim: Boolean, scale: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        FullSuggestionsBar.rowHeightDp(slim, scale),
        context.resources.displayMetrics
    ).toInt()

    private fun theme(scale: Float, cornerRatio: Float = 0.08f) = KeyboardThemeColors(
        background = 0, divider = 0, normalKey = 0, specialKey = 0, textAndIcons = 0,
        ledInactive = 0, ledActive = 0, ledLocked = 0, accent = 0, suggestionsHeightScale = scale,
        chromeCornerRadiusRatio = cornerRatio)

    private fun chrome(frame: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(frame)
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, 600, view.measuredHeight)
    }

    private fun update(bar: FullSuggestionsBar, suggestions: List<String>) {
        bar.update(suggestions, true, null, null, false, null,
            null, null, null, null, null, null)
    }

    private fun updateWithActions(bar: FullSuggestionsBar) {
        bar.update(SUGGESTIONS, true, null, null, false, null,
            null, null, null, {}, {}, { true })
    }

    private fun visibleViews(view: View): List<View> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { visibleViews(view.getChildAt(it)) }
        } else emptyList()
    }
}
