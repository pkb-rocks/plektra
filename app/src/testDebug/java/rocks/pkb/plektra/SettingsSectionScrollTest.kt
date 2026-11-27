package rocks.pkb.plektra

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h800dp-mdpi")
class SettingsSectionScrollTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scroll: ScrollState
    private var toggled = 0

    private fun page(sections: Int = 6) {
        compose.setContent {
            scroll = androidx.compose.foundation.rememberScrollState()
            SettingsSectionScrollColumn(modifier = Modifier.testTag("page"), scrollState = scroll) {
                repeat(sections) { section ->
                    SettingsSectionDivider("Section $section")
                    repeat(8) { row ->
                        var on by remember { mutableStateOf(false) }
                        SettingsToggleRow(
                            title = "Row $section.$row",
                            checked = on,
                            onCheckedChange = { on = it; toggled++ }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // The tracks are the outer 28 dp of each edge; mdpi makes dp == px.
    private val inStartTrack = 12f
    private val inEndTrack = 399f

    @Test
    fun aVerticalDragOnEitherTrackJumpsStraightToTheSectionUnderTheThumb() {
        page()
        compose.onNodeWithTag("page").performTouchInput { down(Offset(inStartTrack, 10f)); moveTo(Offset(inStartTrack, 790f)); up() }
        compose.waitForIdle()
        assertEquals(scroll.maxValue, scroll.value)
        compose.onNodeWithTag("page").performTouchInput { down(Offset(inEndTrack, 790f)); moveTo(Offset(inEndTrack, 10f)); up() }
        compose.waitForIdle()
        assertEquals(0, scroll.value)
        assertEquals(0, toggled)
    }

    @Test
    fun aTapOnTheTrackStillReachesTheRow() {
        page()
        compose.onNodeWithTag("page").performTouchInput { down(Offset(inEndTrack, 120f)); up() }
        compose.waitForIdle()
        assertEquals(1, toggled)
        assertEquals(0, scroll.value)
    }

    @Test
    fun aSidewaysStartOnTheTrackIsLeftToBackAndThePage() {
        page()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(inStartTrack, 400f)); moveTo(Offset(inStartTrack + 120f, 410f)); moveTo(Offset(inStartTrack + 120f, 790f)); up()
        }
        compose.waitForIdle()
        assertTrue(scroll.value < scroll.maxValue)
    }

    @Test
    fun anOrdinarySwipeInTheMiddleScrolls() {
        page()
        compose.onNodeWithTag("page").performTouchInput { down(Offset(200f, 600f)); moveTo(Offset(200f, 300f)); up() }
        compose.waitForIdle()
        assertTrue(scroll.value in 1 until scroll.maxValue)
    }

    @Test
    fun shortPagesKeepNormalBehavior() {
        page(sections = 2)
        compose.onNodeWithTag("page").performTouchInput { down(Offset(inStartTrack, 10f)); moveTo(Offset(inStartTrack, 790f)); up() }
        compose.waitForIdle()
        assertTrue(scroll.value < scroll.maxValue)
    }
}
