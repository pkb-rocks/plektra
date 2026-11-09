package rocks.pkb.plektra.data.emoji

import android.content.Context
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rocks.pkb.plektra.SettingsManager

/**
 * How Plektra draws emoji in its own views: with the bundled Noto Color Emoji font (default) or
 * with the system font.
 *
 * The bundled font is loaded offline through EmojiCompat, without Google Play services, and draws
 * every emoji the picker offers on every supported Android version. Plektra inserts plain
 * Unicode either way: the app receiving the text draws it with its own fonts.
 */
object EmojiRendering {
    const val FONT_ASSET = "fonts/NotoColorEmoji-emojicompat.ttf"
    private const val PREF = "emoji_style"

    enum class Style(val persisted: String) {
        NOTO("noto"),
        SYSTEM("system");

        companion object {
            fun fromPersisted(value: String?): Style = entries.firstOrNull { it.persisted == value } ?: NOTO
        }
    }

    @Volatile
    private var cachedStyle: Style? = null

    @Volatile
    private var initialized = false

    fun style(context: Context): Style = cachedStyle ?: Style.fromPersisted(
        runCatching { SettingsManager.getPreferences(context).getString(PREF, null) }.getOrNull()
    ).also { cachedStyle = it }

    fun setStyle(context: Context, style: Style) {
        SettingsManager.getPreferences(context).edit().putString(PREF, style.persisted).apply()
        cachedStyle = style
        ensureInitialized(context)
        EmojiRepository.clearCache()
        EmojiSearchRepository.clearCache()
    }

    fun usesBundledFont(context: Context): Boolean = style(context) == Style.NOTO

    /** Starts loading the bundled font in the background; safe to call repeatedly. */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized || !usesBundledFont(context)) return
        initialized = true
        val appContext = context.applicationContext
        runCatching {
            EmojiCompat.init(
                object : EmojiCompat.Config(BundledFontLoader(appContext)) {}
                    .setReplaceAll(true)
            )
        }
    }

    /** EmojiCompat once the bundled font is loaded and in use, else null. */
    private fun readyInstance(context: Context): EmojiCompat? {
        if (!usesBundledFont(context)) return null
        return runCatching {
            EmojiCompat.get().takeIf { it.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED }
        }.getOrNull()
    }

    fun isReady(context: Context): Boolean = readyInstance(context) != null

    /** Waits up to [timeoutMs] for the bundled font; false when it isn't used or didn't load. */
    suspend fun awaitReady(context: Context, timeoutMs: Long = 3000): Boolean {
        if (!usesBundledFont(context)) return false
        ensureInitialized(context)
        val compat = runCatching { EmojiCompat.get() }.getOrNull() ?: return false
        if (compat.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED) return true
        if (compat.loadState == EmojiCompat.LOAD_STATE_FAILED) return false
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : EmojiCompat.InitCallback() {
                    override fun onInitialized() {
                        compat.unregisterInitCallback(this)
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onFailed(throwable: Throwable?) {
                        compat.unregisterInitCallback(this)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                compat.registerInitCallback(callback)
                continuation.invokeOnCancellation { compat.unregisterInitCallback(callback) }
            }
        } ?: false
    }

    /** [text] with its emoji drawn by the bundled font when that is in use and loaded. */
    fun forDisplay(context: Context, text: CharSequence): CharSequence {
        if (text.isEmpty()) return text
        val compat = readyInstance(context) ?: return text
        return runCatching {
            compat.process(text, 0, text.length, Int.MAX_VALUE, EmojiCompat.REPLACE_STRATEGY_ALL)
        }.getOrNull() ?: text
    }

    /** Whether the bundled font draws [emoji] as one emoji; null while it isn't loaded or used. */
    fun bundledFontDraws(context: Context, emoji: String): Boolean? {
        val compat = readyInstance(context) ?: return null
        return runCatching {
            compat.getEmojiMatch(emoji, Int.MAX_VALUE) == EmojiCompat.EMOJI_SUPPORTED
        }.getOrDefault(false)
    }

    private class BundledFontLoader(private val context: Context) : EmojiCompat.MetadataRepoLoader {
        override fun load(loaderCallback: EmojiCompat.MetadataRepoLoaderCallback) {
            Thread({
                try {
                    loaderCallback.onLoaded(MetadataRepo.create(context.assets, FONT_ASSET))
                } catch (throwable: Throwable) {
                    loaderCallback.onFailed(throwable)
                }
            }, "emoji-font").start()
        }
    }
}
