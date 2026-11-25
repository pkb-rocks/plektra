package rocks.pkb.plektra

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClicksButtonBindingScreenSyncTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val pendingHint get() = context.getString(R.string.clicks_button_binding_pending)
    private val successMessage get() = context.getString(R.string.clicks_button_binding_success)

    @Before
    fun setUp() {
        // Robolectric creates a new Application per test; the process singleton must not keep
        // a previous test's preference store. Production has only one Application instance.
        resetControllerInitialization()
    }

    private fun resetControllerInitialization() {
        ClicksPowerKeyboardController::class.java.getDeclaredField("initialized").apply {
            isAccessible = true
            setBoolean(ClicksPowerKeyboardController, false)
        }
    }

    @After
    fun tearDown() {
        ClicksPowerKeyboardController.simulateKeyboardSessionForTest(null, ClicksPowerKeyboardState())
        resetControllerInitialization()
    }

    @Test
    fun appliedSelectionYieldsToFirmwareAssignmentReadLaterInTheSameScreen() {
        context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ClicksPowerKeyboardController.initialize(context)
        val keyboard = CompletingKeyboard()
        ClicksPowerKeyboardController.simulateKeyboardSessionForTest(keyboard, writableKeyboardState(tabRemap = null))
        compose.setContent {
            CompositionLocalProvider(LocalSettingHighlightId provides "clicks.buttons.red_button") {
                ClicksPowerKeyboardSettingsScreen(onBack = {})
            }
        }

        // Select Alt for the red button and let the keyboard apply it.
        compose.onNodeWithText(context.getString(R.string.clicks_red_native_action)).performClick()
        // Robolectric does not lay out the dropdown popup, so the item is activated semantically.
        compose.onAllNodesWithText(context.getString(R.string.clicks_button_mode_alt))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, keyboard.writes.size)
        keyboard.completeAll()
        compose.waitForIdle()

        compose.onNodeWithText(successMessage).assertExists()
        assertFalse(
            SettingsManager.getClicksDesiredButtonBinding(context, ClicksButtonBindingTarget.RED)!!.pending
        )
        assertTrue(compose.onAllNodesWithText(pendingHint).fetchSemanticsNodes().isEmpty())

        // The Clicks app restores the native output; a fresh read reports it while the screen stays open.
        ClicksPowerKeyboardController.simulateKeyboardSessionForTest(keyboard, writableKeyboardState(tabRemap = null))
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.clicks_red_native_action)).assertExists()
        assertTrue(compose.onAllNodesWithText(pendingHint).fetchSemanticsNodes().isEmpty())
        assertEquals(1, keyboard.writes.size)
    }

    /** Applies each write like the GATT client: publish the new state, then complete. */
    private class CompletingKeyboard : ClicksButtonRemapWriter {
        val writes = mutableListOf<ByteArray>()
        private val completions = ArrayDeque<Pair<ByteArray, (Boolean) -> Unit>>()

        override fun writeButtonRemap(command: Int, output: ByteArray, onComplete: (Boolean) -> Unit) {
            assertEquals(ClicksPowerKeyboardProtocol.COMMAND_TAB_REMAP, command)
            writes += output.copyOf()
            completions.addLast(output.copyOf() to onComplete)
        }

        fun completeAll() {
            while (completions.isNotEmpty()) {
                val (output, onComplete) = completions.removeFirst()
                ClicksPowerKeyboardController.simulateKeyboardSessionForTest(
                    this,
                    writableKeyboardState(tabRemap = output)
                )
                onComplete(true)
            }
        }
    }

    private companion object {
        fun writableKeyboardState(tabRemap: ByteArray?) = ClicksPowerKeyboardState(
            ready = true,
            sessionValidated = true,
            firmwareVersion = "1.0.9",
            specialKeyEnableFlags = 0,
            tabRemap = tabRemap
        )
    }
}
