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
