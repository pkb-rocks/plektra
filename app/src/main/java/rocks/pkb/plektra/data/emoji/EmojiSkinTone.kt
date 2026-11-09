package rocks.pkb.plektra.data.emoji

import android.content.Context
import rocks.pkb.plektra.SettingsManager

/**
 * The default emoji skin tone: emoji that come in skin tones show in it in the picker and on
 * the emoji pages. Holding an emoji still offers every tone.
 *
 * Adapted from Flux Keyboard (GPL-3.0), EmojiSkinTone.kt.
 */
object EmojiSkinTone {
    private const val PREF = "emoji_default_skin_tone"

    /** The Fitzpatrick modifiers, light to dark: tones 1 to 5 (0 is the default yellow). */
    val MODIFIERS: List<String> = listOf("🏻", "🏼", "🏽", "🏾", "🏿")

    fun get(context: Context): Int =
        SettingsManager.getPreferences(context).getInt(PREF, 0).coerceIn(0, MODIFIERS.size)

    fun set(context: Context, tone: Int) {
        SettingsManager.getPreferences(context).edit()
            .putInt(PREF, tone.coerceIn(0, MODIFIERS.size))
            .apply()
    }

    /**
     * [base] in [tone] when one of its [variants] carries that tone alone; otherwise [base].
     * Multi-person sequences only change when every person has the tone (👫🏽, not 👩🏽‍🤝‍👨🏿).
     */
    fun apply(base: String, variants: List<String>, tone: Int): String {
        if (tone !in 1..MODIFIERS.size) return base
        val modifier = MODIFIERS[tone - 1]
        val others = MODIFIERS - modifier
        return variants.firstOrNull { variant ->
            modifier in variant && others.none { it in variant }
        } ?: base
    }

    fun preferred(entry: EmojiRepository.EmojiEntry, tone: Int): String =
        apply(entry.base, entry.variants, tone)
}
