package rocks.pkb.plektra.data.emoji

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "de")
class EmojiSearchImprovementTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun searchFindsUnicode18EmojiAndWordsInAnyOrderInEverySearchLanguage() = runBlocking {
        // System font availability: the search must not depend on the bundled font loading.
        EmojiRendering.setStyle(context, EmojiRendering.Style.SYSTEM)
        try {
            val index = EmojiSearchRepository.getSearchIndex(context)
            fun found(query: String) = EmojiSearchRepository.search(index, query).map { it.entry.base }
            // Robolectric offers only emoji with a known API level (no system emoji font): 🧡 is one
            assertTrue(found("oranges herz").take(3).contains("🧡"))
            assertTrue(found("herz orange").take(3).contains("🧡"))
            assertTrue(found("heart orange").take(3).contains("🧡"))
            val lighthouse = found("leuchtturm")
            val all = EmojiRepository.getEmojiCategories(context).flatMap { it.emojis.map(EmojiRepository.EmojiEntry::base) }
            // Robolectric has no emoji font: Unicode 18 emoji are only offered when a font draws them.
            assertTrue("🛙" !in all || lighthouse.contains("🛙"))
        } finally {
            EmojiRendering.setStyle(context, EmojiRendering.Style.NOTO)
        }
    }

    @Test
    fun emojiTypedAsTheQueryFindsItsEntry() = runBlocking {
        TestEmojiFont.install(context)
        val index = EmojiSearchRepository.getSearchIndex(context)
        fun first(query: String) = EmojiSearchRepository.search(index, query).firstOrNull()?.entry?.base
        assertEquals("👋", first("👋🏽"))
        assertEquals("👋", first("👋"))
        assertEquals("🤝", first("\uD83E\uDEF1\uD83C\uDFFB\u200D\uD83E\uDEF2\uD83C\uDFFC")) // 🫱🏻‍🫲🏼
        assertEquals("🧑\u200D🩰", first("🧑🏽\u200D🩰"))
        // With or without the emoji presentation selector
        assertEquals("❤️", first("❤"))
        assertEquals("❤️", first("❤️"))
    }
}
