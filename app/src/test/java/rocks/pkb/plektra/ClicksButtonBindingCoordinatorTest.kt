package rocks.pkb.plektra

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClicksButtonBindingCoordinatorTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val target = ClicksButtonBindingTarget.KEYBOARD
    private val alt = ClicksPowerKeyboardProtocol.leftAltRemapOutput()
    private val plektraAction = ClicksPowerKeyboardProtocol.plektraActionRemapOutput()
    private lateinit var keyboard: FakeKeyboard
    private lateinit var coordinator: ClicksButtonBindingCoordinator

    @Before
    fun setUp() {
        context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        keyboard = FakeKeyboard(writableClicksState(altRemap = alt))
        coordinator = ClicksButtonBindingCoordinator(
            store = SettingsClicksButtonBindingStore(context),
            keyboardState = { keyboard.state },
            writer = { keyboard }
        )
        keyboard.onPublish = coordinator::reconcile
    }

    @Test
    fun selectionDuringAutomaticWriteIsAppliedAfterTheOlderWrite() {
        // An offline selection of the Plektra action is synced automatically on reconnect while the
        // firmware still holds Alt.
        SettingsManager.setClicksDesiredButtonBinding(
            context,
            target,
            ClicksDesiredButtonBinding("alt_direct_action", plektraAction)
        )
        coordinator.reconcile()
        assertEquals(1, keyboard.writes.size)

        // Alt is selected again before that write completes.
        val results = mutableListOf<Boolean>()
        val status = coordinator.request(target, ClicksDesiredButtonBinding("alt_alt", alt)) { results += it }

        assertEquals(ClicksButtonBindingRequestStatus.APPLYING, status)
        assertTrue(desired().pending)
        // A fresh read still shows Alt while the older write is in flight.
        coordinator.reconcile()
        assertTrue(desired().pending)
        assertEquals(1, keyboard.writes.size)

        keyboard.completeNext(success = true)

        assertEquals(emptyList<Boolean>(), results)
        assertTrue(desired().pending)
        assertEquals(2, keyboard.writes.size)
        assertTrue(keyboard.writes[1].contentEquals(alt))

        keyboard.completeNext(success = true)

        assertEquals(listOf(true), results)
        assertTrue(keyboard.state.altRemap!!.contentEquals(alt))
        assertFalse(desired().pending)
        assertEquals("alt_alt", desired().choiceId)
        coordinator.reconcile()
        assertEquals(2, keyboard.writes.size)
    }

    @Test
    fun selectionMatchingTheKeyboardIsConfirmedWhenTheOlderWriteFails() {
        SettingsManager.setClicksDesiredButtonBinding(
            context,
            target,
            ClicksDesiredButtonBinding("alt_direct_action", plektraAction)
        )
        coordinator.reconcile()
        val results = mutableListOf<Boolean>()
        coordinator.request(target, ClicksDesiredButtonBinding("alt_alt", alt)) { results += it }

        keyboard.completeNext(success = false)

        assertEquals(listOf(true), results)
        assertFalse(desired().pending)
        assertEquals(1, keyboard.writes.size)
    }

    @Test
    fun selectionMatchingTheKeyboardWithoutWriteInFlightIsConfirmedImmediately() {
        val status = coordinator.request(target, ClicksDesiredButtonBinding("alt_alt", alt)) {}

        assertEquals(ClicksButtonBindingRequestStatus.CONFIRMED, status)
        assertFalse(desired().pending)
        assertTrue(keyboard.writes.isEmpty())
    }

    private fun desired() = requireNotNull(SettingsManager.getClicksDesiredButtonBinding(context, target))

    /** Mirrors the GATT client: the written state is published before the completion callback. */
    private class FakeKeyboard(var state: ClicksPowerKeyboardState) : ClicksButtonRemapWriter {
        var onPublish: () -> Unit = {}
        val writes = mutableListOf<ByteArray>()
        private val queue = ArrayDeque<Pair<ByteArray, (Boolean) -> Unit>>()

        override fun writeButtonRemap(command: Int, output: ByteArray, onComplete: (Boolean) -> Unit) {
            assertEquals(ClicksPowerKeyboardProtocol.COMMAND_ALT_REMAP, command)
            writes += output.copyOf()
            queue.addLast(output.copyOf() to onComplete)
        }

        fun completeNext(success: Boolean) {
            val (output, onComplete) = queue.removeFirst()
            if (success) {
                state = state.copy(altRemap = output)
                onPublish()
            }
            onComplete(success)
        }
    }
}

internal fun writableClicksState(
    tabRemap: ByteArray? = null,
    geminiRemap: ByteArray? = null,
    altRemap: ByteArray? = null
) = ClicksPowerKeyboardState(
    ready = true,
    sessionValidated = true,
    firmwareVersion = "1.0.9",
    specialKeyEnableFlags = 0,
    tabRemap = tabRemap,
    geminiRemap = geminiRemap,
    altRemap = altRemap
)
