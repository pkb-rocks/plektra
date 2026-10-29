package rocks.pkb.plektra.statistics

import android.os.Looper
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.PreferencesBackupHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TypingStatisticsBackupTest {
    @Test fun newExportsLeaveCountersOnTheDevice() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = SettingsManager.getPreferences(context)
        preferences.edit().putLong(TypingStatisticsStore.MANUAL_KEY, 12)
            .putLong(TypingStatisticsStore.SUGGESTED_KEY, 20).commit()
        val store = TypingStatisticsStore.get(context)
        store.record(1, 2); store.flush(); preferences.edit().commit()
        val directory = File(context.cacheDir, "statistics-backup").apply { mkdirs() }
        PreferencesBackupHelper.dumpSharedPreferences(context, directory, false)
        val entries = JSONObject(File(directory, "plektra_prefs.json").readText()).getJSONObject("entries")
        assertFalse(entries.has(TypingStatisticsStore.MANUAL_KEY))
        assertFalse(entries.has(TypingStatisticsStore.SUGGESTED_KEY))
        assertEquals(TypingStatisticsSnapshot(13, 22), store.snapshot.value)
    }

    @Test fun olderAndNewerLegacyCountersCannotReplaceLocalTotals() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = SettingsManager.getPreferences(context)
        preferences.edit().putLong(TypingStatisticsStore.MANUAL_KEY, 100)
            .putLong(TypingStatisticsStore.SUGGESTED_KEY, 50).commit()
        val store = TypingStatisticsStore.get(context)
        store.record(2, 3)
        for (importedCount in listOf(1L, 999999L)) {
            val legacy = mapOf("plektra_prefs" to mapOf(
                TypingStatisticsStore.MANUAL_KEY to rocks.pkb.plektra.backup.PreferenceValue(rocks.pkb.plektra.backup.PreferenceValueType.LONG, importedCount),
                TypingStatisticsStore.SUGGESTED_KEY to rocks.pkb.plektra.backup.PreferenceValue(rocks.pkb.plektra.backup.PreferenceValueType.LONG, importedCount)))
            PreferencesBackupHelper.restorePreferences(context, legacy)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(TypingStatisticsSnapshot(102, 53), store.snapshot.value)
        }
        store.flush()
        assertEquals(102L, preferences.getLong(TypingStatisticsStore.MANUAL_KEY, -1))
        assertEquals(53L, preferences.getLong(TypingStatisticsStore.SUGGESTED_KEY, -1))
    }

    @Test fun anImportedCounterCannotSeedAnotherUsersInstallation() {
        val context = RuntimeEnvironment.getApplication()
        val store = TypingStatisticsStore.get(context)
        PreferencesBackupHelper.restorePreferences(context, mapOf("plektra_prefs" to mapOf(
            TypingStatisticsStore.MANUAL_KEY to rocks.pkb.plektra.backup.PreferenceValue(rocks.pkb.plektra.backup.PreferenceValueType.LONG, 12345L))))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, store.snapshot.value.totalWords)
    }

    @Test fun installationDateComesFromAndroidRatherThanImportedPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime = 1600000000000L
        org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(info)
        assertEquals(info.firstInstallTime, TypingStatisticsStore.installationStartedAt(context))
        PreferencesBackupHelper.restorePreferences(context, mapOf("plektra_prefs" to mapOf(
            "typing_statistics_installation_date" to rocks.pkb.plektra.backup.PreferenceValue(rocks.pkb.plektra.backup.PreferenceValueType.LONG, 1700000000000L))))
        assertEquals(1600000000000L, TypingStatisticsStore.installationStartedAt(context))
    }
    @Test fun realZipRestoresSettingsButPreservesLocalCountersAndPendingWrites() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = SettingsManager.getPreferences(context)
        preferences.edit().putBoolean("auto_capitalize_first_letter", false).commit()
        val store = TypingStatisticsStore.get(context)
        store.record(4, 2)
        val archive = File(context.cacheDir, "statistics-portability.zip")
        kotlinx.coroutines.runBlocking {
            assertTrue(rocks.pkb.plektra.backup.BackupManager.createBackup(context, android.net.Uri.fromFile(archive))
                is rocks.pkb.plektra.backup.BackupResult.Success)
        }
        java.util.zip.ZipFile(archive).use { zip ->
            val prefEntry = zip.entries().asSequence().first { it.name.endsWith("plektra_prefs.json") }
            val contents = zip.getInputStream(prefEntry).bufferedReader().use { it.readText() }
            assertFalse(contents.contains(TypingStatisticsStore.MANUAL_KEY))
            assertFalse(contents.contains(TypingStatisticsStore.SUGGESTED_KEY))
        }
        store.record(1, 3)
        preferences.edit().putBoolean("auto_capitalize_first_letter", true).commit()
        kotlinx.coroutines.runBlocking {
            assertTrue(rocks.pkb.plektra.backup.RestoreManager.restore(context, android.net.Uri.fromFile(archive))
                is rocks.pkb.plektra.backup.RestoreResult.Success)
        }
        shadowOf(Looper.getMainLooper()).idle()
        store.flush()
        assertEquals(TypingStatisticsSnapshot(5, 5), store.snapshot.value)
        assertFalse(preferences.getBoolean("auto_capitalize_first_letter", true))
    }

}
