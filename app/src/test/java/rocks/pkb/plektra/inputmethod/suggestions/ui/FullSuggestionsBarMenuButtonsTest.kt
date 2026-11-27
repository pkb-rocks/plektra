package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarCallbacks

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullSuggestionsBarMenuButtonsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun configureDistinctSlots() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setStatusBarSlotsLeft(context, listOf("emoji"))
        SettingsManager.setStatusBarSlotsRight(context, listOf("none"))
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf("settings"))
        SettingsManager.setSlimStatusBarSlotsRight(context, listOf("none"))
    }

    @Test fun slimButtonsRemainVisibleAndClickableWithoutSuggestions() {
        var clicks = 0
        val bar = FullSuggestionsBar(context, StatusBarButtonRegistry()) {
            StatusBarCallbacks(onOpenSettings = { clicks++ })
        }
        val frame = bar.ensureView()
        bar.setMinimalUiActive(true)
        bar.setContentEnabled(suggestions = false, menuButtons = true)
        update(bar, emptyList())
        assertEquals(View.VISIBLE, frame.visibility)
        val settings = visibleViews(frame).single {
            it.contentDescription == context.getString(R.string.status_bar_button_settings_description)
        }
        assertTrue(settings.performClick())
        assertEquals(1, clicks)
        assertFalse("Disabled suggestions must not render empty key surfaces",
            visibleViews(frame).any { it is android.widget.TextView && it.background != null && it.text.isEmpty() })
        assertFalse(visibleViews(frame).any {
            it.contentDescription == context.getString(R.string.status_bar_button_emoji_description)
        })
    }

    @Test fun suggestionsKeepSlimButtonsWhenContentIsEnabled() {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        bar.setMinimalUiActive(true)
        bar.setContentEnabled(suggestions = true, menuButtons = true)
        update(bar, listOf("Alpha", "Beta", "Gamma"))
        assertEquals(View.VISIBLE, frame.visibility)
        assertTrue(visibleViews(frame).any {
            it.contentDescription == context.getString(R.string.status_bar_button_settings_description)
        })
        assertTrue(visibleViews(frame).any { it is android.widget.TextView && it.text == "Alpha" })
    }

    @Test fun menuButtonsInTheRowNarrowTheSuggestionPadding() {
        fun padding(menuButtons: Boolean): Int {
            val bar = FullSuggestionsBar(context)
            val frame = bar.ensureView()
            bar.setContentEnabled(suggestions = true, menuButtons = menuButtons)
            update(bar, listOf("Alpha", "Beta", "Gamma"))
            val slot = visibleViews(frame).filterIsInstance<android.widget.TextView>().single { it.text == "Alpha" }
            assertEquals(slot.paddingLeft, slot.paddingRight)
            return slot.paddingLeft
        }
        fun px(dp: Float) = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()
        assertEquals(px(FullSuggestionsBar.SUGGESTION_PADDING_DP), padding(menuButtons = false))
        assertEquals(px(FullSuggestionsBar.SHARED_ROW_SUGGESTION_PADDING_DP), padding(menuButtons = true))
    }

    @Test fun emptySlimSlotsDoNotLeaveAnEmptyRow() {
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf("none"))
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        bar.setMinimalUiActive(true)
        bar.setContentEnabled(suggestions = false, menuButtons = true)
        update(bar, emptyList())
        assertFalse(bar.hasMenuButtons)
        assertEquals(View.GONE, frame.visibility)
    }

    @Test fun clearingSuggestionsRebindsGeometryForTheRemainingButtons() {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        val chrome = rocks.pkb.plektra.inputmethod.ImeChromeLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(frame)
        }
        bar.setMinimalUiActive(true)
        bar.setContentEnabled(suggestions = true, menuButtons = true)
        update(bar, listOf("Alpha", "Beta", "Gamma"))
        layout(chrome)
        bar.setContentEnabled(suggestions = false, menuButtons = true)
        update(bar, emptyList())
        layout(chrome)
        assertEquals(View.VISIBLE, frame.visibility)
        assertNotNull(rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.bottomRow(chrome))
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

    private fun visibleViews(view: View): List<View> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { visibleViews(view.getChildAt(it)) }
        } else emptyList()
    }
}
