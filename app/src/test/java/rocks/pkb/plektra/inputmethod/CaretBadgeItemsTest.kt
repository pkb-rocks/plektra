package rocks.pkb.plektra.inputmethod

import rocks.pkb.plektra.inputmethod.ui.CaretBadgeView.Item
import rocks.pkb.plektra.inputmethod.ui.CaretBadgeView.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaretBadgeItemsTest {
    private fun snapshot(
        capsLock: Boolean = false,
        shiftHeld: Boolean = false,
        shiftOneShot: Boolean = false,
        ctrlLatch: Boolean = false,
        ctrlHeld: Boolean = false,
        ctrlOneShot: Boolean = false,
        ctrlFromNavMode: Boolean = false,
        altLatch: Boolean = false,
        altHeld: Boolean = false,
        altOneShot: Boolean = false,
        symPage: Int = 0
    ) = StatusBarController.StatusSnapshot(
        capsLockEnabled = capsLock,
        shiftPhysicallyPressed = shiftHeld,
        shiftOneShot = shiftOneShot,
        ctrlLatchActive = ctrlLatch,
        ctrlPhysicallyPressed = ctrlHeld,
        ctrlOneShot = ctrlOneShot,
        ctrlLatchFromNavMode = ctrlFromNavMode,
        altLatchActive = altLatch,
        altPhysicallyPressed = altHeld,
        altOneShot = altOneShot,
        symPage = symPage
    )

    @Test
    fun nothingArmed_showsNothing() {
        assertTrue(caretBadgeItemsFor(snapshot()).isEmpty())
    }

    @Test
    fun shift_isFaintWhenHeld_plainWhenOneShot_lockedWhenCapsLock() {
        assertEquals(
            listOf(Item(Shape.ARROW, null, locked = false, faint = true)),
            caretBadgeItemsFor(snapshot(shiftHeld = true))
        )
        assertEquals(
            listOf(Item(Shape.ARROW, null, locked = false, faint = false)),
            caretBadgeItemsFor(snapshot(shiftOneShot = true, shiftHeld = true))
        )
        assertEquals(
            listOf(Item(Shape.ARROW, null, locked = true, faint = false)),
            caretBadgeItemsFor(snapshot(capsLock = true, shiftOneShot = true))
        )
    }

    @Test
    fun alt_usesTheOptionSymbolAndTheSameThreeStates() {
        assertEquals(
            listOf(Item(Shape.ALT, null, locked = true, faint = false)),
            caretBadgeItemsFor(snapshot(altLatch = true, altOneShot = true))
        )
        assertEquals(
            listOf(Item(Shape.ALT, null, locked = false, faint = false)),
            caretBadgeItemsFor(snapshot(altOneShot = true))
        )
        assertEquals(
            listOf(Item(Shape.ALT, null, locked = false, faint = true)),
            caretBadgeItemsFor(snapshot(altHeld = true))
        )
    }

    @Test
    fun ctrlLatchedByNavMode_isNotReported() {
        assertTrue(
            caretBadgeItemsFor(snapshot(ctrlLatch = true, ctrlFromNavMode = true)).isEmpty()
        )
        assertEquals(
            listOf(Item(Shape.TEXT, "CTRL", locked = true, faint = false)),
            caretBadgeItemsFor(snapshot(ctrlLatch = true))
        )
    }

    @Test
    fun navModeCtrl_doesNotHideAPressOnTopOfIt() {
        assertEquals(
            listOf(Item(Shape.TEXT, "CTRL", locked = false, faint = true)),
            caretBadgeItemsFor(snapshot(ctrlLatch = true, ctrlFromNavMode = true, ctrlHeld = true))
        )
    }

    @Test
    fun severalModifiers_areListedShiftAltCtrlSym() {
        assertEquals(
            listOf(
                Item(Shape.ARROW, null, locked = false, faint = false),
                Item(Shape.ALT, null, locked = false, faint = false),
                Item(Shape.TEXT, "CTRL", locked = false, faint = false),
                Item(Shape.TEXT, "SYM", locked = false, faint = false)
            ),
            caretBadgeItemsFor(
                snapshot(shiftOneShot = true, altOneShot = true, ctrlOneShot = true, symPage = 2)
            )
        )
    }
}
