package rocks.pkb.plektra.documents

import org.json.JSONObject

internal data class DocumentWallpaper(
    val asset: String,
    val intensity: Int = 70,
    val x: Int = 50,
    val y: Int = 50,
    val zoom: Int = 100,
) {
    fun json() =
        JSONObject()
            .put("asset", asset)
            .put("intensity", intensity)
            .put("x", x)
            .put("y", y)
            .put("zoom", zoom)

    companion object {
        val pattern = Regex("wallpaper-[0-9a-f]{64}\\.jpg")

        fun parse(value: JSONObject): DocumentWallpaper {
            val asset = value.getString("asset")
            require(pattern.matches(asset))
            fun integer(key: String, default: Int, range: IntRange): Int {
                if (!value.has(key)) return default
                require(value.get(key) is Int)
                return value.getInt(key).also { require(it in range) }
            }
            return DocumentWallpaper(
                asset,
                integer("intensity", 70, 0..100),
                integer("x", 50, 0..100),
                integer("y", 50, 0..100),
                integer("zoom", 100, 100..300),
            )
        }
    }
}
