package rocks.pkb.plektra

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.PlektraTheme

/**
 * The header row keeps its height when the search replaces title and subtitle: the
 * content below starts at the same place on opening, closing and after a restore that
 * starts with the search open. Large font and a real wrapping subtitle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-xhdpi", fontScale = 1.5f)
class SettingsHeaderHeightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val search get() = context.getString(R.string.settings_search_hint)
    private val closeSearch get() = context.getString(R.string.settings_search_close)
    private val content = "First content row"

    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSettingsFullscreenEnabled(context, true)
    }

    private fun contentTop(): Dp = compose.onNodeWithText(content).getBoundsInRoot().top

    private fun check(style: AppSettingsStyle) {
        SettingsManager.setAppSettingsStyle(context, style)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PlektraTheme(settingsWindow = true) {
                Scaffold(topBar = {
                    PlektraSettingsHeader(
                        title = context.getString(R.string.keyboard_layout_title),
                        subtitle = "Layout for the built-in keyboard and every connected one",
                        onBack = {}
                    )
                }) { padding -> Text(content, Modifier.padding(padding)) }
            }
        }
        compose.waitForIdle()
        val normal = contentTop()

        compose.onNodeWithContentDescription(search).performClick()
        compose.waitForIdle()
        assertEquals(normal, contentTop())

        // Restored with the search open: the first frame already has the title row's height.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(closeSearch).assertExists()
        assertEquals(normal, contentTop())

        compose.onNodeWithContentDescription(closeSearch).performClick()
        compose.waitForIdle()
        assertEquals(normal, contentTop())
    }

    @Test fun compactDividers() = check(AppSettingsStyle.COMPACT_DIVIDERS)

    @Test fun material() = check(AppSettingsStyle.MATERIAL)
}
