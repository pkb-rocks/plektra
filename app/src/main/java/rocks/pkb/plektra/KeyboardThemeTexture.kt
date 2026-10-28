package rocks.pkb.plektra

import org.json.JSONArray
import org.json.JSONObject

/** Pixels live in theme-images; these small references stay in ordinary theme JSON. */
data class KeyboardThemeTexture(
    val asset: String,
    val mode: String = "cover",
    val foreground: Int? = null,
    val halo: Boolean = false,
    val continuous: Boolean = false,
    val wholeKeyboard: Boolean = false,
    val intensity: Float = 1f,
    val outlineColor: Int? = null,
    val outlineWidth: Float = 1.3f,
    val sourceAsset: String? = null,
    val sourceCrop: List<Float>? = null,
    val bold: Boolean? = null
)

internal object ThemeTextureModel {
    val roles = setOf("background", "normal_key", "special_key", "suggestion", "status_bar_button", "key_popup", "key_popup_selected")
    val assetPattern = Regex("texture-[0-9a-f]{64}\\.png")
    val sourcePattern = Regex("source-[0-9a-f]{64}\\.png")
    const val MAX_EDGE = 512
    fun normalizedIntensity(texture: KeyboardThemeTexture) = texture.intensity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    fun number(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it == it.toInt().toDouble()
    }?.toInt()
    private fun crop(value: JSONArray?): List<Float>? {
        if (value == null || value.length() != 4) return null
        val result = (0..3).map { (value.opt(it) as? Number)?.toFloat() ?: Float.NaN }
        return result.takeIf { it.all { n -> n.isFinite() && n in 0f..1f } && it[2] > it[0] && it[3] > it[1] }
    }
    fun decode(json: JSONObject?): Map<String, KeyboardThemeTexture> = buildMap {
        if (json == null) return@buildMap
        for (role in roles) {
            val entry = json.optJSONObject(role) ?: continue
            val asset = entry.optString("asset")
            val mode = entry.optString("mode", "cover")
            if (!assetPattern.matches(asset) || mode !in setOf("cover", "fit", "tile")) continue
            put(role, KeyboardThemeTexture(
                asset, mode, number(entry.opt("foreground")), entry.opt("halo") == true,
                entry.opt("continuous") == true, entry.opt("wholeKeyboard") == true,
                entry.optDouble("intensity", 1.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 1f,
                number(entry.opt("outlineColor")),
                entry.optDouble("outlineWidth", 1.3).takeIf { it.isFinite() }?.coerceIn(0.5, 3.0)?.toFloat() ?: 1.3f,
                entry.optString("sourceAsset").takeIf { sourcePattern.matches(it) },
                crop(entry.optJSONArray("sourceCrop")), entry.opt("bold") as? Boolean
            ))
        }
    }
    fun encode(textures: Map<String, KeyboardThemeTexture>): JSONObject = JSONObject().apply {
        for ((role, t) in textures) {
            if (role !in roles || !assetPattern.matches(t.asset) || t.mode !in setOf("cover", "fit", "tile")) continue
            put(role, JSONObject().put("asset", t.asset).put("mode", t.mode)
                .put("continuous", t.continuous).put("wholeKeyboard", t.wholeKeyboard)
                .put("intensity", normalizedIntensity(t).toDouble()).apply {
                    t.foreground?.let { put("foreground", it) }
                    put("halo", t.halo)
                    t.outlineColor?.let { put("outlineColor", it) }
                    put("outlineWidth", t.outlineWidth.takeIf { it.isFinite() }?.coerceIn(0.5f, 3f)?.toDouble() ?: 1.3)
                    t.bold?.let { put("bold", it) }
                    t.sourceAsset?.takeIf { sourcePattern.matches(it) }?.let { put("sourceAsset", it) }
                    t.sourceCrop?.takeIf { it.size == 4 && it.all { n -> n.isFinite() && n in 0f..1f } }
                        ?.let { crop(JSONArray(it)) }?.let { put("sourceCrop", JSONArray(it)) }
                })
        }
    }
    fun referencedAssets(values: Collection<String>): Set<String> = buildSet {
        fun collect(value: Any?, depth: Int) {
            require(depth <= 32) { "Theme JSON nesting is too deep" }
            when (value) {
                is JSONObject -> {
                    decode(value.optJSONObject("textures")).values.forEach { t -> add(t.asset); t.sourceAsset?.let(::add) }
                    value.keys().forEach { collect(value.opt(it), depth + 1) }
                }
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), depth + 1)
            }
        }
        for (text in values) {
            require(text.length <= 4 * 1024 * 1024) { "Theme preference is too large" }
            var depth = 0
            var quoted = false
            var escaped = false
            for (character in text) {
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                } else when (character) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 32) { "Theme JSON nesting is too deep" } }
                    '}', ']' -> depth--
                }
            }
            when (text.trimStart().firstOrNull()) {
                '{' -> collect(JSONObject(text), 0)
                '[' -> collect(JSONArray(text), 0)
            }
        }
    }
}

internal fun Map<String, KeyboardThemeTexture>.hasWholeBackdrop() = get("background")?.wholeKeyboard == true
internal fun Map<String, KeyboardThemeTexture>.surfaceColour(colour: Int) = if (hasWholeBackdrop()) android.graphics.Color.TRANSPARENT else colour

internal fun keyboardThemeClipboardJson(theme: KeyboardThemePreset): String =
    SettingsManager.keyboardThemeToJsonString(theme.copy(textures = emptyMap()).toSettingsTheme())
