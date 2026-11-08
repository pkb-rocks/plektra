package rocks.pkb.plektra.inputmethod.aospkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class KeyNeighborScorerTest {

    // 25.4 dpi makes one pixel one millimetre.
    private val dpi = 25.4f
    private val row = listOf(
        KeyNeighborScorer.KeyRect(0, 0f, 0f, 6f, 9f),
        KeyNeighborScorer.KeyRect(1, 6f, 0f, 12f, 9f),
        KeyNeighborScorer.KeyRect(2, 12f, 0f, 18f, 9f)
    )

    @Test
    fun centreHitRanksKeyFirstAndScoresSumToOne() {
        val scored = KeyNeighborScorer().score(9f, 4.5f, row, dpi, dpi)

        assertEquals(1, scored.first().index)
        assertEquals(1.0, scored.sumOf { exp(it.logProbability.toDouble()) }, 1e-4)
    }

    @Test
    fun boundaryTouchSplitsProbabilityEvenly() {
        val scored = KeyNeighborScorer().score(6f, 4.5f, row, dpi, dpi).associateBy { it.index }

        assertEquals(scored.getValue(0).logProbability, scored.getValue(1).logProbability, 1e-4f)
    }

    @Test
    fun wideKeyUsesLineCoreInsteadOfCentre() {
        val keys = listOf(
            KeyNeighborScorer.KeyRect(0, 0f, 0f, 60f, 9f),
            KeyNeighborScorer.KeyRect(1, 60f, 0f, 66f, 9f)
        )

        val scored = KeyNeighborScorer().score(52f, 4.5f, keys, dpi, dpi)

        assertEquals(0, scored.first().index)
    }

    @Test
    fun distantKeysAreExcluded() {
        val keys = row + KeyNeighborScorer.KeyRect(3, 100f, 0f, 106f, 9f)

        val scored = KeyNeighborScorer().score(3f, 4.5f, keys, dpi, dpi)

        assertTrue(scored.none { it.index == 3 })
    }
}
