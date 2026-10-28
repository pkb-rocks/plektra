package rocks.pkb.plektra

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.util.LruCache
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object ThemeTextureStore {
    const val DIRECTORY = "theme-images"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableMapOf<String, MutableList<WeakReference<View>>>()
    private val failed = linkedSetOf<String>()
    private var generation = 0L
    fun file(context: Context, asset: String): File {
        require(ThemeTextureModel.assetPattern.matches(asset) || ThemeTextureModel.sourcePattern.matches(asset))
        return File(File(context.filesDir, DIRECTORY), asset)
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun key(context: Context, asset: String) = context.filesDir.path + "/" + asset
    fun peek(context: Context, asset: String): Bitmap? = cache.get(key(context, asset))
    fun invalidate() = synchronized(lock) { generation++; cache.evictAll(); failed.clear() }
    fun request(view: View, asset: String) {
        if (!ThemeTextureModel.assetPattern.matches(asset)) return
        val app = view.context.applicationContext
        val key = key(app, asset)
        val epoch = synchronized(lock) {
            if (cache.get(key) != null || key in failed) return
            pending[key]?.let { if (it.size < 96) it.add(WeakReference(view)); return }
            pending[key] = mutableListOf(WeakReference(view))
            generation
        }
        scope.launch {
            val bitmap = try {
                decodeAsset(app, asset)
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            val views = synchronized(lock) {
                if (generation == epoch) {
                    if (bitmap != null) cache.put(key, bitmap) else {
                        failed.add(key); if (failed.size > 128) failed.remove(failed.first())
                    }
                }
                pending.remove(key).orEmpty()
            }
            withContext(Dispatchers.Main) { views.forEach { it.get()?.invalidate() } }
        }
    }
    internal fun decodeAsset(context: Context, asset: String): Bitmap {
        val image = file(context, asset)
        require(image.length() in 1..2_097_152L)
        val bytes = image.inputStream().use { readBounded(it, 2_097_152) }
        require(asset == "texture-${hash(bytes)}.png")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(bytes.inputStream(), null, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..512 && bounds.outHeight in 1..512) { "Invalid PNG bounds: ${bounds.outMimeType} ${bounds.outWidth}x${bounds.outHeight}" }
        return BitmapFactory.decodeStream(bytes.inputStream()) ?: error("Unable to decode theme PNG")
    }
    fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            require(out.size().toLong() + n <= limit) { "Image is too large" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    private fun persist(context: Context, bitmap: Bitmap, source: Boolean): String {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val temporary = File.createTempFile("image-saving-", ".png", directory)
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            require(temporary.length() <= if (source) 8_388_608 else 2_097_152)
            val name = (if (source) "source-" else "texture-") + hash(temporary.readBytes()) + ".png"
            Files.move(temporary.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return name
        } finally { temporary.delete() }
    }
    suspend fun import(context: Context, uri: android.net.Uri): KeyboardThemeTexture = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, 16 * 1024 * 1024) }
            ?: error("Unable to open image")
        val original = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(info.size.width > 0 && info.size.height > 0)
            val scale = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        try {
            coroutineContext.ensureActive()
            val scale = minOf(1.0, 512.0 / maxOf(original.width, original.height))
            val sample = Bitmap.createScaledBitmap(original, maxOf(1, (original.width * scale).toInt()), maxOf(1, (original.height * scale).toInt()), false)
            try {
                KeyboardThemeTexture(persist(context, sample, false), wholeKeyboard = true,
                    sourceAsset = persist(context, original, true), sourceCrop = listOf(0f, 0f, 1f, 1f))
            } finally { if (sample !== original) sample.recycle() }
        } finally { original.recycle() }
    }
}

/** Own the backdrop once; matching descendants must not repaint another crop over it. */
internal object ThemeTextureBackground {
    fun set(view: View?, color: Int, texture: KeyboardThemeTexture?) {
        view ?: return
        val old = view.background
        if (texture == null && old is android.graphics.drawable.ColorDrawable && old.color == color) return
        if (old is ThemeTextureDrawable && old.color == color && old.texture == texture) return
        view.background = if (texture == null) android.graphics.drawable.ColorDrawable(color)
            else ThemeTextureDrawable(view, color, texture)
    }
}

internal class ThemeTextureDrawable(private val owner: View, val color: Int, val texture: KeyboardThemeTexture) : Drawable() {
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val matrix = Matrix()
    private var shaderBitmap: Bitmap? = null
    private var imageShader: BitmapShader? = null
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        var parent = owner.parent
        while (parent is View) {
            val backdrop = parent.background as? ThemeTextureDrawable
            if (backdrop?.texture == texture) return
            parent = parent.parent
        }
        paint.shader = null; paint.color = color; paint.alpha = (Color.alpha(color) * opacity / 255)
        canvas.drawRect(bounds, paint)
        val bitmap = ThemeTextureStore.peek(owner.context, texture.asset)
        if (bitmap == null) { ThemeTextureStore.request(owner, texture.asset); return }
        if (shaderBitmap !== bitmap) {
            shaderBitmap = bitmap
            imageShader = BitmapShader(bitmap, if (texture.mode == "tile") Shader.TileMode.REPEAT else Shader.TileMode.CLAMP,
                if (texture.mode == "tile") Shader.TileMode.REPEAT else Shader.TileMode.CLAMP)
        }
        val scale = if (texture.mode == "tile") 1f else maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(bounds.left + (bounds.width() - bitmap.width * scale) / 2f, bounds.top + (bounds.height() - bitmap.height * scale) / 2f)
        imageShader!!.setLocalMatrix(matrix)
        paint.shader = imageShader; paint.alpha = (opacity * ThemeTextureModel.normalizedIntensity(texture)).toInt()
        canvas.drawRect(bounds, paint)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = opacity
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
