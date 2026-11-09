package rocks.pkb.plektra.data.emoji

import android.content.Context
import android.graphics.Typeface
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowLooper

/** Loads the bundled Noto Color Emoji metadata into EmojiCompat, as the app does at runtime. */
object TestEmojiFont {
    /** [drawable]: with the font itself as the typeface (native graphics), not only its metadata. */
    fun install(context: Context, drawable: Boolean = false) {
        val repo = if (drawable) {
            MetadataRepo.create(context.assets, EmojiRendering.FONT_ASSET)
        } else {
            val bytes = context.assets.open(EmojiRendering.FONT_ASSET).use { it.readBytes() }
            MetadataRepo.create(Typeface.DEFAULT, ByteBuffer.wrap(bytes))
        }
        EmojiCompat.reset(
            object : EmojiCompat.Config(EmojiCompat.MetadataRepoLoader { it.onLoaded(repo) }) {}
                .setReplaceAll(true)
        )
        ShadowLooper.idleMainLooper()
        EmojiRendering.setStyle(context, EmojiRendering.Style.NOTO)
        runBlocking { EmojiRepository.getEmojiCategories(context) }
    }
}
