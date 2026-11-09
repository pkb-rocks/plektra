package rocks.pkb.plektra

import android.content.Context

/**
 * Settings of the emoji, symbol and kaomoji pages on the keys and in the picker.
 */
object EmojiPagesSettings {
    private const val KEY_EMOJI_PAGES = "emoji_layer_pages"
    private const val KEY_SYMBOL_PAGES = "symbols_layer_pages"
    private const val KEY_KAOMOJI_PLACEMENT = "kaomoji_pages_placement"
    private const val KEY_PICKER_EXTRAS = "emoji_picker_symbols_kaomoji"

    /** Where the kaomoji pages follow on: after the symbols' or the emoji's pages, or nowhere. */
    enum class KaomojiPlacement(val persisted: String) {
        SYMBOLS("symbols"),
        EMOJI("emoji"),
        OFF("off");

        companion object {
            fun fromPersisted(value: String?): KaomojiPlacement =
                entries.firstOrNull { it.persisted == value } ?: SYMBOLS
        }
    }

    /** The emoji page turns through every emoji with Q and P. */
    fun emojiPagesEnabled(context: Context): Boolean =
        SettingsManager.getPreferences(context).getBoolean(KEY_EMOJI_PAGES, true)

    fun setEmojiPagesEnabled(context: Context, enabled: Boolean) {
        SettingsManager.getPreferences(context).edit().putBoolean(KEY_EMOJI_PAGES, enabled).apply()
    }

    /** The symbols page turns through the symbol pages (and kaomoji) with Q and P. */
    fun symbolPagesEnabled(context: Context): Boolean =
        SettingsManager.getPreferences(context).getBoolean(KEY_SYMBOL_PAGES, true)

    fun setSymbolPagesEnabled(context: Context, enabled: Boolean) {
        SettingsManager.getPreferences(context).edit().putBoolean(KEY_SYMBOL_PAGES, enabled).apply()
    }

    fun kaomojiPlacement(context: Context): KaomojiPlacement = KaomojiPlacement.fromPersisted(
        SettingsManager.getPreferences(context).getString(KEY_KAOMOJI_PLACEMENT, null)
    )

    fun setKaomojiPlacement(context: Context, placement: KaomojiPlacement) {
        SettingsManager.getPreferences(context).edit()
            .putString(KEY_KAOMOJI_PLACEMENT, placement.persisted)
            .apply()
    }

    /** The emoji picker also offers (and searches) text symbols and kaomoji. */
    fun pickerSymbolsAndKaomoji(context: Context): Boolean =
        SettingsManager.getPreferences(context).getBoolean(KEY_PICKER_EXTRAS, true)

    fun setPickerSymbolsAndKaomoji(context: Context, enabled: Boolean) {
        SettingsManager.getPreferences(context).edit().putBoolean(KEY_PICKER_EXTRAS, enabled).apply()
    }
}
