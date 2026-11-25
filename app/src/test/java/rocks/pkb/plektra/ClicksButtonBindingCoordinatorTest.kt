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
        startAutomaticPlektraActionWrite()

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
    fun selectionMatchingTheKeyboardIsRewrittenWhenTheOlderWriteFails() {
        startAutomaticPlektraActionWrite()
        val results = mutableListOf<Boolean>()
        coordinator.request(target, ClicksDesiredButtonBinding("alt_alt", alt)) { results += it }

        // The first write step is rejected, so the keyboard still holds Alt; Plektra cannot tell.
        keyboard.completeNext(success = false)

        assertEquals(emptyList<Boolean>(), results)
        assertTrue(desired().pending)
        assertEquals(2, keyboard.writes.size)
        assertTrue(keyboard.writes[1].contentEquals(alt))

        keyboard.completeNext(success = true)

        assertEquals(listOf(true), results)
        assertFalse(desired().pending)
    }

    @Test
    fun partiallyFailedOlderWriteDoesNotConfirmTheLatestSelection() {
        startAutomaticPlektraActionWrite()
        // The remap bytes of the Plektra action are confirmed; with Alt remapping already enabled,
        // the keyboard button now emits the Plektra action.
        keyboard.confirmRemapBytesOfNext()
        val results = mutableListOf<Boolean>()
        val status = coordinator.request(target, ClicksDesiredButtonBinding("alt_alt", alt)) { results += it }
        assertEquals(ClicksButtonBindingRequestStatus.APPLYING, status)

        // The enable step fails without a disconnect; the published state still shows Alt.
        keyboard.failEnableFlagsOfNext()

        assertEquals(emptyList<Boolean>(), results)
        assertTrue(desired().pending)
        assertTrue(keyboard.state.altRemap!!.contentEquals(alt))
        // Republishing that outdated state does not confirm Alt either.
        coordinator.reconcile()
        assertTrue(desired().pending)
        // Alt is written again instead.
        assertEquals(2, keyboard.writes.size)
        assertTrue(keyboard.writes[1].contentEquals(alt))

        keyboard.completeNext(success = true)

        assertEquals(listOf(true), results)
        assertFalse(desired().pending)
        assertTrue(keyboard.firmwareOutput()!!.contentEquals(alt))
    }

    @Test
    fun selectionAfterTerminalPartialWriteIsWrittenAfterReconnect() {
        startAutomaticPlektraActionWrite()
        keyboard.confirmRemapBytesOfNext()
        val results = mutableListOf<Boolean>()
        coordinator.request(target, ClicksDesiredButtonBinding("alt_alt", alt)) { results += it }

        // The enable step times out; the session ends before the write is reported as failed.
        keyboard.timeOutNext()

        assertFalse(keyboard.state.ready)
        assertFalse(keyboard.state.sessionValidated)
        assertTrue(keyboard.state.altRemap!!.contentEquals(alt))
        assertEquals(listOf(false), results)
        assertTrue(desired().pending)
        assertEquals("alt_alt", desired().choiceId)
        coordinator.reconcile()
        assertTrue(desired().pending)
        assertEquals(1, keyboard.writes.size)

        // The new session reads the Plektra action and writes Alt again.
        coordinator.onConnectionReset()
        keyboard.readAgain()

        assertEquals(2, keyboard.writes.size)
        assertTrue(keyboard.writes[1].contentEquals(alt))
        assertTrue(desired().pending)

        keyboard.completeNext(success = true)

        assertFalse(desired().pending)
        assertTrue(keyboard.firmwareOutput()!!.contentEquals(alt))
    }

    @Test
    fun selectionAfterFailedOlderWriteIsConfirmedByAFreshRead() {
        startAutomaticPlektraActionWrite()
        coordinator.request(target, ClicksDesiredButtonBinding("alt_alt", alt)) {}

        // The first write step times out, so the keyboard keeps Alt.
        keyboard.timeOutNext()
        assertTrue(desired().pending)

        coordinator.onConnectionReset()
        keyboard.readAgain()

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

    private fun startAutomaticPlektraActionWrite() {
        // An offline selection of the Plektra action is synced automatically on reconnect while the
        // firmware still holds Alt.
        SettingsManager.setClicksDesiredButtonBinding(
            context,
            target,
            ClicksDesiredButtonBinding("alt_direct_action", plektraAction)
        )
        coordinator.reconcile()
        assertEquals(1, keyboard.writes.size)
    }

    private fun desired() = requireNotNull(SettingsManager.getClicksDesiredButtonBinding(context, target))

    /**
     * Mirrors the GATT client: a remap is written as its bytes and then its enable flag, and the
     * written state is published only after both steps succeeded, before the completion callback.
     */
    private class FakeKeyboard(var state: ClicksPowerKeyboardState) : ClicksButtonRemapWriter {
        var onPublish: () -> Unit = {}
        val writes = mutableListOf<ByteArray>()
        private var remapBytes: ByteArray? = state.altRemap?.copyOf()
        private var remapEnabled = state.altRemap != null
        private val queue = ArrayDeque<Pair<ByteArray, (Boolean) -> Unit>>()

        override fun writeButtonRemap(command: Int, output: ByteArray, onComplete: (Boolean) -> Unit) {
            assertEquals(ClicksPowerKeyboardProtocol.COMMAND_ALT_REMAP, command)
            assertFalse(output.all { it == 0.toByte() })
            writes += output.copyOf()
            queue.addLast(output.copyOf() to onComplete)
        }

        fun firmwareOutput(): ByteArray? = remapBytes.takeIf { remapEnabled }

        /** Completes both write steps, or rejects the first one without any change. */
        fun completeNext(success: Boolean) {
            if (!success) {
                queue.removeFirst().second(false)
                return
            }
            confirmRemapBytesOfNext()
            val (output, onComplete) = queue.removeFirst()
            remapEnabled = true
            publish(
                state.copy(
                    specialKeyEnableFlags = (state.specialKeyEnableFlags ?: 0) or
                        ClicksPowerKeyboardProtocol.FLAG_ALT_REMAP_ENABLED,
                    altRemap = output
                )
            )
            onComplete(true)
        }

        fun confirmRemapBytesOfNext() {
            remapBytes = queue.first().first.copyOf()
        }

        /** The keyboard rejects the enable step; nothing is published. */
        fun failEnableFlagsOfNext() {
            queue.removeFirst().second(false)
        }

        /** Like an operation timeout: the session ends first, then all operations fail. */
        fun timeOutNext() {
            publish(state.copy(connected = false, ready = false, sessionValidated = false))
            val aborted = queue.toList()
            queue.clear()
            aborted.forEach { (_, onComplete) -> onComplete(false) }
        }

        /** Like the initial reads of a session: unvalidated first, then the firmware state. */
        fun readAgain() {
            publish(state.copy(sessionValidated = false))
            val output = firmwareOutput()
            publish(
                state.copy(
                    connected = true,
                    ready = true,
                    sessionValidated = true,
                    specialKeyEnableFlags = if (output != null) ClicksPowerKeyboardProtocol.FLAG_ALT_REMAP_ENABLED else 0,
                    altRemap = output?.copyOf()
                )
            )
        }

        private fun publish(newState: ClicksPowerKeyboardState) {
            state = newState
            onPublish()
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
    specialKeyEnableFlags = (if (tabRemap != null) ClicksPowerKeyboardProtocol.FLAG_TAB_REMAP_ENABLED else 0) or
        (if (geminiRemap != null) ClicksPowerKeyboardProtocol.FLAG_GEMINI_REMAP_ENABLED else 0) or
        (if (altRemap != null) ClicksPowerKeyboardProtocol.FLAG_ALT_REMAP_ENABLED else 0),
    tabRemap = tabRemap,
    geminiRemap = geminiRemap,
    altRemap = altRemap
)
