package rocks.pkb.plektra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The system photo picker is a separate activity; Android may recreate the settings activity meanwhile. */
@RunWith(RobolectricTestRunner::class)
// Tall enough that the draft editor's photo card is on screen without scrolling the whole page.
@Config(sdk = [33], qualifiers = "w411dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ThemePhotoPickerRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var photo: Uri
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        ThemeTextureStore.invalidate()
        val bytes = javaClass.classLoader!!.getResourceAsStream("theme-backdrop.png")!!.use { it.readBytes() }
        photo = Uri.fromFile(File(context.cacheDir, "picked.png").apply { writeBytes(bytes) })
        for (name in listOf("Photo draft", "Other draft")) {
            SettingsManager.saveKeyboardThemeDraft(context, SettingsManager.KeyboardThemeDraft(name,
                SettingsManager.getKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE),
                KEYBOARD_THEME_DRAFT_REQUIRED_FIELDS))
        }
    }

    @After fun tearDown() { scenario?.close() }

    private fun open(target: SettingsManager.KeyboardThemeTarget) {
        val intent = Intent(context, SettingsActivity::class.java).putSettingsPage(SettingsPage(
            SettingsDestination.Customization, "keyboard_theme", target.name, KeyboardThemeEditorTab.Colors.name))
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    private fun openDraft(name: String) {
        // The theme row and the preview pager are both lazy; only the row knows draft keys.
        val lists = compose.onAllNodes(hasScrollToKeyAction())
        val row = (0 until lists.fetchSemanticsNodes().size).first { index ->
            runCatching { lists[index].performScrollToKey("draft:$name") }.isSuccess
        }
        assertNotNull(row)
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
    }

    /** Opens the picker and returns its request code, as the platform would deliver the result. */
    private fun choosePhoto(): Int {
        compose.onNodeWithText(context.getString(R.string.theme_background_photo)).performScrollTo()
        compose.onNodeWithText(context.getString(R.string.theme_background_photo)).performClick()
        compose.waitForIdle()
        var requestCode = -1
        scenario!!.onActivity { activity ->
            val started = shadowOf(activity).nextStartedActivityForResult
            assertNotNull("The photo picker must have been launched", started)
            requestCode = started.requestCode
        }
        return requestCode
    }

    private fun deliver(requestCode: Int) {
        scenario!!.onActivity { shadowOf(it).callOnActivityResult(requestCode, Activity.RESULT_OK, Intent().setData(photo)) }
        // The platform delivers results between frames; under Robolectric the state write needs an explicit apply.
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun draftPhoto(name: String) = SettingsManager.getKeyboardThemeDrafts(context)
        .first { it.name == name }.theme.textures["background"]

    private fun fixedPhoto(target: SettingsManager.KeyboardThemeTarget) =
        SettingsManager.getKeyboardTheme(context, target).textures["background"]

    private fun awaitImport(check: () -> Boolean) = compose.waitUntil(10_000) { check() }

    @Test fun pickedPhotoReachesTheOpenDraftAfterActivityRecreation() {
        open(SettingsManager.KeyboardThemeTarget.SOFTWARE)
        openDraft("Photo draft")
        val request = choosePhoto()
        scenario!!.recreate()
        compose.waitForIdle()
        deliver(request)
        awaitImport { draftPhoto("Photo draft") != null }
        assertEquals(null, draftPhoto("Other draft"))
        assertEquals(null, fixedPhoto(SettingsManager.KeyboardThemeTarget.HARDWARE))
        assertEquals(null, fixedPhoto(SettingsManager.KeyboardThemeTarget.SOFTWARE))
    }

    @Test fun pickedPhotoReachesOnlyTheSoftwareThemeAfterActivityRecreation() {
        open(SettingsManager.KeyboardThemeTarget.SOFTWARE)
        val request = choosePhoto()
        scenario!!.recreate()
        compose.waitForIdle()
        deliver(request)
        awaitImport { fixedPhoto(SettingsManager.KeyboardThemeTarget.SOFTWARE) != null }
        assertEquals(null, fixedPhoto(SettingsManager.KeyboardThemeTarget.HARDWARE))
    }

    @Test fun resultForALeftEditorIsNotAppliedToTheNextOne() {
        open(SettingsManager.KeyboardThemeTarget.SOFTWARE)
        openDraft("Photo draft")
        val request = choosePhoto()
        openDraft("Other draft")
        deliver(request)
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(null, draftPhoto("Photo draft"))
        assertEquals(null, draftPhoto("Other draft"))
        assertFalse(fixedPhoto(SettingsManager.KeyboardThemeTarget.SOFTWARE) != null)
        assertEquals(null, fixedPhoto(SettingsManager.KeyboardThemeTarget.HARDWARE))
    }
}
