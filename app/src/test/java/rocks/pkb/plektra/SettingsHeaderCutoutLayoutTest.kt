package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header row placement around display cutouts, in px of a 1080 px wide row. */
class SettingsHeaderCutoutLayoutTest {
    private fun arrange(cutouts: List<IntRange>, middlePreferred: Int = 300) = SettingsHeaderCutoutLayout.arrange(
        width = 1080, startWidth = 144, endWidth = 144, middlePreferred = middlePreferred, middleMin = 360,
        padStart = 12, padEnd = 24, cutouts = cutouts
    )

    @Test
    fun withoutCutoutTheRowMatchesAWeightedMiddle() {
        val a = arrange(emptyList())
        assertEquals(SettingsHeaderArrangement(startX = 12, middleX = 156, middleWidth = 1080 - 24 - 144 - 156, endX = 912), a)
    }

    @Test
    fun aCornerCutoutAtTheStartIndentsOnlyBack() {
        val plain = arrange(emptyList())
        val a = arrange(listOf(-10..120))
        assertEquals(120, a.startX)
        assertEquals(120 + 144, a.middleX)
        assertEquals(plain.endX, a.endX)
    }

    @Test
    fun aCornerCutoutAtTheEndIndentsOnlyTheActions() {
        val plain = arrange(emptyList())
        val a = arrange(listOf(980..1100))
        assertEquals(980 - 144, a.endX)
        assertEquals(plain.startX, a.startX)
        assertEquals(plain.middleX, a.middleX)
        assertEquals(980 - 144 - plain.middleX, a.middleWidth)
    }

    @Test
    fun aCentredHoleLeavesAFittingTitleInPlace() {
        val plain = arrange(emptyList())
        val a = arrange(listOf(500..580), middlePreferred = 300)
        assertEquals(plain.middleX, a.middleX)
        assertEquals(500 - plain.middleX, a.middleWidth)
        assertEquals(plain.startX, a.startX)
        assertEquals(plain.endX, a.endX)
    }

    @Test
    fun aHoleRightAfterBackMovesTheTitleBehindIt() {
        val a = arrange(listOf(160..260), middlePreferred = 500)
        assertEquals(12, a.startX)
        assertEquals(260, a.middleX)
        assertEquals(912 - 260, a.middleWidth)
    }

    @Test
    fun aLongTitleTakesTheFirstStretchThatStillReadsWell() {
        // Neither stretch holds the whole title; the first one holding the minimum wins.
        val a = arrange(listOf(560..640), middlePreferred = 900)
        assertEquals(156, a.middleX)
        assertEquals(560 - 156, a.middleWidth)
        assertTrue(a.middleWidth >= 360)
    }
}
