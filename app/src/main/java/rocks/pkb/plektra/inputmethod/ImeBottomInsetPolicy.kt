package rocks.pkb.plektra.inputmethod

/**
 * Bottom padding of the keyboard chrome. The themed background always reaches the display edge;
 * the padding only keeps the controls clear.
 *
 * Navigation bar and cutout stay fully protected. While Plektra's hidden caption bar leaves only
 * the gesture zone below the keyboard, the stable gesture inset still counts the hidden bar, so
 * the visible mandatory gesture zone applies instead, by [CompactGestureClearance].
 *
 * Inside that zone Android delivers taps, but system gestures take precedence over the whole
 * zone, and Android names no part of it as safe. Device checks decide whether a layout holds
 * there (see `ImeHitBoundsReport`). Rounded display corners in that band are handled by the
 * rounded-corner layout.
 */
internal object ImeBottomInsetPolicy {
    /** How much of the visible gesture zone stays free below a hidden caption bar. */
    enum class CompactGestureClearance {
        /**
         * The upper half of the zone: taps on the lower part of a compact bottom row reach the
         * keyboard, while a swipe starting there may become a system gesture. For touch devices,
         * where that row is tapped often.
         */
        HALF,

        /**
         * None: the controls reach the display edge and follow its contour. For devices with a
         * built-in hardware keyboard, which is typed on instead of the bar.
         */
        NONE
    }

    fun resolve(
        navigation: Int,
        gestures: Int,
        mandatoryGestures: Int,
        cutout: Int,
        compactCaptionArea: Boolean,
        compactClearance: CompactGestureClearance = CompactGestureClearance.HALF
    ): Int {
        val gestureClearance = when {
            !compactCaptionArea -> gestures
            compactClearance == CompactGestureClearance.NONE -> 0
            else -> (mandatoryGestures + 1) / 2
        }
        return maxOf(navigation, cutout, gestureClearance)
    }
}

/**
 * Device defaults for Android's IME caption bar (navigation handle, back and IME switcher below
 * the keyboard). A stored user choice always wins over [hideByDefault].
 */
internal object ImeCaptionBarDefaults {
    /**
     * Devices with a built-in hardware keyboard drop the bar and its bottom reserve: the keyboard
     * replaces most taps on the bar, and the bar's controls then reach the display edge. Touch
     * devices keep the light system bar unless the user hides it.
     */
    fun hideByDefault(): Boolean = DeviceSpecific.hasBuiltInHardwareKeyboard()

    /** The bottom reserve below a hidden caption bar, see [ImeBottomInsetPolicy]. */
    fun compactClearance(): ImeBottomInsetPolicy.CompactGestureClearance =
        if (DeviceSpecific.hasBuiltInHardwareKeyboard()) ImeBottomInsetPolicy.CompactGestureClearance.NONE
        else ImeBottomInsetPolicy.CompactGestureClearance.HALF
}
