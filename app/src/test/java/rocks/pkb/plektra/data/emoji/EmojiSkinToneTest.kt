package rocks.pkb.plektra.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiSkinToneTest {
    @Test
    fun appliesTheToneWhenAVariantCarriesItAlone() {
        val variants = listOf("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿")
        assertEquals("👍", EmojiSkinTone.apply("👍", variants, 0))
        assertEquals("👍🏽", EmojiSkinTone.apply("👍", variants, 3))
        assertEquals("😀", EmojiSkinTone.apply("😀", emptyList(), 3))
    }

    @Test
    fun mixedToneSequencesAreNotTheDefault() {
        val variants = listOf("🧑🏻‍🤝‍🧑🏿", "🧑🏿‍🤝‍🧑🏿")
        assertEquals("🧑🏿‍🤝‍🧑🏿", EmojiSkinTone.apply("🧑‍🤝‍🧑", variants, 5))
        assertEquals("🧑‍🤝‍🧑", EmojiSkinTone.apply("🧑‍🤝‍🧑", variants, 1))
    }
}
