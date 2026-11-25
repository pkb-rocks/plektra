package rocks.pkb.plektra

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import rocks.pkb.plektra.backup.BackupManager
import rocks.pkb.plektra.backup.BackupResult
import rocks.pkb.plektra.backup.RestoreManager
import rocks.pkb.plektra.backup.RestoreResult
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "notnight")
class NotesAppearanceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val notePrefs get() = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    @Before
    fun resetState() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        notePrefs.edit().clear().commit()
    }

    private fun rootColor(activity: NotesActivity): Int {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        return (root.background as ColorDrawable).color
    }

    private fun isLightDialogTheme(context: Context): Boolean {
        val value = TypedValue()
        assertTrue(
            "Expected android.R.attr.isLightTheme to resolve on AlertDialog theme",
            context.theme.resolveAttribute(android.R.attr.isLightTheme, value, true)
        )
        return value.data != 0
    }

    @Test
    fun forcedDarkStandardOnLightOsMatchesDarkSystemBarsAndLightIcons() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(
                themeMode = AppThemeMode.DARK,
                darkSurfaceStyle = AppDarkSurfaceStyle.STANDARD,
                dynamicColor = false
            )
        )
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val expectedPaper = Color.rgb(28, 29, 31)
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

            assertEquals(expectedPaper, rootColor(activity))
            assertEquals(expectedPaper, (activity.window.decorView.background as? ColorDrawable)?.color)
            @Suppress("DEPRECATION")
            assertEquals(expectedPaper, activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(expectedPaper, activity.window.navigationBarColor)
            @Suppress("DEPRECATION")
            assertTrue(ColorUtils.calculateLuminance(activity.window.statusBarColor) < 0.5)
            @Suppress("DEPRECATION")
            assertTrue(ColorUtils.calculateLuminance(activity.window.navigationBarColor) < 0.5)
            assertFalse(insets.isAppearanceLightStatusBars)
            assertFalse(insets.isAppearanceLightNavigationBars)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun forcedDarkOledOnLightOsMatchesBlackSystemBarsAndLightIcons() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(
                themeMode = AppThemeMode.DARK,
                darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
                dynamicColor = false
            )
        )
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

            assertEquals(Color.BLACK, rootColor(activity))
            assertEquals(Color.BLACK, (activity.window.decorView.background as? ColorDrawable)?.color)
            @Suppress("DEPRECATION")
            assertEquals(Color.BLACK, activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(Color.BLACK, activity.window.navigationBarColor)
            @Suppress("DEPRECATION")
            assertTrue(ColorUtils.calculateLuminance(activity.window.statusBarColor) < 0.5)
            @Suppress("DEPRECATION")
            assertTrue(ColorUtils.calculateLuminance(activity.window.navigationBarColor) < 0.5)
            assertFalse(insets.isAppearanceLightStatusBars)
            assertFalse(insets.isAppearanceLightNavigationBars)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun forcedLightOnDarkOsMatchesLightSystemBarsAndDarkIcons() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(
                themeMode = AppThemeMode.LIGHT,
                darkSurfaceStyle = AppDarkSurfaceStyle.OLED,
                dynamicColor = false
            )
        )
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val expectedPaper = Color.rgb(250, 250, 248)
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

            assertEquals(expectedPaper, rootColor(activity))
            assertEquals(expectedPaper, (activity.window.decorView.background as? ColorDrawable)?.color)
            @Suppress("DEPRECATION")
            assertEquals(expectedPaper, activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(expectedPaper, activity.window.navigationBarColor)
            @Suppress("DEPRECATION")
            assertTrue(ColorUtils.calculateLuminance(activity.window.statusBarColor) > 0.5)
            @Suppress("DEPRECATION")
            assertTrue(ColorUtils.calculateLuminance(activity.window.navigationBarColor) > 0.5)
            assertTrue(insets.isAppearanceLightStatusBars)
            assertTrue(insets.isAppearanceLightNavigationBars)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun retainedNotesAdoptsAppearanceChangesOnResumeWithoutLosingNoteFocusOrSelection() {
        val repo = NotesRepository(notePrefs)
        val firstNote = repo.active().copy(title = "First note", body = "First body")
        repo.save(firstNote)
        val secondNote = repo.create().copy(title = "Second title", body = "Keep this selection intact")
        repo.save(secondNote)
        repo.select(secondNote.id)

        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
        )

        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val heading = activity.findViewById<EditText>(R.id.notes_heading)
            val editor = activity.findViewById<EditText>(R.id.notes_body)
            val lightPaper = Color.rgb(250, 250, 248)

            assertEquals(lightPaper, rootColor(activity))
            assertEquals("Second title", heading.text.toString())
            assertEquals("Keep this selection intact", editor.text.toString())
            editor.requestFocus()
            editor.setSelection(5, 14)
            assertTrue(editor.hasFocus())

            // Background activity, switch to Dark OLED, and return to the same instance
            controller.pause().stop()
            SettingsManager.setAppAppearancePreferences(
                context,
                AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.OLED, dynamicColor = false)
            )
            controller.start().resume().visible()

            val darkInsets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertEquals(Color.BLACK, rootColor(activity))
            @Suppress("DEPRECATION")
            assertEquals(Color.BLACK, activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(Color.BLACK, activity.window.navigationBarColor)
            assertFalse(darkInsets.isAppearanceLightStatusBars)
            assertFalse(darkInsets.isAppearanceLightNavigationBars)
            assertEquals(Color.rgb(237, 237, 235), editor.currentTextColor)
            assertEquals("Second title", heading.text.toString())
            assertEquals("Keep this selection intact", editor.text.toString())
            assertTrue("Editor focus must survive appearance update", editor.hasFocus())
            assertEquals(5, editor.selectionStart)
            assertEquals(14, editor.selectionEnd)
            assertEquals(secondNote.id, repo.active().id)

            // Switch again to Dark Standard and then back to Light while preserving heading focus
            heading.requestFocus()
            heading.setSelection(1, 6)
            controller.pause().stop()
            SettingsManager.setAppAppearancePreferences(
                context,
                AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
            )
            controller.start().resume().visible()

            val grayPaper = Color.rgb(28, 29, 31)
            assertEquals(grayPaper, rootColor(activity))
            @Suppress("DEPRECATION")
            assertEquals(grayPaper, activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(grayPaper, activity.window.navigationBarColor)
            assertTrue("Heading focus must survive appearance update", heading.hasFocus())
            assertEquals(1, heading.selectionStart)
            assertEquals(6, heading.selectionEnd)
            assertEquals(secondNote.id, repo.active().id)

            controller.pause().stop()
            SettingsManager.setAppAppearancePreferences(
                context,
                AppAppearancePreferences(AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
            )
            controller.start().resume().visible()

            val lightInsets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertEquals(lightPaper, rootColor(activity))
            @Suppress("DEPRECATION")
            assertEquals(lightPaper, activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(lightPaper, activity.window.navigationBarColor)
            assertTrue(lightInsets.isAppearanceLightStatusBars)
            assertTrue(lightInsets.isAppearanceLightNavigationBars)
            assertEquals(Color.rgb(30, 34, 36), editor.currentTextColor)
            assertTrue(heading.hasFocus())
            assertEquals(1, heading.selectionStart)
            assertEquals(6, heading.selectionEnd)
            assertEquals(secondNote.id, repo.active().id)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun retainedNotesKeepsRenderedPreviewSelectionAcrossAppearanceChange() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
        )
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val editor = activity.findViewById<EditText>(R.id.notes_body)
            editor.setText("Keep **rendered** preview selection")
            activity.findViewById<View>(R.id.notes_preview_toggle).performClick()
            val preview = activity.findViewById<TextView>(R.id.notes_preview_body)
            val renderedText = preview.text.toString()
            assertEquals("Keep rendered preview selection", renderedText)
            val renderedBefore = preview.text
            Selection.setSelection(preview.text as Spannable, 5, 13)

            controller.pause().stop()
            SettingsManager.setAppAppearancePreferences(
                context,
                AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
            )
            controller.start().resume().visible()

            assertEquals(Color.rgb(28, 29, 31), rootColor(activity))
            assertEquals(Color.rgb(237, 237, 235), preview.currentTextColor)
            assertTrue("Preview must be re-rendered for the new appearance", preview.text !== renderedBefore)
            assertEquals(renderedText, preview.text.toString())
            assertEquals(5, preview.selectionStart)
            assertEquals(13, preview.selectionEnd)
            assertEquals("Keep **rendered** preview selection", editor.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun retainedNotesAdoptsRestoredBackupAppearanceWithoutLosingEditingState() = runBlocking {
        val repo = NotesRepository(notePrefs)
        val retainedNote = repo.active().copy(title = "Backup note", body = "Restored appearance check")
        repo.save(retainedNote)
        repo.select(retainedNote.id)

        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.OLED, dynamicColor = false)
        )
        val backupZip = File.createTempFile("notes_appearance_restore_", ".zip", context.cacheDir)
        try {
            val backupResult = BackupManager.createBackup(context, Uri.fromFile(backupZip))
            assertTrue("Backup failed: $backupResult", backupResult is BackupResult.Success)

            SettingsManager.setAppAppearancePreferences(
                context,
                AppAppearancePreferences(AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, dynamicColor = true)
            )
            val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
            try {
                val activity = controller.get()
                val editor = activity.findViewById<EditText>(R.id.notes_body)
                val heading = activity.findViewById<EditText>(R.id.notes_heading)
                assertEquals(Color.rgb(250, 250, 248), rootColor(activity))

                editor.requestFocus()
                editor.setSelection(3, 9)

                controller.pause().stop()
                val restoreResult = RestoreManager.restore(
                    context = context,
                    sourceUri = Uri.fromFile(backupZip),
                    importMode = RestoreManager.ImportMode.UNCHANGED
                )
                assertTrue("Restore failed: $restoreResult", restoreResult is RestoreResult.Success)
                controller.start().resume().visible()

                val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertEquals(Color.BLACK, rootColor(activity))
                @Suppress("DEPRECATION")
                assertEquals(Color.BLACK, activity.window.statusBarColor)
                @Suppress("DEPRECATION")
                assertEquals(Color.BLACK, activity.window.navigationBarColor)
                assertFalse(insets.isAppearanceLightStatusBars)
                assertFalse(insets.isAppearanceLightNavigationBars)
                assertEquals("Backup note", heading.text.toString())
                assertEquals("Restored appearance check", editor.text.toString())
                assertTrue(editor.hasFocus())
                assertEquals(3, editor.selectionStart)
                assertEquals(9, editor.selectionEnd)
                assertEquals(retainedNote.id, repo.active().id)
            } finally {
                controller.pause().stop().destroy()
            }
        } finally {
            backupZip.delete()
        }
    }

    @Test
    fun notesListDialogUsesDarkAlertThemeWhenAppDarkOverridesLightOs() {
        SettingsManager.setAppThemeMode(context, AppThemeMode.DARK)
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val moreButton = findViewByContentDescription(
                activity.findViewById(android.R.id.content),
                activity.getString(R.string.notes_title)
            )
            moreButton.performClick()

            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFalse(
                "Notes list dialog should use dark AlertDialog theme in forced Dark mode",
                isLightDialogTheme(dialog.context)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun notesListDialogUsesLightAlertThemeWhenAppLightOverridesDarkOs() {
        SettingsManager.setAppThemeMode(context, AppThemeMode.LIGHT)
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val moreButton = findViewByContentDescription(
                activity.findViewById(android.R.id.content),
                activity.getString(R.string.notes_title)
            )
            moreButton.performClick()

            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(
                "Notes list dialog should use light AlertDialog theme in forced Light mode",
                isLightDialogTheme(dialog.context)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun findViewByContentDescription(root: View, description: String): View {
        val matches = ArrayList<View>()
        root.findViewsWithText(matches, description, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        assertTrue("Expected view with contentDescription='$description'", matches.isNotEmpty())
        return matches.first()
    }
}
