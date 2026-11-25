package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.KeyboardBarProfile
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.clipboard.ClipboardHistoryManager
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonId
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry

/** Android's IME switcher request (API 36+) on each keyboard bar surface. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeSwitcherButtonTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private var pickerOpened = 0

    @Before fun reset() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setTitan2LayoutEnabled(context, false)
        ImeSwitcher.openForTests = { pickerOpened++ }
    }

    @After fun restore() {
        ImeSwitcher.openForTests = null
    }

    private fun snapshot(
        requested: Boolean,
        symPage: Int = 0,
        clipboardOverlay: Boolean = false,
        navMode: Boolean = false
    ) = StatusBarController.StatusSnapshot(
        capsLockEnabled = false, shiftPhysicallyPressed = false, shiftOneShot = false,
        ctrlLatchActive = navMode, ctrlPhysicallyPressed = false, ctrlOneShot = false,
        ctrlLatchFromNavMode = navMode, altLatchActive = false, altPhysicallyPressed = false,
        altOneShot = false, symPage = symPage, clipboardOverlay = clipboardOverlay,
        imeSwitcherShown = requested
    )

    private val symMappings = mapOf(KeyEvent.KEYCODE_Q to "1", KeyEvent.KEYCODE_A to "*", KeyEvent.KEYCODE_Z to "(")

    private fun hardwareController() = StatusBarController(
        context, StatusBarController.Mode.CANDIDATES_ONLY, clipboardHistoryManager = ClipboardHistoryManager(context)
    )

    @Test fun theRegistryAddsTheSwitcherOnceAndNeverCountsItAsConfigured() {
        SettingsManager.setSlimStatusBarSlotsLeft(context, emptyList())
        SettingsManager.setSlimStatusBarSlotsRight(context, emptyList())
        SettingsManager.setStatusBarSlotsRight(context, listOf(SettingsManager.STATUS_BAR_BUTTON_KEYBOARD_PICKER))
        val registry = StatusBarButtonRegistry()
        assertTrue(registry.getEnabledSlimButtons(context).isEmpty())

        registry.imeSwitcherShown = true
        assertEquals(listOf(StatusBarButtonRegistry.IME_SWITCHER), registry.getEnabledSlimButtons(context))
        assertFalse("the switcher alone does not keep the window open", registry.hasConfiguredSlimButtons(context))
        assertEquals("a configured picker is not duplicated", 1,
            registry.getEnabledButtons(context).count { it.id == StatusBarButtonId.KeyboardPicker })
    }

    @Test fun theSlimRowCarriesTheSwitcherBesideConfiguredButtons() {
        SettingsManager.setSlimStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_SETTINGS))
        SettingsManager.setSlimStatusBarSlotsRight(context, emptyList())
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        controller.update(snapshot(requested = false))
        assertEquals(0, visibleSwitchers(controller))

        controller.update(snapshot(requested = true))
        assertEquals(1, visibleSwitchers(controller))
        assertTrue(visibleDescriptions(controller).contains(context.getString(R.string.status_bar_button_settings_description)))

        controller.update(snapshot(requested = false))
        assertEquals("FALSE hides it again", 0, visibleSwitchers(controller))
    }

    @Test fun aSlimBarWithoutButtonsShowsTheSwitcherInItsRow() {
        SettingsManager.setSlimStatusBarSlotsLeft(context, emptyList())
        SettingsManager.setSlimStatusBarSlotsRight(context, emptyList())
        KeyboardBarConfiguration.setSlimProfile(context, KeyboardBarProfile(false, false, false, false, false))
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.setSlimModeActive(true)
        controller.update(snapshot(requested = true))
        assertFalse(controller.hasConfiguredMenuButtons)
        assertEquals(1, visibleSwitchers(controller))
    }

    @Test fun theExpandedBarCarriesTheSwitcherInItsButtonRow() {
        val controller = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)
        controller.update(snapshot(requested = true))
        assertEquals(1, visibleSwitchers(controller))
        controller.update(snapshot(requested = false))
        assertEquals(0, visibleSwitchers(controller))
    }

    @Test fun theSoftwareKeyboardShowsTheSwitcherAboveItsKeys() {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        val controller = StatusBarController(context, StatusBarController.Mode.INPUT_VIEW)
        controller.update(snapshot(requested = true))
        assertEquals("in the action row", 1, visibleSwitchers(controller))
        controller.update(snapshot(requested = false))
        assertEquals(0, visibleSwitchers(controller))

        SettingsManager.setStatusBarVariationsEnabled(context, false)
        controller.update(snapshot(requested = true))
        assertEquals("alone in the suggestions row without actions", 1, visibleSwitchers(controller))
        controller.update(snapshot(requested = false))
        assertEquals(0, visibleSwitchers(controller))
    }

    @Test fun theStandardSymPageShowsTheSwitcherInItsGridOnly() {
        val controller = hardwareController()
        controller.update(snapshot(requested = true), symMappings = symMappings)
        assertEquals(1, visibleSwitchers(controller))
        controller.update(snapshot(requested = true, symPage = 1), symMappings = symMappings)
        assertEquals("the SYM grid replaces the bar and carries the switcher", 1, visibleSwitchers(controller))
        assertEquals(1, visibleSurfaceSwitchers(controller))
        controller.update(snapshot(requested = false, symPage = 1), symMappings = symMappings)
        assertEquals("FALSE removes it from the grid", 0, visibleSwitchers(controller))
        controller.update(snapshot(requested = true, symPage = 2), symMappings = symMappings)
        assertEquals(1, visibleSwitchers(controller))
    }

    @Test fun theTitanSymPageKeepsItsOwnSwitcherWithoutADuplicate() {
        SettingsManager.setTitan2LayoutEnabled(context, true)
        val controller = hardwareController()
        controller.update(snapshot(requested = true, symPage = 1), symMappings = symMappings)
        assertEquals(1, visibleSwitchers(controller))
    }

    @Test fun theClipboardShowsTheSwitcherBesideItsClose() {
        val controller = hardwareController()
        for (clipboard in listOf(snapshot(requested = true, clipboardOverlay = true), snapshot(requested = true, symPage = 3))) {
            controller.update(clipboard, symMappings = symMappings)
            assertEquals(1, visibleSwitchers(controller))
            val switcher = surfaceSwitcher(controller)
            val close = field<View>(controller, "symSurfaceCloseButton")
            assertTrue(close.visibility == View.VISIBLE && switcher.visibility == View.VISIBLE)
            val params = switcher.layoutParams as android.widget.FrameLayout.LayoutParams
            assertEquals("one cell left of close", close.layoutParams.width +
                Math.round(4 * context.resources.displayMetrics.density), params.marginEnd)
            controller.update(snapshot(requested = false, symPage = clipboard.symPage, clipboardOverlay = clipboard.clipboardOverlay),
                symMappings = symMappings)
            assertEquals("FALSE removes it", 0, visibleSwitchers(controller))
            controller.update(snapshot(requested = true), symMappings = symMappings)
        }
    }

    @Test fun theEmojiPickerShowsTheSwitcherInItsTabRow() {
        val controller = hardwareController()
        controller.update(snapshot(requested = true, symPage = 4), symMappings = symMappings)
        assertEquals(1, visibleSwitchers(controller))
        assertEquals("the host accessory stays hidden", View.GONE, surfaceSwitcher(controller).visibility)
        controller.update(snapshot(requested = false, symPage = 4), symMappings = symMappings)
        assertEquals(0, visibleSwitchers(controller))
    }

    @Test fun surfaceChangesMoveTheSwitcherWithoutDuplicates() {
        val controller = hardwareController()
        val steps = listOf(
            snapshot(requested = true),
            snapshot(requested = true, symPage = 1),
            snapshot(requested = true, symPage = 3),
            snapshot(requested = true, clipboardOverlay = true),
            snapshot(requested = true, symPage = 2),
            snapshot(requested = true)
        )
        for (step in steps) {
            controller.update(step, symMappings = symMappings)
            assertEquals("page ${step.symPage} overlay ${step.clipboardOverlay}", 1, visibleSwitchers(controller))
        }
        assertEquals("back in the bar, the surface accessory is gone", View.GONE, surfaceSwitcher(controller).visibility)
    }

    @Test fun navModeShowsNoSurfaceAndOpensNothing() {
        val controller = hardwareController()
        controller.update(snapshot(requested = true), symMappings = symMappings)
        controller.update(snapshot(requested = true, navMode = true), symMappings = symMappings)
        assertEquals(View.GONE, controller.getLayout()?.visibility)
        assertEquals(0, visibleSwitchers(controller))
    }

    @Test fun tappingEachSwitcherOpensTheInputMethodPicker() {
        val controller = hardwareController()
        controller.update(snapshot(requested = true), symMappings = symMappings)
        clickSwitcher(controller)
        controller.update(snapshot(requested = true, symPage = 1), symMappings = symMappings)
        clickSwitcher(controller)
        controller.update(snapshot(requested = true, clipboardOverlay = true), symMappings = symMappings)
        clickSwitcher(controller)
        assertEquals(3, pickerOpened)
    }

    private fun clickSwitcher(controller: StatusBarController) {
        val descriptions = switcherDescriptions()
        val target = visibleViews(controller).single { it.contentDescription?.toString() in descriptions }
        // The picker button's click target is the button or its frame.
        generateSequence(target) { it.parent as? View }.first { it.hasOnClickListeners() }.performClick()
    }

    private fun switcherDescriptions() = setOf(
        context.getString(R.string.status_bar_button_keyboard_picker_description),
        context.getString(R.string.change_keyboard_button)
    )

    private fun visibleSwitchers(controller: StatusBarController): Int =
        visibleDescriptions(controller).count { it in switcherDescriptions() }

    private fun visibleSurfaceSwitchers(controller: StatusBarController): Int =
        visibleDescriptions(controller).count { it == context.getString(R.string.change_keyboard_button) }

    private fun surfaceSwitcher(controller: StatusBarController): View = field(controller, "symSurfaceSwitcherButton")

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(controller: StatusBarController, name: String): T =
        StatusBarController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller) as T

    private fun visibleDescriptions(controller: StatusBarController): List<String> =
        visibleViews(controller).mapNotNull { it.contentDescription?.toString() }

    private fun visibleViews(controller: StatusBarController): List<View> {
        val root = controller.getLayout() ?: return emptyList()
        val found = mutableListOf<View>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return found
    }
}
