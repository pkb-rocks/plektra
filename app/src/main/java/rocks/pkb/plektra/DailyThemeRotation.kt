package rocks.pkb.plektra

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

/** Optional calendar-day rotation, independent of restarts, timezone offsets and DST.*/
object DailyThemeRotation {
    private fun suffix(target: SettingsManager.KeyboardThemeTarget) = target.name.lowercase(java.util.Locale.ROOT)
    fun namesKey(target: SettingsManager.KeyboardThemeTarget) = "keyboard_theme_rotation_names_${suffix(target)}"
    fun anchorKey(target: SettingsManager.KeyboardThemeTarget) = "keyboard_theme_rotation_anchor_${suffix(target)}"
    fun selectedNames(context: Context, target: SettingsManager.KeyboardThemeTarget): List<String> {
        val raw = SettingsManager.getPreferences(context).getString(namesKey(target), "[]") ?: "[]"
        if (raw.length > 32768) return emptyList()
        return try { val array = JSONArray(raw); (0 until minOf(array.length(),256)).map { array.getString(it) }.filter { it.isNotBlank() && it.length<=256 }.distinct() }
        catch (_: Exception) { emptyList() }
    }
    /** Only visible saved themes receive a rank; imported renames must not leave invisible slots.*/
    internal fun availableNames(names: List<String>, available: List<String>): List<String> =
        names.mapNotNull { name -> available.firstOrNull { it.equals(name, ignoreCase = true) } }.distinct()

    fun select(context: Context, target: SettingsManager.KeyboardThemeTarget, names: List<String>) {
        SettingsManager.getPreferences(context).edit()
            .putString(namesKey(target), JSONArray(availableNames(names.take(256), SettingsManager.getSavedKeyboardThemes(context).map {it.name})).toString())
            .putLong(anchorKey(target), LocalDate.now().toEpochDay()).apply()
    }
    fun index(day: Long, anchor: Long, count: Int): Int =
        if (count <= 0) -1 else Math.floorMod(Math.floorMod(day,count.toLong()) - Math.floorMod(anchor,count.toLong()),count.toLong()).toInt()

    private data class Cache(val prefs: android.content.SharedPreferences, val saved: String?, val names: String?,
        val day: Long, val anchor: Long, val theme: SettingsManager.KeyboardThemeSettings?)
    private val caches = mutableMapOf<SettingsManager.KeyboardThemeTarget, Cache>()
    @Synchronized
    fun theme(context: Context, target: SettingsManager.KeyboardThemeTarget, day: Long = LocalDate.now().toEpochDay()): SettingsManager.KeyboardThemeSettings? {
        val prefs = SettingsManager.getPreferences(context)
        val saved = prefs.getString("keyboard_theme_saved_themes", null)
        val names = prefs.getString(namesKey(target), null)
        val anchor = prefs.getLong(anchorKey(target), day)
        caches[target]?.let { if (it.prefs === prefs && it.saved == saved && it.names == names && it.day == day && it.anchor == anchor) return it.theme }
        val available = SettingsManager.getSavedKeyboardThemes(context)
        val themes = availableNames(selectedNames(context, target),available.map {it.name}).mapNotNull { name -> available.firstOrNull { it.name == name }?.theme }
        val result = themes.getOrNull(index(day, anchor, themes.size))
        caches[target] = Cache(prefs, saved, names, day, anchor, result)
        return result
    }

    fun hasDailyRotation(context: Context): Boolean = SettingsManager.KeyboardThemeTarget.entries.any {
        SettingsManager.getKeyboardThemeAssignmentMode(context, it) == SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY
    }
}
