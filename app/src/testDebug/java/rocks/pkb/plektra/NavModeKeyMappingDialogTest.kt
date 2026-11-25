package rocks.pkb.plektra

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.data.mappings.KeyMappingLoader

/** Issues #162 and #224: every type must be reachable and savable on Titan 2 Elite-sized windows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavModeKeyMappingDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var saved: KeyMappingLoader.CtrlMapping? = null
    private var saveCalls = 0

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun open(current: KeyMappingLoader.CtrlMapping? = KeyMappingLoader.CtrlMapping("keycode", "DPAD_UP")) {
        compose.setContent {
            KeyMappingDialog(
                keyCode = KeyEvent.KEYCODE_E,
                currentMapping = current,
                defaultMapping = KeyMappingLoader.CtrlMapping("keycode", "DPAD_UP"),
                onDismiss = {},
                onSave = { saved = it; saveCalls++ }
            )
        }
        compose.waitForIdle()
    }

    private fun node(text: String) = compose.onNode(hasText(text) and hasClickAction())

    /** Scrolls only where the node sits in a scrollable container; the visibility checks decide the rest. */
    private fun SemanticsNodeInteraction.reveal() = apply { runCatching { performScrollTo() } }

    /** Visible in the dialog window without clipping; Save must never need more than scrolling the list. */
    private fun SemanticsNodeInteraction.assertFullyVisible(label: String) {
        val n = fetchSemanticsNode()
        val bounds = n.boundsInRoot
        assertTrue("$label is clipped: $bounds vs ${n.size}",
            bounds.height >= n.size.height - 1f && bounds.width >= n.size.width - 1f)
    }

    private fun assertTextNotTruncated(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse("\"$text\" is truncated", layouts.single().hasVisualOverflow)
        compose.onNodeWithText(text, useUnmergedTree = true).assertFullyVisible(text)
    }

    private val typeLabels get() = listOf(
        string(R.string.nav_mode_keycode), string(R.string.nav_mode_action),
        string(R.string.nav_mode_native_ctrl), string(R.string.nav_mode_command), string(R.string.nav_mode_none)
    )

    private fun assertEveryTypeReachableAndSavable() {
        for (type in typeLabels) {
            node(type).reveal().assertFullyVisible(type)
            node(type).performClick()
            compose.waitForIdle()
            node(string(R.string.nav_mode_save)).assertFullyVisible("Save on $type")
            node(string(R.string.nav_mode_cancel)).assertFullyVisible("Cancel on $type")
        }
    }

    // Titan 2 Elite at its default 300 dpi with enlarged system text.
    @Test @Config(qualifiers = "w576dp-h640dp-300dpi")
    fun typesAndActionsStayReachableAtTitan2EliteDefaultDensityWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        open()
        assertEveryTypeReachableAndSavable()
    }

    // Titan 2 Elite at 360 dpi with enlarged system text: the type chips no longer fit one row.
    @Test @Config(qualifiers = "w480dp-h533dp-360dpi")
    fun typesAndActionsStayReachableAtTitan2Elite360DpiWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        open()
        assertEveryTypeReachableAndSavable()
        node(string(R.string.nav_mode_use_default, "DPAD_UP")).reveal().assertFullyVisible("Use default")
    }

    // Titan 2 Elite at 360 dpi while the keyboard bar takes part of the window.
    @Test @Config(qualifiers = "w480dp-h400dp-360dpi")
    fun typesAndActionsStayReachableInShortWindow() {
        open()
        assertEveryTypeReachableAndSavable()
    }

    @Test @Config(qualifiers = "w480dp-h533dp-360dpi")
    fun actionSelectionIsSaved() {
        open()
        node(string(R.string.nav_mode_action)).reveal().performClick()
        node(string(R.string.nav_mode_action_paste)).reveal().performClick()
        node(string(R.string.nav_mode_save)).performClick()
        assertEquals(KeyMappingLoader.CtrlMapping("action", "paste"), saved)
    }

    @Test @Config(qualifiers = "w480dp-h533dp-360dpi")
    fun commandSelectionIsSavedAndItsLabelIsReadable() {
        open()
        node(string(R.string.nav_mode_command)).reveal().performClick()
        val label = "Plektra: Toggle Keyboard Mode"
        node(label).reveal().performClick()
        assertTextNotTruncated(label)
        node(string(R.string.nav_mode_save)).performClick()
        assertEquals(KeyMappingLoader.CtrlMapping("command", "plektra.toggle_software_keyboard_mode"), saved)
    }

    @Test @Config(qualifiers = "w480dp-h533dp-360dpi")
    fun switchingTypeWithoutChoosingAValueCannotSaveAsNone() {
        open()
        node(string(R.string.nav_mode_action)).reveal().performClick()
        node(string(R.string.nav_mode_action_copy)).reveal().performClick()
        node(string(R.string.nav_mode_keycode)).reveal().performClick()
        node(string(R.string.nav_mode_save)).assertIsNotEnabled().performClick()
        assertEquals(0, saveCalls)

        node("DPAD_DOWN").reveal().performClick()
        node(string(R.string.nav_mode_save)).assertIsEnabled().performClick()
        assertEquals(KeyMappingLoader.CtrlMapping("keycode", "DPAD_DOWN"), saved)
    }
}
