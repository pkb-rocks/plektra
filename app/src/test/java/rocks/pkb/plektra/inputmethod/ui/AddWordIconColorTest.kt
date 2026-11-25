package rocks.pkb.plektra.inputmethod.ui

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.pkb.plektra.keyboardThemePresets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddWordIconColorTest {
    @Test
    fun yellowPlusOnAWhiteSuggestionGivesWayToTheThemeText() {
        val text = Color.rgb(30, 30, 30)
        assertEquals(text, AddWordIconColor.resolve(Color.WHITE, Color.YELLOW, text))
    }

    @Test
    fun accentIsKeptWhereItStandsOut() {
        assertEquals(Color.YELLOW, AddWordIconColor.resolve(Color.rgb(21, 25, 29), null, Color.WHITE))
    }

    @Test
    fun everyBuiltInThemeShowsThePlusWithEnoughContrast() {
        keyboardThemePresets().forEach { preset ->
            listOf(preset.suggestion, preset.normalKey).forEach { surface ->
                val icon = AddWordIconColor.resolve(surface, preset.accent, preset.textAndIcons)
                val opaque = ColorUtils.compositeColors(surface, Color.BLACK)
                val contrast = ColorUtils.calculateContrast(ColorUtils.compositeColors(icon, opaque), opaque)
                assertTrue("${preset.name}: contrast $contrast", contrast >= AddWordIconColor.MIN_CONTRAST)
            }
        }
    }
}
