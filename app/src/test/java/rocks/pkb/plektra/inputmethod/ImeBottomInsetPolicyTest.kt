package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test
import rocks.pkb.plektra.inputmethod.ImeBottomInsetPolicy.CompactGestureClearance.HALF
import rocks.pkb.plektra.inputmethod.ImeBottomInsetPolicy.CompactGestureClearance.NONE

class ImeBottomInsetPolicyTest {
    // Measured below a hidden caption bar: stable gestures 96 px, visible mandatory gestures 64 px.
    @Test fun touchDevicesLetTheBottomRowIntoTheUpperHalfOfTheGestureZone() {
        assertEquals(32, ImeBottomInsetPolicy.resolve(0, 96, 64, 0, true, HALF))
        assertEquals(33, ImeBottomInsetPolicy.resolve(0, 96, 65, 0, true, HALF))
    }
    @Test fun hardwareKeyboardDevicesReserveNothingForTheGestureZone() {
        assertEquals(0, ImeBottomInsetPolicy.resolve(0, 96, 64, 0, true, NONE))
    }
    @Test fun visibleCaptionKeepsTheStableGestureAreaWhateverTheClearance() {
        assertEquals(96, ImeBottomInsetPolicy.resolve(0, 96, 64, 0, false, HALF))
        assertEquals(96, ImeBottomInsetPolicy.resolve(0, 96, 64, 0, false, NONE))
    }
    @Test fun navigationAndCutoutRemainProtected() {
        for (clearance in listOf(HALF, NONE)) {
            assertEquals(120, ImeBottomInsetPolicy.resolve(120, 96, 64, 0, true, clearance))
            assertEquals(140, ImeBottomInsetPolicy.resolve(0, 96, 64, 140, true, clearance))
        }
    }
    @Test fun withoutGestureZoneNothingIsReserved() {
        assertEquals(0, ImeBottomInsetPolicy.resolve(0, 0, 0, 0, true))
    }
}
