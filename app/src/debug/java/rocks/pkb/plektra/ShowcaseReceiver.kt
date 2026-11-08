package rocks.pkb.plektra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.json.JSONArray

/** Shell-only, reversible configuration for real-device marketing captures. */
class ShowcaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("exportTransparent", false)) {
            try {
                resultData = DebugShowcaseExport.exportVisible()
            } catch (error: Exception) {
                resultCode = 1
                resultData = error.message
            }
            return
        }
        val prefs = SettingsManager.getPreferences(context)
        val backup = java.io.File(context.filesDir, "showcase-preferences.json")
        try {
            val knownKeys = allowed + prefs.all.keys + SettingsManager::class.java.declaredFields
                .filter { it.name.startsWith("KEY_") && it.type == String::class.java }
                .mapNotNull { field -> runCatching { field.isAccessible = true; field.get(null) as String }.getOrNull() }
            if (intent.getBooleanExtra("list", false)) {
                resultData = JSONArray(knownKeys.sorted()).toString()
                return
            }
            if (intent.getBooleanExtra("restore", false)) {
                val restoredNotes = restoreNotesPreferences(context)
                require(backup.exists() || restoredNotes) { "No showcase configuration to restore" }
                if (backup.exists()) {
                    val saved = JSONObject(backup.readText())
                    val editor = prefs.edit()
                    saved.keys().forEach { key -> editor.putEncoded(key, saved.getJSONObject(key)) }
                    require(editor.commit()) { "Restore failed" }
                    require(backup.delete()) { "Could not remove restored backup" }
                }
                resultData = "Showcase configuration restored"
                return
            }
            intent.getStringExtra("note")?.let { preset ->
                applyNotePreset(context, JSONObject(preset))
                resultData = "Showcase note applied"
                return
            }
            val values = JSONObject(intent.getStringExtra("settings") ?: "{}")
            val theme = intent.getStringExtra("theme")?.let { name ->
                keyboardThemePresets().firstOrNull { it.name == name } ?: error("Unknown theme $name")
            }
            if (theme != null) listOf("keyboard_theme_hardware", "keyboard_theme_software",
                "keyboard_theme_assignment_mode_hardware", "keyboard_theme_assignment_mode_software").forEach { key ->
                if (!values.has(key)) values.put(key, JSONObject.NULL)
            }
            require(values.keys().asSequence().all { it in knownKeys }) { "Unknown preference key; use --ez list true" }
            val saved = if (backup.exists()) JSONObject(backup.readText()) else JSONObject()
            val editor = prefs.edit()
            values.keys().forEach { key ->
                val current = prefs.all[key]
                val proposed = values.get(key)
                if (current != null && proposed != JSONObject.NULL) {
                    val matches = when (current) {
                        is String -> proposed is String
                        is Boolean -> proposed is Boolean
                        is Int -> proposed is Int
                        is Long -> proposed is JSONObject && proposed.optString("type") == "long"
                        is Float -> proposed is JSONObject && proposed.optString("type") == "float"
                        is Set<*> -> proposed is JSONObject && proposed.optString("type") == "set"
                        else -> false
                    }
                    require(matches) { "Type mismatch for $key" }
                }
                if (!saved.has(key)) {
                    val previous = prefs.all[key]
                    val type = when (previous) {
                        null -> "absent"
                        is Boolean -> "boolean"
                        is Int -> "int"
                        is Long -> "long"
                        is Float -> "float"
                        is String -> "string"
                        is Set<*> -> "set"
                        else -> error("Unsupported original type")
                    }
                    saved.put(key, JSONObject().put("type", type).put("value", if (previous is Set<*>) JSONArray(previous.toList()) else previous))
                }
                when (val value = values.get(key)) {
                    JSONObject.NULL -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    is JSONObject -> when (value.getString("type")) {
                        "float" -> editor.putFloat(key, value.getDouble("value").toFloat())
                        "long" -> editor.putLong(key, value.getLong("value"))
                        "set" -> editor.putStringSet(key, value.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() })
                        else -> error("Use float, long or set for typed values")
                    }
                    else -> error("Unsupported value for $key")
                }
            }
            backup.writeText(saved.toString())
            require(editor.commit()) { "Settings could not be saved" }
            // Applied exactly as choosing the preset in settings does, geometry included.
            theme?.let {
                SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE, theme.toSettingsTheme())
                SettingsManager.setKeyboardTheme(
                    context, SettingsManager.KeyboardThemeTarget.SOFTWARE, theme.withSoftwareKeyboardDefaults().toSettingsTheme()
                )
            }
            resultData = "Showcase settings applied"
        } catch (error: Exception) {
            resultCode = 1
            resultData = error.message
        }
    }

    /**
     * Replaces the Markdown editor's single notes collection with one synthetic note.
     * The original notes preferences are saved once and put back by `--ez restore true`.
     */
    private fun applyNotePreset(context: Context, preset: JSONObject) {
        val prefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
        val backup = java.io.File(context.filesDir, NOTES_BACKUP)
        if (!backup.exists()) {
            val saved = JSONObject()
            prefs.all.forEach { (key, value) -> saved.put(key, encodePref(value)) }
            backup.writeText(saved.toString())
        }
        val body = preset.optString("body")
        val title = preset.optString("title")
        val cursor = preset.optInt("cursor", body.length).coerceIn(0, body.length)
        val note = JSONObject().put("id", NOTE_ID).put("title", title).put("body", body)
            .put("cursor", cursor).put("headingFocused", false)
        val editor = prefs.edit()
            .putString("collection", JSONArray().put(note).toString())
            .putString("active_note", NOTE_ID)
            .putString("heading", title)
            .putString("body", body)
            .putInt("cursor", cursor)
            .putInt("view_mode", 0)
        require(editor.commit()) { "Notes could not be saved" }
    }

    private fun restoreNotesPreferences(context: Context): Boolean {
        val backup = java.io.File(context.filesDir, NOTES_BACKUP)
        if (!backup.exists()) return false
        val saved = JSONObject(backup.readText())
        val editor = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE).edit()
        saved.keys().forEach { key -> editor.putEncoded(key, saved.getJSONObject(key)) }
        require(editor.commit()) { "Notes restore failed" }
        require(backup.delete()) { "Could not remove notes backup" }
        return true
    }

    private fun encodePref(value: Any?): JSONObject = when (value) {
        null -> JSONObject().put("type", "absent")
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is String -> JSONObject().put("type", "string").put("value", value)
        is Set<*> -> JSONObject().put("type", "set").put("value", JSONArray(value.toList()))
        else -> error("Unsupported original type")
    }

    private fun android.content.SharedPreferences.Editor.putEncoded(key: String, item: JSONObject): android.content.SharedPreferences.Editor =
        when (item.getString("type")) {
            "absent" -> remove(key)
            "boolean" -> putBoolean(key, item.getBoolean("value"))
            "int" -> putInt(key, item.getInt("value"))
            "long" -> putLong(key, item.getLong("value"))
            "float" -> putFloat(key, item.getDouble("value").toFloat())
            "string" -> putString(key, item.getString("value"))
            "set" -> putStringSet(key, item.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() })
            else -> error("Unsupported backup type")
        }

    companion object {
        private const val NOTES_PREFS = "notes"
        private const val NOTES_BACKUP = "showcase-notes-backup.json"
        private const val NOTE_ID = "showcase-note"

        private val allowed = setOf(
            "app_language_tag", "software_keyboard_mode", "software_keyboard_mode_runtime_override",
            "suggestions_enabled", "status_bar_variations_visible", "modifier_indicator_mode",
            "slim_mode_override", "slim_mode_active", "keyboard_bar_arrangement",
            "keyboard_bar_slim_suggestions", "keyboard_bar_slim_actions", "keyboard_bar_slim_bottom",
            "keyboard_bar_slim_android", "keyboard_bar_slim_inline", "status_bar_slots_left",
            "status_bar_slots_right", "status_bar_slot_left", "status_bar_slot_right_1", "status_bar_slot_right_2",
            "keyboard_theme_hardware", "keyboard_theme_software", "keyboard_theme_assignment_mode_hardware",
            "keyboard_theme_assignment_mode_software", "snippets_enabled", "snippets_prefix", "snippets_v1",
            "snippets_presentation", "snippets_accept_with_tab", "emoji_shortcodes_enabled", "symbol_shortcodes_enabled",
            "keyboard_layout", "keyboard_layout_auto_by_locale", "keyboard_layout_list",
            "auto_correct_enabled", "auto_capitalize_first_letter", "auto_show_keyboard",
            "titan2_elite_rounded_corner_insets", "titan2_elite_fill_bottom_corners",
            "titan2_elite_top_corner_multiplier", "clipboard_history_enabled",
            "software_keyboard_number_row_enabled", "software_keyboard_layout_style"
        )
    }
}
