package rocks.pkb.plektra.backup

import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.AppAppearance
import rocks.pkb.plektra.AppAppearanceColors
import rocks.pkb.plektra.SettingsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppAppearanceBackupTest {
    @Test fun zipRestoresPaletteAfterPreferencesAreCleared() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().clear().commit()
        val palette = AppAppearanceColors(true, 0xff176b9b.toInt(), 0xff83cce5.toInt())
        AppAppearance.save(context, palette)
        val archive = File(context.cacheDir, "app-appearance.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        prefs.edit().clear().commit()
        assertFalse(AppAppearance.read(context).enabled)
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertEquals(palette, AppAppearance.read(context))
    }
}
