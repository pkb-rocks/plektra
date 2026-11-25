package rocks.pkb.plektra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClicksButtonBindingSyncPolicyTest {
    @Test
    fun nativeOutputIsConfirmedByDisabledFirmwareRemap() {
        val state = writableState(tabRemap = null)

        assertTrue(
            ClicksButtonBindingSyncPolicy.isConfirmed(
                state,
                ClicksButtonBindingTarget.RED,
                ClicksPowerKeyboardProtocol.nativeRemapOutput()
            )
        )
    }

    @Test
    fun desiredOutputMustMatchTheTargetRemap() {
        val dictation = ClicksPowerKeyboardProtocol.dictationRemapOutput()
        val state = writableState(geminiRemap = dictation)

        assertTrue(
            ClicksButtonBindingSyncPolicy.isConfirmed(
                state,
                ClicksButtonBindingTarget.MICROPHONE,
                dictation
            )
        )
        assertFalse(
            ClicksButtonBindingSyncPolicy.isConfirmed(
                state,
                ClicksButtonBindingTarget.KEYBOARD,
                dictation
            )
        )
    }

    @Test
    fun staleOrUnvalidatedSessionsCannotWritePendingBindings() {
        assertFalse(ClicksButtonBindingSyncPolicy.canWrite(writableState(stale = true)))
        assertFalse(ClicksButtonBindingSyncPolicy.canWrite(writableState(sessionValidated = false)))
        assertTrue(ClicksButtonBindingSyncPolicy.canWrite(writableState()))
    }

    @Test
    fun supersededWriteKeepsLatestRequestPending() {
        val first = ClicksPowerKeyboardProtocol.leftAltRemapOutput()
        val latest = ClicksPowerKeyboardProtocol.languageSwitchRemapOutput()

        assertEquals(
            ClicksButtonBindingCompletion.KEEP_PENDING,
            ClicksButtonBindingCompletionPolicy.resolve(
                attemptedOutput = first,
                latestDesiredOutput = latest,
                latestDesiredConfirmed = false
            )
        )
    }

    @Test
    fun latestRequestCompletesOnlyWhenItsOwnStateIsKnown() {
        val latest = ClicksPowerKeyboardProtocol.languageSwitchRemapOutput()

        assertEquals(
            ClicksButtonBindingCompletion.SUCCESS,
            ClicksButtonBindingCompletionPolicy.resolve(
                attemptedOutput = latest,
                latestDesiredOutput = latest,
                latestDesiredConfirmed = true
            )
        )
        assertEquals(
            ClicksButtonBindingCompletion.FAILURE,
            ClicksButtonBindingCompletionPolicy.resolve(
                attemptedOutput = latest,
                latestDesiredOutput = latest,
                latestDesiredConfirmed = false
            )
        )
    }

    @Test
    fun appliedBindingIsNotRewrittenAfterClicksAppAssignedAlt() {
        // Keyboard button assigned to Alt in the Clicks app: Alt remap enabled with Left Alt.
        val clicksAppAlt = byteArrayOf(0xe2.toByte(), 0x00)
        val state = writableState(
            specialKeyEnableFlags = ClicksPowerKeyboardProtocol.FLAG_ALT_REMAP_ENABLED,
            altRemap = clicksAppAlt
        )
        val applied = ClicksDesiredButtonBinding(
            "alt_toggle_keyboard_mode",
            ClicksPowerKeyboardProtocol.plektraActionRemapOutput(),
            pending = false
        )

        assertEquals(
            ClicksButtonBindingReconcileAction.NONE,
            ClicksButtonBindingSyncPolicy.reconcileAction(
                state,
                ClicksButtonBindingTarget.KEYBOARD,
                applied,
                failedInCurrentConnection = false
            )
        )
        assertFalse(ClicksButtonBindingSyncPolicy.isPending(state, ClicksButtonBindingTarget.KEYBOARD, applied))
        assertNull(ClicksButtonBindingSyncPolicy.displayedBinding(state, ClicksButtonBindingTarget.KEYBOARD, applied))
        assertTrue(
            ClicksButtonBindingSyncPolicy.currentOutput(state, ClicksButtonBindingTarget.KEYBOARD)!!
                .contentEquals(ClicksPowerKeyboardProtocol.leftAltRemapOutput())
        )
    }

    @Test
    fun appliedBindingStaysDisplayedWhileFirmwareStillMatches() {
        val state = writableState(altRemap = null)
        val applied = ClicksDesiredButtonBinding(
            "alt_native",
            ClicksPowerKeyboardProtocol.nativeRemapOutput(),
            pending = false
        )

        assertSame(
            applied,
            ClicksButtonBindingSyncPolicy.displayedBinding(state, ClicksButtonBindingTarget.KEYBOARD, applied)
        )
    }

    @Test
    fun pendingBindingIsWrittenOnlyInFreshSessionAndThenMarkedApplied() {
        val alt = ClicksPowerKeyboardProtocol.leftAltRemapOutput()
        val pending = ClicksDesiredButtonBinding("alt_alt", alt)
        fun action(state: ClicksPowerKeyboardState, failed: Boolean = false) =
            ClicksButtonBindingSyncPolicy.reconcileAction(
                state,
                ClicksButtonBindingTarget.KEYBOARD,
                pending,
                failedInCurrentConnection = failed
            )

        // A resumed connection still shows the cached snapshot until the initial reads finish.
        assertEquals(ClicksButtonBindingReconcileAction.NONE, action(writableState(stale = true)))
        assertEquals(ClicksButtonBindingReconcileAction.NONE, action(writableState(sessionValidated = false)))
        assertEquals(ClicksButtonBindingReconcileAction.WRITE, action(writableState()))
        assertEquals(ClicksButtonBindingReconcileAction.NONE, action(writableState(), failed = true))
        assertEquals(
            ClicksButtonBindingReconcileAction.MARK_APPLIED,
            action(
                writableState(
                    specialKeyEnableFlags = ClicksPowerKeyboardProtocol.FLAG_ALT_REMAP_ENABLED,
                    altRemap = alt
                )
            )
        )
        assertTrue(ClicksButtonBindingSyncPolicy.isPending(writableState(), ClicksButtonBindingTarget.KEYBOARD, pending))
        assertSame(
            pending,
            ClicksButtonBindingSyncPolicy.displayedBinding(writableState(), ClicksButtonBindingTarget.KEYBOARD, pending)
        )
    }

    @Test
    fun unreadRemapStateKeepsSelectionAndHasNoCurrentOutput() {
        val state = ClicksPowerKeyboardState()
        val applied = ClicksDesiredButtonBinding(
            "alt_toggle_keyboard_mode",
            ClicksPowerKeyboardProtocol.plektraActionRemapOutput(),
            pending = false
        )

        assertSame(
            applied,
            ClicksButtonBindingSyncPolicy.displayedBinding(state, ClicksButtonBindingTarget.KEYBOARD, applied)
        )
        assertNull(ClicksButtonBindingSyncPolicy.currentOutput(state, ClicksButtonBindingTarget.KEYBOARD))
    }

    @Test
    fun readStateDistinguishesFreshReadsFromLastKnownAssignments() {
        assertEquals(ClicksButtonBindingReadState.UNKNOWN, ClicksButtonBindingSyncPolicy.readState(ClicksPowerKeyboardState()))
        assertEquals(
            ClicksButtonBindingReadState.CURRENT,
            ClicksButtonBindingSyncPolicy.readState(writableState().copy(connected = true))
        )
        // A refresh marks cached data stale until the complete read validated the session again.
        assertEquals(
            ClicksButtonBindingReadState.READING,
            ClicksButtonBindingSyncPolicy.readState(writableState(stale = true, sessionValidated = false).copy(connected = true))
        )
        assertEquals(
            ClicksButtonBindingReadState.LAST_KNOWN,
            ClicksButtonBindingSyncPolicy.readState(
                writableState(stale = true, sessionValidated = false).copy(connected = false, ready = false)
            )
        )
    }

    @Test
    fun freshReadOfAnExternalAssignmentIsShownAndNeverOverwritten() {
        val applied = ClicksDesiredButtonBinding(
            choiceId = "alt_language_switch",
            firmwareOutput = ClicksPowerKeyboardProtocol.languageSwitchRemapOutput(),
            pending = false
        )
        val external = writableState(altRemap = ClicksPowerKeyboardProtocol.leftAltRemapOutput())

        assertEquals(
            ClicksButtonBindingReconcileAction.NONE,
            ClicksButtonBindingSyncPolicy.reconcileAction(external, ClicksButtonBindingTarget.KEYBOARD, applied, false)
        )
        assertNull(ClicksButtonBindingSyncPolicy.displayedBinding(external, ClicksButtonBindingTarget.KEYBOARD, applied))
        assertFalse(ClicksButtonBindingSyncPolicy.isPending(external, ClicksButtonBindingTarget.KEYBOARD, applied))
    }

    private fun writableState(
        stale: Boolean = false,
        sessionValidated: Boolean = true,
        tabRemap: ByteArray? = null,
        geminiRemap: ByteArray? = null,
        altRemap: ByteArray? = null,
        specialKeyEnableFlags: Int = 0
    ) = ClicksPowerKeyboardState(
        ready = true,
        stale = stale,
        sessionValidated = sessionValidated,
        firmwareVersion = "1.0.9",
        specialKeyEnableFlags = specialKeyEnableFlags,
        tabRemap = tabRemap,
        geminiRemap = geminiRemap,
        altRemap = altRemap
    )
}
