package rocks.pkb.plektra

import android.content.Context
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.PreferencesBackupHelper
import rocks.pkb.plektra.backup.PreferenceValue
import rocks.pkb.plektra.backup.PreferenceValueType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LauncherShortcutsCacheTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = SettingsManager.getPreferences(context)

    @Before fun clear() {
        prefs.edit().clear().putBoolean("quick_launcher_default_assigned", true).commit()
    }

    @Test fun writesRemovalAndSwapInvalidateCachedAssignments() {
        SettingsManager.setLauncherShortcut(context, KeyEvent.KEYCODE_A, "example.one", "One")
        assertEquals("example.one", SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_A)?.packageName)
        assertSame(SettingsManager.getLauncherShortcuts(context), SettingsManager.getLauncherShortcuts(context))
        SettingsManager.setLauncherShortcut(context, KeyEvent.KEYCODE_A, "example.two", "Two")
        assertEquals("example.two", SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_A)?.packageName)
        SettingsManager.swapLauncherShortcuts(context, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B)
        assertNull(SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_A))
        assertEquals("example.two", SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_B)?.packageName)
        SettingsManager.removeLauncherShortcut(context, KeyEvent.KEYCODE_B)
        assertNull(SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_B))
    }

    @Test fun preferenceRestoreAndResetReplaceLiveCache() {
        SettingsManager.setLauncherShortcut(context, KeyEvent.KEYCODE_A, "example.saved", "Saved")
        val saved = prefs.getString("launcher_shortcuts", "{}")!!
        SettingsManager.setLauncherShortcut(context, KeyEvent.KEYCODE_A, "example.new", "New")
        SettingsManager.getLauncherShortcuts(context)
        val summary = PreferencesBackupHelper.restorePreferences(context,
            mapOf("plektra_prefs" to mapOf("launcher_shortcuts" to
                PreferenceValue(PreferenceValueType.STRING, saved))))
        assertTrue(summary.skippedKeys.isEmpty())
        assertEquals("example.saved", SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_A)?.packageName)
        prefs.edit().clear().commit()
        assertNull(SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_A))
    }

    @Test fun malformedJsonDoesNotPoisonLaterReads() {
        prefs.edit().putString("launcher_shortcuts", "invalid").commit()
        assertTrue(SettingsManager.getLauncherShortcuts(context).isEmpty())
        prefs.edit().putString("launcher_shortcuts", "{}").commit()
        SettingsManager.setLauncherShortcut(context, KeyEvent.KEYCODE_A, "example.repaired", "Repaired")
        assertEquals("example.repaired", SettingsManager.getLauncherShortcut(context, KeyEvent.KEYCODE_A)?.packageName)
    }
}
