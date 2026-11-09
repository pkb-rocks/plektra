package rocks.pkb.plektra.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.ThemeTextureModel
import rocks.pkb.plektra.ThemeTextureStore

/** Versioned assets beside prefs/files/learning, never embedded in theme JSON. */
internal object ThemeImageBackup {
    const val DIRECTORY = "theme-images"
    private const val TAG = "ThemeImageBackup"
    private const val MANIFEST = "manifest.json"
    private const val MANIFEST_VERSION = 1
    private const val MAX_ASSETS = 512
    private const val MAX_TOTAL_BYTES = 72L * 1024 * 1024

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun isSource(name: String) = ThemeTextureModel.sourcePattern.matches(name)

    private fun dimensions(file: File, source: Boolean): Pair<Int, Int> {
        val limit = if (source) 8L * 1024 * 1024 else 2L * 1024 * 1024
        val edge = if (source) 1024 else ThemeTextureModel.MAX_EDGE
        require(file.length() in 1..limit) { "Theme image size is invalid" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..edge && bounds.outHeight in 1..edge) {
            "Theme image format or dimensions are invalid"
        }
        val decoded = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(file.readBytes()))) { decoder, _, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        decoded.recycle()
        return bounds.outWidth to bounds.outHeight
    }

    /**
     * Export referenced images only. A missing or damaged local image is skipped instead of failing
     * the whole backup; the theme then falls back to its colours, exactly as it does on this device.
     */
    fun snapshot(context: Context, destination: File, themeValues: Collection<String> =
        SettingsManager.getPreferences(context).all.filterKeys { it.startsWith("keyboard_theme_") }.values.filterIsInstance<String>()): List<String> {
        val source = File(context.filesDir, DIRECTORY)
        require(destination.canonicalFile != source.canonicalFile)
        destination.mkdirs()
        destination.listFiles().orEmpty().forEach {
            require(it.isFile && (it.name == MANIFEST || ThemeTextureModel.assetPattern.matches(it.name) || isSource(it.name)))
            check(it.delete())
        }
        val assets = ThemeTextureModel.referencedAssets(themeValues).sorted()
        require(assets.size <= MAX_ASSETS) { "Too many theme textures" }
        val names = mutableListOf<String>()
        val entries = JSONArray(); var total = 0L
        for (name in assets) {
            val image = File(source, name)
            val checked = runCatching {
                val (width, height) = dimensions(image, isSource(name))
                val digest = hash(image)
                require(name == (if (isSource(name)) "source-$digest.png" else "texture-$digest.png")) { "Theme texture hash is invalid" }
                Triple(width, height, digest)
            }.getOrElse { failure ->
                Log.w(TAG, "Skipping unusable theme image $name", failure)
                null
            } ?: continue
            val (width, height, digest) = checked
            total += image.length(); require(total <= MAX_TOTAL_BYTES)
            image.copyTo(File(destination, name), overwrite = true)
            entries.put(JSONObject().put("name", name).put("bytes", image.length()).put("width", width)
                .put("height", height).put("sha256", digest))
            names += name
        }
        File(destination, MANIFEST).writeText(JSONObject().put("version", MANIFEST_VERSION).put("images", entries).toString(2))
        return listOf("$DIRECTORY/$MANIFEST") + names.map { "$DIRECTORY/$it" }
    }

    /** Validate all files before changing any setting or image. */
    fun validate(directory: File): List<String> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Theme image folder is invalid" }
        val manifest = File(directory, MANIFEST)
        require(manifest.isFile && manifest.length() in 1..262_144L) { "Theme image manifest is missing or too large" }
        val json = JSONObject(manifest.readText())
        require(json.getInt("version") == MANIFEST_VERSION) { "Unsupported theme image manifest" }
        val images = json.getJSONArray("images")
        require(images.length() <= MAX_ASSETS) { "Too many theme images" }
        val names = mutableListOf<String>(); var total = 0L
        for (index in 0 until images.length()) {
            val entry = images.getJSONObject(index)
            val name = entry.getString("name")
            require((ThemeTextureModel.assetPattern.matches(name) || isSource(name)) && name !in names) {
                "Unsupported theme image name"
            }
            val image = File(directory, name)
            require(image.isFile && image.length() == entry.getLong("bytes")) { "Theme image is missing or truncated" }
            total += image.length(); require(total <= MAX_TOTAL_BYTES)
            val (width, height) = dimensions(image, isSource(name))
            val digest = hash(image)
            require(width == entry.getInt("width") && height == entry.getInt("height") && digest == entry.getString("sha256")) {
                "Theme image integrity check failed"
            }
            require(name == (if (isSource(name)) "source-$digest.png" else "texture-$digest.png")) { "Theme texture hash is invalid" }
            names.add(name)
        }
        require(directory.listFiles().orEmpty().all { it.isFile && (it.name == MANIFEST || it.name in names) }) {
            "Unexpected theme image content"
        }
        return names
    }

    fun restore(context: Context, source: File): List<String> {
        if (!source.exists()) return emptyList()
        val images = validate(source)
        val target = File(context.filesDir, DIRECTORY)
        require(!target.exists() || target.isDirectory)
        val staged = Files.createTempDirectory(context.filesDir.toPath(), "theme-images-restoring-").toFile()
        val previous = File(staged.parentFile, staged.name + "-previous")
        var oldMoved = false
        try {
            target.listFiles().orEmpty().filter { it.isFile && (ThemeTextureModel.assetPattern.matches(it.name) || ThemeTextureModel.sourcePattern.matches(it.name)) }
                .forEach { it.copyTo(File(staged, it.name)) }
            // Existing assets are immutable and content-addressed, so incoming copies may replace them.
            for (name in images) File(source, name).copyTo(File(staged, name), overwrite = true)
            if (target.exists()) {
                Files.move(target.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
                oldMoved = true
            }
            try { Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (failure: Exception) {
                if (oldMoved) Files.move(previous.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw failure
            }
            ThemeTextureStore.invalidate()
        } finally {
            staged.deleteRecursively()
            if (target.isDirectory) previous.deleteRecursively()
        }
        return images.map { "$DIRECTORY/$it" }
    }
}
