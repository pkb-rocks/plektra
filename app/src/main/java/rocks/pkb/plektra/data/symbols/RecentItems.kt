package rocks.pkb.plektra.data.symbols

import android.content.Context
import org.json.JSONArray

/**
 * Recently typed symbols and kaomoji, newest first. Kept beside the recent emoji in the same
 * preferences file, which backups leave out like the recent emoji.
 */
class RecentItems(private val key: String, private val max: Int) {

    fun get(context: Context): List<String> {
        val json = prefs(context).getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map(array::getString).filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, item: String) {
        if (item.isBlank()) return
        val updated = (listOf(item) + get(context).filter { it != item }).take(max)
        prefs(context).edit().putString(key, JSONArray(updated).toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "recent_emojis_prefs"
    }
}
