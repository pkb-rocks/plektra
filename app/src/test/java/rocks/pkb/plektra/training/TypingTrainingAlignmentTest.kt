package rocks.pkb.plektra.training

import org.junit.Assert.assertEquals
import org.junit.Test

class TypingTrainingAlignmentTest {

    private val targets = listOf("sind", "nächstes", "Wort", "hier", "fertig")

    @Test
    fun regularTypingAdvancesOneWordPerSpace() {
        assertEquals(2, TypingTrainingAlignment.align("sind nächstes ", targets, ignoreCase = true).currentTarget)
    }

    @Test
    fun missingSpaceConsumesBothTargets() {
        assertEquals(2, TypingTrainingAlignment.align("sindnächstes ", targets, ignoreCase = true).currentTarget)
    }

    @Test
    fun missingSpaceWithTyposStillRealigns() {
        assertEquals(3, TypingTrainingAlignment.align("sindnächstrs wort ", targets, ignoreCase = true).currentTarget)
    }

    @Test
    fun extraSpaceInsideWordStaysOnThatTarget() {
        val result = TypingTrainingAlignment.align("sind näch stes wort ", targets, ignoreCase = true)
        assertEquals(listOf(0, 1, 1, 2), result.tokenTargets)
        assertEquals(3, result.currentTarget)
    }

    @Test
    fun doubleSpaceIsIgnored() {
        assertEquals(2, TypingTrainingAlignment.align("sind  nächstes ", targets, ignoreCase = true).currentTarget)
    }

    @Test
    fun ordinaryTypoDoesNotSkipWords() {
        assertEquals(2, TypingTrainingAlignment.align("sinf nächstrs ", targets, ignoreCase = true).currentTarget)
    }

    @Test
    fun caseSensitiveModeCountsCaseDifferences() {
        assertEquals(1, TypingTrainingAlignment.editDistance("wort", "Wort"))
    }

    @Test
    fun ignoredAccentsCompareBaseLetters() {
        assertEquals("cafe deja lodz sono", TypingTrainingAlignment.fold("Café déjà Łódź sonó", ignoreCase = true, ignoreAccents = true))
        assertEquals("Café", TypingTrainingAlignment.fold("Café", ignoreCase = false, ignoreAccents = false))
        val french = listOf("été", "déjà", "là")
        val typed = "ete deja "
        assertEquals(listOf(0, 1), TypingTrainingAlignment.align(typed, french, ignoreCase = true, ignoreAccents = true).tokenTargets)
        assertEquals("ete".length, TypingTrainingAlignment.fold("été", ignoreCase = true, ignoreAccents = true).length)
    }

    @Test
    fun foldingKeepsOffsetsForExpandingLowercase() {
        // String.lowercase() turns »İ« into »i̇« (two characters).
        assertEquals("istanbul", TypingTrainingAlignment.fold("İstanbul", ignoreCase = true, ignoreAccents = true))
        assertEquals("İstanbul".length, TypingTrainingAlignment.fold("İstanbul", ignoreCase = true, ignoreAccents = false).length)
    }
}
