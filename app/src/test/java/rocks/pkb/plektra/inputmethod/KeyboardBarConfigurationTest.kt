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

    @Test fun slimSoftwareKeyboardAndSymKeepTheirOwnRows() {
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

    @Test fun sharedRowPrefersDynamicVariationsOverWordSuggestions() {
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        KeyboardBarConfiguration.setArrangement(context, rocks.pkb.plektra.KeyboardBarArrangement.SHARED)
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.update(snapshot().copy(variations = listOf("à", "â"), suggestions = listOf("alphabet")))
        val labels = mutableListOf<String>()
        fun collect(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is android.widget.TextView) labels.add(view.text.toString())
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(controller.getLayout()!!)
        assertTrue(labels.contains("à"))
        assertTrue(labels.contains("â"))
        assertFalse(labels.contains("alphabet"))
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

}
