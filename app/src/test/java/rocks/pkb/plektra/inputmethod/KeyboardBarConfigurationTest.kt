package rocks.pkb.plektra.inputmethod

import android.view.View
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardBarConfigurationTest {
    private companion object {
        val DEV_CHOICE = listOf("»", "«", ";", "!", "?", ",", ".", "–", "%")
    }

    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
    }

    private fun snapshot() = StatusBarController.StatusSnapshot(
        capsLockEnabled = false, shiftPhysicallyPressed = false, shiftOneShot = false,
        ctrlLatchActive = false, ctrlPhysicallyPressed = false, ctrlOneShot = false,
        ctrlLatchFromNavMode = false, altLatchActive = false, altPhysicallyPressed = false,
        altOneShot = false, symPage = 0
    )

    @Test fun slimOnlySuppressesActionsAndKeepsNormalConfiguration() {
        val config = KeyboardBarConfiguration(true, true, true, true, true)
        assertFalse(config.presentation(true).actions)
        assertTrue(config.presentation(true).suggestions)
        assertTrue(config.presentation(true).bottomIndicators)
        assertTrue(config.presentation(false).actions)
        assertFalse(config.copy(suggestions = false).presentation(false).inlineIndicators)
    }

    @Test fun slimProfileCanDisableEverythingWithoutChangingNormalProfile() {
        SettingsManager.setSlimStatusBarSlotsLeft(context, emptyList())
        SettingsManager.setSlimStatusBarSlotsRight(context, emptyList())
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        rocks.pkb.plektra.KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, false, false, true, false))
        val configuration = KeyboardBarConfiguration.read(context)
        assertFalse(configuration.presentation(true).hasPersistentContent)
        assertTrue(configuration.presentation(true).androidIndicators)
        assertTrue(configuration.presentation(false).actions)
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        controller.update(snapshot())
        assertEquals(View.GONE, controller.getLayout()!!.visibility)
    }

    @Test fun slimMenuButtonsKeepTheRowVisibleWithAllContentDisabled() {
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, false, false, false, false))
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_SETTINGS))
        SettingsManager.setSlimStatusBarSlotsRight(context, emptyList())
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        controller.update(snapshot())
        val root = controller.getLayout()!!
        assertTrue(controller.hasConfiguredMenuButtons)
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(View.VISIBLE, root.getChildAt(0).visibility)
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        assertNull((root as ImeChromeLayout).ledOnlyReservedHeightPx)
    }

    @Test fun migratedSlimProfileIsIndependentOfSubsequentNormalEdits() {
        SettingsManager.setSuggestionsEnabled(context, true)
        val before = KeyboardBarConfiguration.read(context).slimProfile
        SettingsManager.setSuggestionsEnabled(context, false)
        assertEquals(before, KeyboardBarConfiguration.read(context).slimProfile)
    }

    @Test fun emptySuggestionsReserveNoHeight() {
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, false)
        SettingsManager.setModifierIndicators(context, emptySet())
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.update(snapshot())
        assertEquals(View.GONE, controller.getLayout()!!.visibility)
        assertEquals(0, controller.getLayout()!!.layoutParams.height)
    }

    @Test fun actionsFirstMovesSnippetRowBelowActionsAndKeepsIndicatorsLast() {
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        KeyboardBarConfiguration.setArrangement(context, rocks.pkb.plektra.KeyboardBarArrangement.ACTIONS_FIRST)
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.showExpansionSuggestions(listOf("Snippet choice")) {}
        controller.update(snapshot())
        val root = controller.getLayout()!!
        assertTrue(root.getChildAt(1) is rocks.pkb.plektra.inputmethod.ui.CursorSwipeLayout)
        assertTrue(root.getChildAt(root.childCount - 1) is android.widget.FrameLayout)
        assertEquals(View.VISIBLE, root.getChildAt(1).visibility)
    }

    @Test fun liveSlimSwitchSharesVariationsAndMenuWithoutKeepingTheNormalActionsRow() {
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf("language"))
        SettingsManager.setSlimStatusBarSlotsRight(context, listOf("hamburger"))
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, true, true, true, false))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        val state = snapshot().copy(variations = listOf("à", "â"), suggestions = listOf("alphabet"))
        fun visibleLabels(view: View): List<String> {
            if (view.visibility != View.VISIBLE) return emptyList()
            if (view is android.widget.TextView) return listOf(view.text.toString())
            return if (view is android.view.ViewGroup)
                (0 until view.childCount).flatMap { visibleLabels(view.getChildAt(it)) }
            else emptyList()
        }
        for (slim in listOf(false, true, false, true)) {
            controller.setSlimModeActive(slim)
            controller.update(state)
            val root = controller.getLayout()!!
            assertEquals(View.VISIBLE, root.getChildAt(0).visibility)
            assertEquals(if (slim) View.GONE else View.VISIBLE, root.getChildAt(1).visibility)
            if (slim) {
                val labels = visibleLabels(root)
                assertTrue(labels.contains("à"))
                assertTrue(labels.contains("â"))
                assertFalse(labels.contains("alphabet"))
            }
        }
    }

    @Test fun slimPresentationAlwaysCombinesRowsAndKeepsTheNormalArrangement() {
        for (arrangement in rocks.pkb.plektra.KeyboardBarArrangement.entries) {
            val config = KeyboardBarConfiguration(true, true, true, true, true, arrangement)
            assertEquals(rocks.pkb.plektra.KeyboardBarArrangement.SHARED, config.presentation(true).arrangement)
            assertEquals(arrangement, config.presentation(false).arrangement)
        }
    }

    private fun visibleLabels(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        if (view is android.widget.TextView) return listOf(view.text.toString())
        return if (view is android.view.ViewGroup)
            (0 until view.childCount).flatMap { visibleLabels(view.getChildAt(it)) }
        else emptyList()
    }

    private fun measuredHeight(root: View): Int {
        root.measure(View.MeasureSpec.makeMeasureSpec(1076, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return root.measuredHeight
    }

    /** The reported device configuration: static variations, Slim without suggestions. */
    @Test fun liveSlimSwitchWithStaticVariationsKeepsOneRowInEveryArrangement() {
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setStaticVariationBarModeEnabled(context, true)
        SettingsManager.setStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_HAMBURGER))
        SettingsManager.setStatusBarSlotsRight(context, listOf("emoji", "microphone"))
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, true, true, true, false))
        for (arrangement in rocks.pkb.plektra.KeyboardBarArrangement.entries) {
            KeyboardBarConfiguration.setArrangement(context, arrangement)
            val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
            val variationsRowIndex = if (arrangement == rocks.pkb.plektra.KeyboardBarArrangement.ACTIONS_FIRST) 0 else 1
            for (slim in listOf(false, true, false, true)) {
                controller.setSlimModeActive(slim)
                controller.update(snapshot())
                val root = controller.getLayout()!!
                val variationsRow = root.getChildAt(variationsRowIndex)
                val combinedRow = root.getChildAt(1 - variationsRowIndex)
                if (slim || arrangement == rocks.pkb.plektra.KeyboardBarArrangement.SHARED) {
                    assertEquals("$arrangement slim=$slim", View.VISIBLE, combinedRow.visibility)
                    assertEquals("$arrangement slim=$slim", View.GONE, variationsRow.visibility)
                    assertTrue("$arrangement slim=$slim", visibleLabels(combinedRow).isNotEmpty())
                } else {
                    // Normal profile without suggestions: only the configured actions row.
                    assertEquals("$arrangement slim=$slim", View.VISIBLE, variationsRow.visibility)
                    assertEquals("$arrangement slim=$slim", View.GONE, combinedRow.visibility)
                }
            }
        }
    }

    @Test fun slimWithoutVariationsStillShowsItsMenuRowOnce() {
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setStaticVariationBarModeEnabled(context, false)
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf("language"))
        SettingsManager.setSlimStatusBarSlotsRight(context, listOf("hamburger"))
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, true, true, true, false))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        // The first render sizes the buttons; the second one is the settled Slim bar.
        controller.update(snapshot())
        controller.update(snapshot())
        val root = controller.getLayout()!!
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(View.VISIBLE, root.getChildAt(0).visibility)
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        val slimHeight = measuredHeight(root)
        repeat(2) {
            controller.setSlimModeActive(false)
            controller.update(snapshot().copy(variations = listOf("à", "â")))
            assertEquals(View.VISIBLE, root.getChildAt(1).visibility)
            assertTrue(measuredHeight(root) > slimHeight)
            controller.setSlimModeActive(true)
            controller.update(snapshot())
            assertEquals(View.GONE, root.getChildAt(1).visibility)
            assertEquals(slimHeight, measuredHeight(root))
        }
    }

    @Test fun slimHardwareSymPageReplacesTheCombinedRowUntilItCloses() {
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, true, true, true, false))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        controller.update(snapshot().copy(symPage = 1))
        val root = controller.getLayout()!!
        assertEquals(View.GONE, root.getChildAt(0).visibility)
        controller.update(snapshot())
        assertEquals(View.VISIBLE, root.getChildAt(0).visibility)
        assertEquals(View.GONE, root.getChildAt(1).visibility)
    }

    /** The software keyboard keeps its own actions row above the keys; Slim does not combine it. */
    @Test fun slimSoftwareKeyboardKeepsItsSeparateActionsRow() {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setStaticVariationBarPreset(context, SettingsManager.STATIC_VARIATION_PRESET_DEV_CHOICE)
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, true, true, true, false))
        val controller = StatusBarController(context, StatusBarController.Mode.INPUT_VIEW)
        for (slim in listOf(false, true, false, true)) {
            controller.setSlimModeActive(slim)
            controller.update(snapshot())
            val root = controller.getLayout()!!
            assertEquals("slim=$slim", View.VISIBLE, root.getChildAt(1).visibility)
            assertTrue("slim=$slim", variationSlotTexts(root.getChildAt(0), DEV_CHOICE).isEmpty())
        }
    }

    private class RecordingConnection {
        val calls = mutableListOf<String>()
        val connection: android.view.inputmethod.InputConnection = java.lang.reflect.Proxy.newProxyInstance(
            android.view.inputmethod.InputConnection::class.java.classLoader,
            arrayOf(android.view.inputmethod.InputConnection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "commitText" -> calls += "commit:${args!![0]}"
                "deleteSurroundingText" -> calls += "delete:${args!![0]},${args[1]}"
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                Integer.TYPE -> 0
                else -> null
            }
        } as android.view.inputmethod.InputConnection
    }

    /** The texts of the row that holds [expected]'s first entry, in visual order; empty slots as "". */
    private fun variationSlotTexts(view: View, expected: List<String>): List<String> {
        fun find(v: View): android.widget.TextView? {
            if (v is android.widget.TextView && v.text.toString() == expected.first() && v.visibility == View.VISIBLE) return v
            if (v is android.view.ViewGroup && v.visibility == View.VISIBLE) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val row = (find(view)?.parent as? android.view.ViewGroup) ?: return emptyList()
        return (0 until row.childCount).map { index ->
            val slot = row.getChildAt(index) as android.widget.TextView
            if (slot.visibility == View.VISIBLE) slot.text.toString() else ""
        }
    }

    private fun slotViews(root: View, expected: List<String>): List<android.widget.TextView> {
        fun find(v: View): android.widget.TextView? {
            if (v is android.widget.TextView && v.text.toString() == expected.first() && v.visibility == View.VISIBLE) return v
            if (v is android.view.ViewGroup && v.visibility == View.VISIBLE) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val row = find(root)!!.parent as android.view.ViewGroup
        return (0 until row.childCount).map { row.getChildAt(it) as android.widget.TextView }
    }

    private fun slimVariationsController(): StatusBarController {
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_LANGUAGE))
        SettingsManager.setSlimStatusBarSlotsRight(context, listOf(SettingsManager.STATUS_BAR_BUTTON_HAMBURGER))
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(false, true, true, true, false))
        return StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY).apply { setSlimModeActive(true) }
    }

    /** The reported device configuration: all nine static entries in order, each committing itself. */
    @Test fun slimCombinedRowShowsTheWholeStaticListInOrderAndCommitsEachEntry() {
        SettingsManager.setStaticVariationBarPreset(context, SettingsManager.STATIC_VARIATION_PRESET_DEV_CHOICE)
        val controller = slimVariationsController()
        val recorder = RecordingConnection()
        controller.update(snapshot(), inputConnection = recorder.connection)
        val root = controller.getLayout()!!
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        assertEquals(DEV_CHOICE, variationSlotTexts(root.getChildAt(0), DEV_CHOICE))
        slotViews(root, DEV_CHOICE).forEach { slot ->
            recorder.calls.clear()
            assertTrue(slot.performClick())
            assertEquals(slot.text.toString(), listOf("commit:${slot.text}"), recorder.calls)
        }
    }

    @Test fun slimCombinedRowShowsAllDynamicVariationsInOrderAndReplacesTheCharacter() {
        SettingsManager.setStaticVariationBarModeEnabled(context, false)
        val variations = listOf("à", "á", "ä", "â", "ã", "å", "ą")
        val controller = slimVariationsController()
        val recorder = RecordingConnection()
        controller.update(snapshot().copy(variations = variations), inputConnection = recorder.connection)
        val root = controller.getLayout()!!
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        assertEquals(variations, variationSlotTexts(root.getChildAt(0), variations))
        slotViews(root, variations).forEach { slot ->
            recorder.calls.clear()
            assertTrue(slot.performClick())
            assertEquals(slot.text.toString(), listOf("delete:1,0", "commit:${slot.text}"), recorder.calls)
        }
    }

    @Test fun combinedRowFollowsTheConfiguredDynamicSlotCount() {
        SettingsManager.setStaticVariationBarModeEnabled(context, false)
        val controller = slimVariationsController()
        val many = listOf("à", "á", "ä", "â", "ã", "å", "ą")
        SettingsManager.setDynamicVariationBarSlotCount(context, 4)
        controller.update(snapshot().copy(variations = many))
        val root = controller.getLayout()!!
        assertEquals(many.take(4), variationSlotTexts(root.getChildAt(0), many))

        // Fewer variations keep the configured slots, unused ones stay empty, unless resized to content.
        SettingsManager.setDynamicVariationBarSlotCount(context, 5)
        controller.update(snapshot().copy(variations = listOf("é", "è")))
        assertEquals(listOf("é", "è", "", "", ""), variationSlotTexts(root.getChildAt(0), listOf("é")))
        slotViews(root, listOf("é")).drop(2).forEach { assertFalse(it.isClickable) }
        SettingsManager.setDynamicVariationBarResizeToContent(context, true)
        controller.update(snapshot().copy(variations = listOf("é", "è")))
        assertEquals(listOf("é", "è"), variationSlotTexts(root.getChildAt(0), listOf("é")))
    }

    /** Equal labels across content kinds and connections must not keep the earlier click binding. */
    @Test fun combinedRowRebindsWhenContentKindOrConnectionChanges() {
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setStaticVariationBarModeEnabled(context, false)
        SettingsManager.setDynamicVariationBarResizeToContent(context, true)
        KeyboardBarConfiguration.setSlimProfile(context,
            rocks.pkb.plektra.KeyboardBarProfile(true, true, true, true, false))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        val words = listOf("ab", "ac", "ad")
        val recorder = RecordingConnection()

        controller.update(snapshot().copy(variations = words), inputConnection = recorder.connection)
        val root = controller.getLayout()!!
        assertEquals(words, variationSlotTexts(root.getChildAt(0), words))

        // Word suggestions keep their ranking and three slots: second best left, best centre.
        controller.update(snapshot().copy(suggestions = listOf("ac", "ad", "ab")), inputConnection = recorder.connection)
        assertEquals(words, variationSlotTexts(root.getChildAt(0), words))
        recorder.calls.clear()
        slotViews(root, words)[0].performClick()
        // The word replaces the (here empty) current word and gets its trailing space.
        assertEquals(listOf("delete:0,0", "commit:ab", "commit: "), recorder.calls)

        controller.update(snapshot().copy(variations = words), inputConnection = recorder.connection)
        recorder.calls.clear()
        slotViews(root, words)[0].performClick()
        assertEquals(listOf("delete:1,0", "commit:ab"), recorder.calls)

        // A click resets the row's cache, so render with the first connection again before the switch.
        controller.update(snapshot().copy(variations = words), inputConnection = recorder.connection)
        val next = RecordingConnection()
        controller.update(snapshot().copy(variations = words), inputConnection = next.connection)
        recorder.calls.clear()
        slotViews(root, words)[1].performClick()
        assertEquals(emptyList<String>(), recorder.calls)
        assertEquals(listOf("delete:1,0", "commit:ac"), next.calls)
    }

    @Test fun sharedRowCommitsSnippetAndSuppressesSeparateActionsRow() {
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        KeyboardBarConfiguration.setArrangement(context, rocks.pkb.plektra.KeyboardBarArrangement.SHARED)
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        var selected: String? = null
        controller.showExpansionSuggestions(listOf("Snippet choice")) { selected = it }
        controller.update(snapshot())
        val root = controller.getLayout()!!
        assertEquals(View.VISIBLE, root.getChildAt(0).visibility)
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        assertEquals(View.GONE, root.getChildAt(2).visibility)
        fun findLabel(view: View): android.widget.TextView? {
            if (view is android.widget.TextView && view.text.toString() == "Snippet choice") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                findLabel(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        findLabel(root)!!.performClick()
        assertEquals("Snippet choice", selected)
    }

    private fun sharedRowController(suggestions: Boolean = true): StatusBarController {
        SettingsManager.setSuggestionsEnabled(context, suggestions)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setStaticVariationBarModeEnabled(context, false)
        KeyboardBarConfiguration.setArrangement(context, rocks.pkb.plektra.KeyboardBarArrangement.SHARED)
        return StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
    }

    @Test fun sharedRowKeepsWordSuggestionsAheadOfDynamicVariationsByDefault() {
        val controller = sharedRowController()
        controller.update(snapshot().copy(variations = listOf("à", "â"), suggestions = listOf("alphabet")))
        val labels = visibleLabels(controller.getLayout()!!)
        assertTrue(labels.contains("alphabet"))
        assertFalse(labels.contains("à"))
        assertFalse(labels.contains("â"))
    }

    @Test fun sharedRowShowsDynamicVariationsFirstWhenEnabled() {
        val controller = sharedRowController()
        SettingsManager.setSharedRowVariationsFirst(context, true)
        val variations = listOf("à", "â")
        val recorder = RecordingConnection()
        controller.update(snapshot().copy(variations = variations, suggestions = listOf("alphabet")),
            inputConnection = recorder.connection)
        val root = controller.getLayout()!!
        val labels = visibleLabels(root)
        assertTrue(labels.containsAll(variations))
        assertFalse(labels.contains("alphabet"))
        slotViews(root, variations).first().performClick()
        assertEquals(listOf("delete:1,0", "commit:à"), recorder.calls)
    }

    /** Without word suggestions the row keeps every variation in order, whatever the precedence. */
    @Test fun sharedRowShowsAllDynamicVariationsWhenSuggestionsAreOff() {
        val variations = listOf("à", "á", "ä", "â", "ã", "å", "ą")
        listOf(false, true).forEach { variationsFirst ->
            val controller = sharedRowController(suggestions = false)
            SettingsManager.setSharedRowVariationsFirst(context, variationsFirst)
            controller.update(snapshot().copy(variations = variations, suggestions = listOf("alphabet")))
            val root = controller.getLayout()!!
            assertEquals(variations, variationSlotTexts(root.getChildAt(0), variations))
            assertFalse(visibleLabels(root).contains("alphabet"))
        }
    }

    @Test fun sharedRowFallsBackToVariationsAndThenStaticCharactersWithoutSuggestions() {
        val controller = sharedRowController()
        val recorder = RecordingConnection()
        controller.update(snapshot().copy(variations = listOf("é", "è")), inputConnection = recorder.connection)
        val root = controller.getLayout()!!
        assertTrue(visibleLabels(root).containsAll(listOf("é", "è")))

        // Neither suggestions nor dynamic variations: the static characters fill the row.
        SettingsManager.setStaticVariationBarPreset(context, SettingsManager.STATIC_VARIATION_PRESET_DEV_CHOICE)
        controller.update(snapshot(), inputConnection = recorder.connection)
        assertEquals(DEV_CHOICE, variationSlotTexts(root.getChildAt(0), DEV_CHOICE))
        recorder.calls.clear()
        slotViews(root, DEV_CHOICE).first().performClick()
        assertEquals(listOf("commit:»"), recorder.calls)
    }

    /** Switching content kinds rebinds each slot to its own action, so no character or word goes astray. */
    @Test fun sharedRowSwitchesBetweenSuggestionsAndVariationsWithMatchingActions() {
        val controller = sharedRowController()
        val recorder = RecordingConnection()
        val words = listOf("fête", "femme", "fenêtre")
        val variations = listOf("è", "é", "ê")
        controller.update(snapshot().copy(variations = variations, suggestions = words), inputConnection = recorder.connection)
        val root = controller.getLayout()!!
        assertFalse(visibleLabels(root).contains("é"))
        recorder.calls.clear()
        slotViews(root, listOf("fenêtre"))[1].performClick()
        assertEquals(listOf("delete:0,0", "commit:fête", "commit: "), recorder.calls)

        controller.update(snapshot().copy(variations = variations), inputConnection = recorder.connection)
        assertTrue(visibleLabels(root).containsAll(variations))
        recorder.calls.clear()
        slotViews(root, variations)[1].performClick()
        assertEquals(listOf("delete:1,0", "commit:é"), recorder.calls)

        controller.update(snapshot().copy(variations = variations, suggestions = words), inputConnection = recorder.connection)
        val labels = visibleLabels(root)
        assertTrue(labels.containsAll(words))
        assertFalse(labels.contains("é"))
    }

    @Test fun modifiersShareOneGrowingMenuButtonAndHandBackTheOuterEdge() {
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_HAMBURGER))
        SettingsManager.setStatusBarSlotsRight(context, emptyList())
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_STATUS_BAR))
        KeyboardBarConfiguration.setArrangement(context, rocks.pkb.plektra.KeyboardBarArrangement.SHARED)
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.showExpansionSuggestions(listOf("Sample")) {}
        controller.update(snapshot().copy(shiftOneShot = true))
        val root = controller.getLayout()!!
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.updateRows(root)
        }
        fun image(description: String, view: View = root): android.widget.ImageView? {
            if (view is android.widget.ImageView && view.contentDescription == description) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                image(description, view.getChildAt(index))?.let { return it }
            }
            return null
        }
        controller.update(snapshot().copy(shiftOneShot = true))
        layout()
        val modifier = image("Shift")!!
        val singleWidth = modifier.layoutParams.width
        val buttons = modifier.parent as android.widget.LinearLayout
        assertEquals(2, buttons.childCount)
        assertSame(modifier, buttons.getChildAt(0))
        assertTrue(modifier.background is rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable)
        assertFalse(buttons.getChildAt(1).background is rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable)

        controller.update(snapshot().copy(shiftOneShot = true, ctrlLatchActive = true, altOneShot = true))
        layout()
        assertSame(modifier, image("Shift, Ctrl, Alt"))
        assertEquals(2, buttons.childCount)
        assertTrue(modifier.layoutParams.width > singleWidth)
        val tripleWidth = modifier.layoutParams.width
        controller.update(snapshot().copy(shiftOneShot = true, ctrlLatchActive = true, altOneShot = true,
            symPhysicallyPressed = true))
        layout()
        assertSame(modifier, image("Shift, Ctrl, Alt, SYM"))
        assertEquals(2, buttons.childCount)
        assertTrue(modifier.layoutParams.width > tripleWidth)

        controller.update(snapshot())
        layout()
        assertNull(image("Shift, Ctrl, Alt"))
        assertEquals(1, buttons.childCount)
        assertTrue(buttons.getChildAt(0).background is rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable)
    }

    @Test fun onlyAndroidIndicatorCollapsesAndRestoresHeight() {
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setStatusBarVariationsEnabled(context, false)
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_MENU_BAR))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.update(snapshot())
        val root = controller.getLayout()!!
        assertEquals(View.GONE, root.visibility)
        assertEquals(0, root.layoutParams.height)
        assertTrue(KeyboardBarConfiguration.read(context).androidIndicators)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        controller.update(snapshot())
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, root.layoutParams.height)
    }

    @Test fun ledOnlyHasNoSuggestionOrActionRowsInSlim() {
        SettingsManager.setSlimStatusBarSlotsLeft(context, emptyList())
        SettingsManager.setSlimStatusBarSlotsRight(context, emptyList())
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setStatusBarVariationsEnabled(context, false)
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        controller.update(snapshot())
        val root = controller.getLayout()!!
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(View.GONE, root.getChildAt(0).visibility)
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        assertEquals(android.graphics.Color.TRANSPARENT,
            (root.background as android.graphics.drawable.ColorDrawable).color)
        assertNotNull((root as ImeChromeLayout).ledOnlyReservedHeightPx)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(root.measuredHeight > 0)
        assertTrue(root.measuredHeight < 16 * context.resources.displayMetrics.density)
    }

    @Test fun explicitlyOpenedEmojiSurvivesAllPersistentContentOff() {
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setStatusBarVariationsEnabled(context, false)
        SettingsManager.setModifierIndicators(context, emptySet())
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.update(snapshot())
        assertEquals(View.GONE, controller.getLayout()!!.visibility)
        controller.update(snapshot().copy(symPage = 4))
        assertEquals(View.VISIBLE, controller.getLayout()!!.visibility)
        assertTrue(controller.hasTransientContent)
        controller.update(snapshot())
        assertEquals(View.GONE, controller.getLayout()!!.visibility)
    }

    @Test fun snippetSuggestionsAppearAndCollapseWithPersistentContentOff() {
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setStatusBarVariationsEnabled(context, false)
        SettingsManager.setModifierIndicators(context, emptySet())
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.update(snapshot())
        assertEquals(View.GONE, controller.getLayout()!!.visibility)
        controller.showExpansionSuggestions(listOf("Snippet choice")) {}
        assertTrue(controller.hasTransientContent)
        controller.update(snapshot())
        val root = controller.getLayout()!!
        assertEquals(View.VISIBLE, root.visibility)
        fun containsVisibleLabel(view: View): Boolean {
            if (view.visibility != View.VISIBLE) return false
            if (view is android.widget.TextView && view.text.toString() == "Snippet choice") return true
            return view is android.view.ViewGroup && (0 until view.childCount).any {
                containsVisibleLabel(view.getChildAt(it))
            }
        }
        assertTrue("Snippet row must actually be visible", containsVisibleLabel(root))
        controller.clearExpansionSuggestions()
        assertFalse(controller.hasTransientContent)
        controller.update(snapshot())
        assertEquals(View.GONE, root.visibility)
        assertEquals(0, root.layoutParams.height)
    }

    @Test fun ledStripStaysAtBottomWhenAndroidEnlargesTheWindowAfterMeasurement() {
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setStatusBarVariationsEnabled(context, false)
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.update(snapshot())
        val root = controller.getLayout()!!
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val surface = root.getChildAt(root.childCount - 1) as android.view.ViewGroup
        val stack = surface.getChildAt(0) as android.view.ViewGroup
        val enlargedHeight = stack.height + 15
        stack.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(enlargedHeight, View.MeasureSpec.EXACTLY))
        stack.layout(0, 0, 1080, enlargedHeight)
        val leds = stack.getChildAt(stack.childCount - 1)
        assertEquals("Extra window height must go above the LEDs", enlargedHeight, leds.bottom)
    }

    @Test fun ledOnlyReservesCornerHeightOnlyForCurvedRails() {
        val led = rocks.pkb.plektra.inputmethod.ui.LedStatusView(context).apply {
            bottomCornerRadiiPx = 100 to 100
            compactOnly = true
            followDisplayContour = false
        }
        val view = led.ensureView()
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(view.measuredHeight > 0)
        assertTrue(view.measuredHeight < 16 * context.resources.displayMetrics.density)
        led.followDisplayContour = true
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(view.measuredHeight >= 100)
    }
    @Test fun variationsTakeOverEmptyMenuEdgesAndReleaseThemWhenAMenuButtonReturns() {
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
        SettingsManager.setStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_NONE))
        SettingsManager.setStatusBarSlotsRight(context, listOf(SettingsManager.STATUS_BAR_BUTTON_NONE))
        SettingsManager.setStaticVariationBarModeEnabled(context, true)
        val bar = rocks.pkb.plektra.inputmethod.ui.VariationBarView(context, context.assets,
            buttonRegistry = rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry())
        bar.themeOverride = SettingsManager.getEffectiveKeyboardTheme(context,
            SettingsManager.KeyboardThemeTarget.HARDWARE).toKeyboardThemeColors()
        val view = bar.ensureView()
        val state = snapshot().copy(shouldDisableVariations = true)
        fun edges(): List<android.view.View> {
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.updateRows(view)
            val result = mutableListOf<android.view.View>()
            fun visit(v: android.view.View) {
                if (v.background is rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable) result += v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
            visit(view)
            return result
        }
        bar.showVariations(state, null)
        val emptyMenus = edges()
        assertEquals(2, emptyMenus.size)
        assertTrue(emptyMenus.all { it is android.widget.TextView })
        assertTrue((emptyMenus.first().background as rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable).leftEdge)
        assertTrue((emptyMenus.last().background as rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable).rightEdge)

        SettingsManager.setStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_HAMBURGER))
        bar.showVariations(state, null)
        val menuReturns = edges()
        assertEquals(2, menuReturns.size)
        assertTrue(menuReturns.first() is android.widget.ImageView)
        assertTrue(menuReturns.last() is android.widget.TextView)

        SettingsManager.setStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_NONE))
        bar.showVariations(state.copy(suggestions = listOf("word"), addWordCandidate = "word"), null)
        val single = edges().single().background as rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable
        assertTrue(single.leftEdge)
        assertTrue(single.rightEdge)
    }

    @Test fun roundedLedClearanceIsEnabledBeforeWindowInsetsArrive() {
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "titan2elite_qwerty")
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.showExpansionSuggestions(listOf("Sample")) {}
        controller.update(snapshot())
        val chrome = controller.getLayout()!! as ImeChromeLayout
        assertNull(chrome.bottomCornerRadiiPx)
        assertTrue("LED clearance must not depend on the timing of window insets", chrome.contourIntegratedIndicators)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [33], qualifiers = "w478dp-h533dp-360dpi")
    @Test fun slimStaticVariationsRemainReadableWithRequestedSwitcherAndRoundedInsets() {
        SettingsManager.setStaticVariationBarPreset(context, SettingsManager.STATIC_VARIATION_PRESET_DEV_CHOICE)
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
        val controller = slimVariationsController()
        val state = snapshot().copy(imeSwitcherShown = true)
        controller.update(state)
        val root = controller.getLayout()!!
        repeat(4) {
            controller.update(state)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1076, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            root.layout(0, 0, 1076, root.measuredHeight)
            root.viewTreeObserver.dispatchOnPreDraw()
            val bitmap = android.graphics.Bitmap.createBitmap(1076, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            try { root.draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
        }
        val slots = slotViews(root, DEV_CHOICE)
        assertEquals(DEV_CHOICE, slots.map { it.text.toString() })
        slots.forEach { slot ->
            assertTrue("Variation ${slot.text} must remain readable", slot.layout != null && slot.layout.getEllipsisCount(0) == 0)
        }
    }

}
