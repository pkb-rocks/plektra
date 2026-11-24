package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class FullSuggestionsBarAppearanceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test fun togglingWeightRendersTheSameCandidatesImmediately() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun render() = bar.update(listOf("LongCandidateWithAccentsÄÖÜ", "Second", "Third"), true,
            null, null, false, "OwnWord", null, null, null, null, null, null)
        fun candidates() = descendants(view).filterIsInstance<TextView>().filter {
            it.text.toString() in listOf("LongCandidateWithAccentsÄÖÜ", "Second", "Third", "OwnWord")
        }
        render()
        assertTrue(candidates().isNotEmpty())
        assertTrue(candidates().all { it.typeface?.isBold != true })
        SettingsManager.setSuggestionsBold(context, true)
        render()
        assertTrue(candidates().all { it.typeface?.isBold == true })
        SettingsManager.setSuggestionsBold(context, false)
        render()
        assertTrue(candidates().all { it.typeface?.isBold != true })
    }

    @Test fun suppliedUserCandidatesRemainVisibleWithoutSubtypeDictionary() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        bar.update(listOf("PersonalWord"), true, null, null, false, null,
            null, null, null, null, null, null)
        assertEquals(View.VISIBLE, view.visibility)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "PersonalWord" })
    }

    @Test fun restrictedFieldClearsPreviouslyRenderedSecretsAndAddButton() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        bar.update(listOf("TestSecret"), true, null, null, false, "TestSecret",
            null, null, null, null, null, null)
        bar.update(listOf("TestSecret"), true, null, null, true, "TestSecret",
            null, null, null, null, null, null, reserveEmptyRow = true)
        assertEquals(View.VISIBLE, view.visibility)
        assertFalse(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "TestSecret" })
    }

    @Test fun reservedSoftwareRowKeepsItsHeightWhenSuggestionsDisappear() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun measure() {
            view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        }
        bar.update(listOf("Candidate"), true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true)
        measure()
        val height = view.measuredHeight
        bar.update(emptyList(), true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true)
        measure()
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(height, view.measuredHeight)
        assertTrue(height > 0)
    }

    @Test fun changingCandidatesAndEmptyResultsKeepsTheSameVisibleSlots() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun render(words: List<String>) = bar.update(words, true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true)
        render(listOf("First", "Second", "Third"))
        val originalSlots = descendants(view).filterIsInstance<TextView>().filter {
            it.text.toString() in listOf("First", "Second", "Third")
        }
        assertEquals(3, originalSlots.size)
        render(listOf("Fourth", "Fifth", "Sixth"))
        assertEquals(setOf("Fourth", "Fifth", "Sixth"), originalSlots.map { it.text.toString() }.toSet())
        render(emptyList())
        assertTrue(originalSlots.all { it.text.isEmpty() && it.visibility == View.VISIBLE })
        assertTrue(originalSlots.all { it.parent != null && !it.isClickable })
        render(listOf("Seventh"))
        assertTrue(originalSlots.any { it.text.toString() == "Seventh" })
        assertEquals(View.VISIBLE, view.visibility)
    }

    @Test fun privateActionOnlyReplacesTheMiddleWordAndReleasesItsListenerOnDismiss() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        val words = listOf("first", "second", "third")
        fun render() = bar.update(words, true, null, null, false, null, null, null, null, null, null, null)
        fun labels() = descendants(view).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }
        render()
        val normal = listOf("third", "first", "second")
        assertEquals(normal, labels().map { it.text.toString() })
        bar.centerAction = FullSuggestionsBar.CenterAction("private", { bar.centerAction = null; render() }) {
            fail("dismissed action inserted")
        }
        render()
        assertEquals(listOf("third", "private", "second"), labels().map { it.text.toString() })
        val stale = labels()[1]
        assertTrue(stale.performLongClick())
        assertEquals(normal, labels().map { it.text.toString() })
        assertFalse(stale.performClick())
    }

    @Test fun liveSpeechPreviewUpdatesInOrderWithoutInsertionActions() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun preview(words: List<String>) = bar.update(words, true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true, displayOnly = true)
        preview(listOf("das", "ist", "Sprache"))
        val words = descendants(view).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }
        assertEquals(listOf("das", "ist", "Sprache"), words.map { it.text.toString() })
        assertTrue(words.all { !it.isClickable && !it.isLongClickable })
        preview(listOf("ist", "Sprache", "erkannt"))
        assertEquals(listOf("ist", "Sprache", "erkannt"), words.map { it.text.toString() })
        bar.update(listOf("Normal"), true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true)
        assertTrue(words.any { it.text.toString() == "Normal" && it.isClickable })
    }

    @Test fun continuousSpeechLineShowsMoreThanThreeWordsWithAStableRightEdge() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun render(text: String) = bar.update(listOf(text), true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true,
            displayOnly = true, continuousPreview = true)
        render("eins zwei drei vier fünf sechs")
        val line = descendants(view).filterIsInstance<TextView>().single { it.text.isNotEmpty() }
        assertEquals("eins zwei drei vier fünf sechs", line.text.toString())
        assertEquals(android.text.TextUtils.TruncateAt.START, line.ellipsize)
        assertTrue(line.gravity and android.view.Gravity.END == android.view.Gravity.END)
        assertFalse(line.isClickable)
        render("eins zwei drei vier fünf sechs sieben")
        assertEquals("eins zwei drei vier fünf sechs sieben", line.text.toString())
        assertTrue(line.parent != null)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
