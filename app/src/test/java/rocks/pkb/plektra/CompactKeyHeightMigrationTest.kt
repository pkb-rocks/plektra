package rocks.pkb.plektra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompactKeyHeightMigrationTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun legacyDefaultHeightsBecomeCompactAndCustomHeightsStay() {
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit()
            .putBoolean("compact_software_key_height_migrated_v1", false)
            .putString("keyboard_theme_software", JSONObject().put("key_height_scale", 1.5489256f.toDouble()).toString())
            .putString(
                "keyboard_theme_saved_themes",
                JSONArray()
                    .put(JSONObject().put("name", "Default").put("theme", JSONObject().put("key_height_scale", 1.5489256f.toDouble())))
                    .put(JSONObject().put("name", "Tall").put("theme", JSONObject().put("key_height_scale", 1.7)))
                    .toString()
            )
            .commit()

        SettingsManager.migrateCompactSoftwareKeyHeightOnce(context)

        val software = JSONObject(prefs.getString("keyboard_theme_software", null)!!)
        assertEquals(SOFTWARE_THEME_DEFAULT_KEY_HEIGHT.toDouble(), software.getDouble("key_height_scale"), 1e-6)
        val saved = JSONArray(prefs.getString("keyboard_theme_saved_themes", null)!!)
        assertEquals(SOFTWARE_THEME_DEFAULT_KEY_HEIGHT.toDouble(), saved.getJSONObject(0).getJSONObject("theme").getDouble("key_height_scale"), 1e-6)
        assertEquals(1.7, saved.getJSONObject(1).getJSONObject("theme").getDouble("key_height_scale"), 1e-6)
    }

    @Test
    fun migrationRunsOnlyOnce() {
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().putBoolean("compact_software_key_height_migrated_v1", false).commit()
        SettingsManager.migrateCompactSoftwareKeyHeightOnce(context)
        val legacy = JSONObject().put("key_height_scale", 1.5489256f.toDouble()).toString()
        prefs.edit().putString("keyboard_theme_software", legacy).commit()

        SettingsManager.migrateCompactSoftwareKeyHeightOnce(context)

        assertEquals(legacy, prefs.getString("keyboard_theme_software", null))
        assertNull(SettingsManager.withCompactSoftwareKeyHeight("""{"key_height_scale":1.2}"""))
    }
}
