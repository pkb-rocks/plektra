package rocks.pkb.plektra.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppAppearanceBackupRoundTripTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPrefs() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun appAppearancePreferencesSurviveFullBackupAndRestoreRoundTrip() = runBlocking {
        val expected = AppAppearancePreferences(
            themeMode = AppThemeMode.DARK,
            darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
            dynamicColor = false,
            settingsStyle = AppSettingsStyle.MATERIAL
        )
        SettingsManager.setAppAppearancePreferences(context, expected)

        val backupZip = File.createTempFile("app_appearance_roundtrip_", ".zip", context.cacheDir)
        try {
            val backupResult = BackupManager.createBackup(context, Uri.fromFile(backupZip))
            assertTrue("Backup failed: $backupResult", backupResult is BackupResult.Success)

            val exportedEntries = readPlektraPrefsEntries(backupZip)
            assertEquals("dark", exportedEntries.getJSONObject(SettingsManager.KEY_APP_THEME_MODE).getString("value"))
            assertEquals("oled", exportedEntries.getJSONObject(SettingsManager.KEY_APP_DARK_SURFACE_STYLE).getString("value"))
            assertFalse(exportedEntries.getJSONObject(SettingsManager.KEY_APP_DYNAMIC_COLOR).getBoolean("value"))
            assertEquals("material", exportedEntries.getJSONObject(SettingsManager.KEY_APP_SETTINGS_STYLE).getString("value"))

            // Wipe preferences and confirm defaults are active before restore
            SettingsManager.getPreferences(context).edit().clear().commit()
            assertEquals(AppAppearancePreferences(), SettingsManager.getAppAppearancePreferences(context))

            val restoreResult = RestoreManager.restore(
                context = context,
                sourceUri = Uri.fromFile(backupZip),
                importMode = RestoreManager.ImportMode.UNCHANGED
            )
            val success = restoreResult as RestoreResult.Success
            assertTrue("Unexpected skipped keys: ${success.preferencesSummary.skippedKeys}", success.preferencesSummary.skippedKeys.isEmpty())
            assertEquals(expected, SettingsManager.getAppAppearancePreferences(context))

            // Also verify ADAPT_TO_CURRENT_DEVICE retains appearance preferences
            SettingsManager.getPreferences(context).edit().clear().commit()
            val adaptedRestore = RestoreManager.restore(
                context = context,
                sourceUri = Uri.fromFile(backupZip),
                importMode = RestoreManager.ImportMode.ADAPT_TO_CURRENT_DEVICE
            ) as RestoreResult.Success
            assertTrue(adaptedRestore.preferencesSummary.skippedKeys.isEmpty())
            assertEquals(expected, SettingsManager.getAppAppearancePreferences(context))
        } finally {
            backupZip.delete()
        }
    }

    @Test
    fun legacyBackupWithoutAppearanceKeysKeepsExistingDefaults() {
        val summary = PreferencesBackupHelper.restorePreferences(
            context = context,
            backedUpPrefs = mapOf(
                "plektra_prefs" to mapOf(
                    "app_language_tag" to PreferenceValue(PreferenceValueType.STRING, "de")
                )
            )
        )
        assertTrue(summary.skippedKeys.isEmpty())
        assertEquals(AppAppearancePreferences(), SettingsManager.getAppAppearancePreferences(context))
    }

    @Test
    fun unknownRestoredAppearanceValuesFallBackSafely() {
        val summary = PreferencesBackupHelper.restorePreferences(
            context = context,
            backedUpPrefs = mapOf(
                "plektra_prefs" to mapOf(
                    SettingsManager.KEY_APP_THEME_MODE to PreferenceValue(PreferenceValueType.STRING, "sepia"),
                    SettingsManager.KEY_APP_DARK_SURFACE_STYLE to PreferenceValue(PreferenceValueType.STRING, "neon"),
                    SettingsManager.KEY_APP_DYNAMIC_COLOR to PreferenceValue(PreferenceValueType.BOOLEAN, true),
                    SettingsManager.KEY_APP_SETTINGS_STYLE to PreferenceValue(PreferenceValueType.STRING, "glass")
                )
            )
        )
        assertTrue(summary.skippedKeys.isEmpty())
        assertEquals(AppThemeMode.SYSTEM, SettingsManager.getAppThemeMode(context))
        assertEquals(AppDarkSurfaceStyle.STANDARD, SettingsManager.getAppDarkSurfaceStyle(context))
        assertTrue(SettingsManager.getAppDynamicColorEnabled(context))
        assertEquals(AppSettingsStyle.COMPACT_DIVIDERS, SettingsManager.getAppSettingsStyle(context))
    }

    private fun readPlektraPrefsEntries(zipFile: File): JSONObject {
        ZipInputStream(FileInputStream(zipFile)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.name == "prefs/plektra_prefs.json") {
                    return JSONObject(stream.readBytes().toString(Charsets.UTF_8)).getJSONObject("entries")
                }
            }
        }
        error("prefs/plektra_prefs.json missing from backup archive")
    }
}
