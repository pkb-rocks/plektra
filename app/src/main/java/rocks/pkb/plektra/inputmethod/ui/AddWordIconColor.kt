package rocks.pkb.plektra.inputmethod.ui

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** Colour of the add-word "+" so it stays recognisable on the suggestion button of every theme. */
internal object AddWordIconColor {
    /** WCAG minimum for graphical objects. */
    const val MIN_CONTRAST = 3.0

    /**
     * The theme accent (yellow without a theme) when it stands out from [surface]; otherwise the
     * theme's text colour, and black or white when even that is too faint.
     */
    fun resolve(surface: Int, accent: Int?, text: Int): Int {
        val opaqueSurface = ColorUtils.compositeColors(surface, Color.BLACK)
        fun contrast(color: Int) = ColorUtils.calculateContrast(ColorUtils.compositeColors(color, opaqueSurface), opaqueSurface)
        return listOf(accent ?: Color.YELLOW, text).firstOrNull { contrast(it) >= MIN_CONTRAST }
            ?: listOf(Color.BLACK, Color.WHITE).maxBy(::contrast)
    }
}
