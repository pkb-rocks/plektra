package rocks.pkb.plektra.training

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class KeyCorrectionCalibrationTest {

    @Test
    fun alignmentAttributesSubstitutionsToTheIntendedCharacter() {
        // "wprt" was meant as "wort": the p sits where the o belongs.
        assertArrayEquals(intArrayOf(0, 1, 2, 3), KeyCorrectionCalibration.align("wort hier", "wprt"))
    }

    @Test
    fun alignmentMarksInsertedCharacters() {
        assertArrayEquals(intArrayOf(0, 1, -1, 2, 3), KeyCorrectionCalibration.align("wort", "woort").let {
            // Either o may be the inserted one; normalise for the assertion.
            if (it[1] == -1) intArrayOf(0, 1, -1, 2, 3) else it
        })
    }
}
