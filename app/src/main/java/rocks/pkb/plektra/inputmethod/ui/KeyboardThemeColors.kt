package rocks.pkb.plektra.inputmethod.ui

data class KeyboardThemeColors(
    val background: Int,
    val divider: Int,
    val normalKey: Int,
    val specialKey: Int,
    val textAndIcons: Int,
    val ledInactive: Int,
    val ledActive: Int,
    val ledLocked: Int,
    val accent: Int,
    val cursorSwipe: Int = accent,
    val keyPopup: Int = specialKey,
    val keyPopupSelected: Int = accent,
    val suggestion: Int = normalKey,
    val statusBarButton: Int = specialKey,
    val keyCornerRadiusRatio: Float = 0.08f,
    val chromeCornerRadiusRatio: Float = 0.08f,
    val suggestionsHeightScale: Float = 1f,
    val variationsHeightScale: Float = 1f,
    val textures: Map<String, rocks.pkb.plektra.KeyboardThemeTexture> = emptyMap()
) {
    // Themes are persisted as colors rather than preset names. Match the unchanged
    // Plektra Dark surfaces so custom palettes keep their configured divider.
    val statusButtonBorder: Int
        get() = if (
            (background == 0xFF000000.toInt() || background == 0x00000000) &&
            normalKey == 0xFF15191D.toInt() &&
            statusBarButton == 0xFF2B3138.toInt() &&
            divider == 0xFF2C3136.toInt()
        ) 0xFF454B52.toInt() else divider
}
