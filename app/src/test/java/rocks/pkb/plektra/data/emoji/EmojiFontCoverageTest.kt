package rocks.pkb.plektra.data.emoji

import android.content.Context
import android.graphics.Typeface
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The bundled Noto Color Emoji font's EmojiCompat metadata (what Plektra draws with at runtime)
 * covers every emoji the picker offers, and the picker data matches the font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmojiFontCoverageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun pickerEmoji(): List<String> =
        context.assets.list("common/emoji").orEmpty()
            .filter { it.endsWith(".txt") && it != "minApi.txt" }
            .flatMap { name ->
                context.assets.open("common/emoji/$name").bufferedReader().readLines()
                    .flatMap { line -> line.split(' ').filter(String::isNotBlank) }
            }

    /** Each line of the picker data: its emoji first, then its skin tones. */
    private fun pickerLines(): List<List<String>> =
        context.assets.list("common/emoji").orEmpty()
            .filter { it.endsWith(".txt") && it != "minApi.txt" }
            .flatMap { name ->
                context.assets.open("common/emoji/$name").bufferedReader().readLines()
                    .map { line -> line.split(' ').filter(String::isNotBlank) }
                    .filter { it.isNotEmpty() }
            }

    @Test
    fun everySkinToneVariantIsOnItsOwnEmojisLine() {
        val tones = EmojiSkinTone.MODIFIERS
        fun untoned(emoji: String) = tones.fold(emoji) { text, tone -> text.replace(tone, "") }
        val lines = pickerLines()
        val bases = lines.map { it.first() }.toSet()
        val misplaced = lines.flatMap { line ->
            line.drop(1).filter { variant ->
                // A variant carries a tone and belongs to the emoji it tones; multi-person tones
                // without an untoned emoji of their own (🫱🏻‍🫲🏼) belong to the shared-name emoji (🤝).
                tones.none { it in variant } || untoned(variant).let { it != line.first() && it in bases }
            }.map { "${line.first()} ← $it" }
        }
        assertTrue("Variants on another emoji's line: ${misplaced.take(10)}", misplaced.isEmpty())
        val lineOf = lines.flatMap { line -> line.map { it to line.first() } }.toMap()
        assertEquals("\uD83E\uDEF9", lineOf["\uD83E\uDEF9\uD83C\uDFFB"]) // 🫹🏻
        assertEquals("\uD83E\uDEFA", lineOf["\uD83E\uDEFA\uD83C\uDFFF"]) // 🫺🏿
        assertEquals("🤝", lineOf["\uD83E\uDEF1\uD83C\uDFFB\u200D\uD83E\uDEF2\uD83C\uDFFC"]) // 🫱🏻‍🫲🏼
        assertEquals(6, lines.first { it.first() == "\uD83E\uDEF9" }.size)
        assertEquals(26, lines.first { it.first() == "💑" }.size)
    }

    @Test
    fun bundledFontMetadataCoversEveryPickerEmoji() {
        val bytes = context.assets.open(EmojiRendering.FONT_ASSET).use { it.readBytes() }
        val repo = MetadataRepo.create(Typeface.DEFAULT, ByteBuffer.wrap(bytes))
        val loaded = CountDownLatch(1)
        val compat = EmojiCompat.reset(
            object : EmojiCompat.Config(EmojiCompat.MetadataRepoLoader { it.onLoaded(repo) }) {}
                .setReplaceAll(true)
                .registerInitCallback(object : EmojiCompat.InitCallback() {
                    override fun onInitialized() = loaded.countDown()
                })
        )
        ShadowLooper.idleMainLooper()
        assertTrue(loaded.await(5, TimeUnit.SECONDS))

        val emoji = pickerEmoji()
        // Every fully-qualified emoji of Unicode Emoji 18.0
        assertEquals(3963, emoji.toSet().size)
        val missing = emoji.filter { compat.getEmojiMatch(it, Int.MAX_VALUE) != EmojiCompat.EMOJI_SUPPORTED }
        assertTrue("Not in the bundled font: ${missing.take(10)}", missing.isEmpty())
        // Text symbols stay text: no emoji glyph replaces them without VS16
        assertEquals("©", compat.process("©").toString())
    }
}
