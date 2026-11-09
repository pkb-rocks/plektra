package rocks.pkb.plektra.data.layout

/**
 * Represents a single mapping between a physical key (KEYCODE) and the
 * lowercase/uppercase characters that should be produced, with optional
 * multi-tap tap-level overrides and a layout-specific Alt output.
 */
data class TapMapping(
    val lowercase: String,
    val uppercase: String
)

data class LayoutMapping(
    val lowercase: String,
    val uppercase: String,
    val multiTapEnabled: Boolean = false,
    val taps: List<TapMapping> = emptyList(),
    val alt: String? = null
)

val LayoutMapping.isRealMultiTap: Boolean
    get() = multiTapEnabled && taps.size > 1
