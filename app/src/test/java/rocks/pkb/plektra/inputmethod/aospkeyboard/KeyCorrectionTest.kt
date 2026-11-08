package rocks.pkb.plektra.inputmethod.aospkeyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCorrectionTest {

    // 25.4 dpi: one pixel is one millimetre. Keys e, r, t are 6 mm wide; 3 above e is a digit.
    private val dpi = 25.4f
    private val keys = listOf(
        KeyCorrection.Candidate(0, "e", 0f, 10f, 6f, 19f),
        KeyCorrection.Candidate(1, "r", 6f, 10f, 12f, 19f),
        KeyCorrection.Candidate(2, "t", 12f, 10f, 18f, 19f),
        KeyCorrection.Candidate(3, "3", 0f, 0f, 6f, 9f)
    )

    @After
    fun reset() {
        KeyCorrection.charContext = null
    }

    @Test
    fun clearHitIsNeverReDecided() {
        KeyCorrection.charContext = CharContext { mapOf('e' to 100f) }
        val decision = KeyCorrection.decide(9f, 14.5f, keys, 1, dpi, dpi, null, "d")
        assertEquals(1, decision.index)
        assertEquals("clear_hit", decision.reason)
    }

    @Test
    fun boundaryTouchFollowsCharacterContext() {
        KeyCorrection.charContext = CharContext { prefix -> if (prefix == "d") mapOf('e' to 100f, 'r' to 1f) else null }
        // 0.3 mm inside r, right at the e|r boundary.
        assertEquals(0, KeyCorrection.decide(6.3f, 14.5f, keys, 1, dpi, dpi, null, "d").index)
    }

    @Test
    fun withoutPrefixOnlyGeometryDecides() {
        KeyCorrection.charContext = CharContext { mapOf('e' to 100f) }
        assertEquals(1, KeyCorrection.decide(6.3f, 14.5f, keys, 1, dpi, dpi, null, "").index)
    }

    @Test
    fun calibratedOffsetMovesTheBoundary() {
        // This user hits r 2 mm to the left of its centre: a touch at 7 mm belongs to r, not e.
        val profile = KeyCorrectionProfile("p", 3f, 2.5f, mapOf("r" to KeyOffset(-2f, 0f, 40)), 1, 40, 0)
        assertEquals(1, KeyCorrection.decide(5.6f, 14.5f, keys, 0, dpi, dpi, profile, "").index)
    }

    @Test
    fun digitTouchedInsideAWordBecomesTheLetterBelow() {
        KeyCorrection.charContext = CharContext { mapOf('e' to 100f, 'r' to 10f) }
        // Lower edge of the number row above e, while typing a word.
        assertEquals(0, KeyCorrection.decide(3f, 8.8f, keys, 3, dpi, dpi, null, "zw").index)
    }
}
