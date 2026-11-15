package rocks.pkb.plektra.inputmethod.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiVariantsPopupLayoutTest {
    @Test
    fun sixTonesWrapEvenlyWhenTheRowIsWiderThanTheFoldOuterDisplay() {
        // Measured on the Galaxy Z Fold6's outer display: 968 px window, 996 px popup for six cells.
        assertEquals(3, EmojiVariantsPopupLayout.perRow(6, 152, 84, 968, 6))
        assertEquals(6, EmojiVariantsPopupLayout.perRow(6, 152, 84, 1856, 6))
    }

    @Test
    fun twoPersonVariantsKeepSixPerRowWhereTheyFit() {
        assertEquals(6, EmojiVariantsPopupLayout.perRow(26, 152, 84, 1856, 6))
        assertEquals(5, EmojiVariantsPopupLayout.perRow(26, 152, 84, 968, 6))
    }

    @Test
    fun aCellWiderThanTheWindowStillGetsARow() {
        assertEquals(1, EmojiVariantsPopupLayout.perRow(6, 2000, 84, 968, 6))
    }

    @Test
    fun popupStaysInsideTheWindowAndStartsAtZeroWhenWider() {
        assertEquals(400, EmojiVariantsPopupLayout.left(500, 100, 300, 968))
        assertEquals(0, EmojiVariantsPopupLayout.left(20, 100, 300, 968))
        assertEquals(668, EmojiVariantsPopupLayout.left(900, 100, 300, 968))
        assertEquals(0, EmojiVariantsPopupLayout.left(900, 100, 996, 968))
    }
}
