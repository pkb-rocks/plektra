package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.robolectric.Shadows.shadowOf
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherActivityTest {

    @Test
    fun backClosesOnKeyUpSoReleaseIsNotForwardedToUnderlyingApp() {
        SettingsManager.setQuickLauncherAnimationDurationMs(RuntimeEnvironment.getApplication(), 0)
        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)

        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_BACK, down))
        assertFalse(activity.isFinishing)

        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_BACK, up))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun heldSymPlusQuickLauncherShortcutTogglesOpenLauncherClosedOnKeyUp() {
        SettingsManager.setQuickLauncherShortcut(
            RuntimeEnvironment.getApplication(),
            KeyEvent.KEYCODE_SPACE
        )
        SettingsManager.setQuickLauncherAnimationDurationMs(RuntimeEnvironment.getApplication(), 0)
        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()
        val down = KeyEvent(
            1_000L,
            1_020L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SPACE,
            0,
            KeyEvent.META_SYM_ON
        )
        val up = KeyEvent(
            1_000L,
            1_040L,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_SPACE,
            0,
            KeyEvent.META_SYM_ON
        )

        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_SPACE, down))
        assertFalse(activity.isFinishing)

        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_SPACE, up))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun plainSymKeyIsNotConsumedByQuickLauncherActivity() {
        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM)

        assertFalse(activity.onKeyDown(KeyEvent.KEYCODE_SYM, down))
        assertFalse(activity.onKeyUp(KeyEvent.KEYCODE_SYM, up))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun normalLetterStillAddsToSearchQuery() {
        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)

        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_A, event))
    }

    @Test
    fun altModifiedLetterStillUsesQuickLauncherLayoutSearch() {
        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()
        val event = KeyEvent(
            1_000L,
            1_020L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_A,
            0,
            KeyEvent.META_ALT_ON
        )

        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_A, event))
    }

    @Test
    fun closingWithAPositiveAnimationSettingFinishesAtOnce() {
        SettingsManager.setQuickLauncherAnimationDurationMs(RuntimeEnvironment.getApplication(), 120)
        val controller = Robolectric.buildActivity(QuickLauncherActivity::class.java).setup()
        val activity = controller.get()
        // Opening: input is taken while the enter animation is still running.
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertEquals("a", activity.debugQueryText())

        controller.newIntent(QuickLauncherActivity.createToggleIntent(activity))
        assertTrue(activity.debugIsClosing())
        // Finished at once: only a finished activity hands the activity focus back.
        assertTrue(activity.isFinishing)
        // No exit animation of its own: closing is a task switch, which activity overrides miss.
        assertEquals(0, shadowOf(activity).pendingTransitionExitAnimationResourceId)

        // Events still delivered meanwhile: no query change, no Enter launch, no second finish path.
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_B, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)))
        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_B, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B)))
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        assertEquals("a", activity.debugQueryText())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun withoutAnimationClosingFinishesAtOnceToo() {
        SettingsManager.setQuickLauncherAnimationDurationMs(RuntimeEnvironment.getApplication(), 0)
        val controller = Robolectric.buildActivity(QuickLauncherActivity::class.java).setup()
        val activity = controller.get()
        controller.newIntent(QuickLauncherActivity.createToggleIntent(activity))
        assertTrue(activity.debugIsClosing())
        assertTrue(activity.isFinishing)
        assertEquals(0, shadowOf(activity).pendingTransitionExitAnimationResourceId)
    }

    @Test
    fun backDismissalFinishesOnKeyUpAndBlocksLaterInput() {
        SettingsManager.setQuickLauncherAnimationDurationMs(RuntimeEnvironment.getApplication(), 120)
        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java).setup().get()
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertFalse(activity.isFinishing)
        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertTrue(activity.isFinishing)
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_Q, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q)))
        assertEquals("", activity.debugQueryText())
    }

    @Test
    fun quickLauncherIntentsSuppressSystemActivityAnimation() {
        val context = RuntimeEnvironment.getApplication()

        val toggleFlags = QuickLauncherActivity.createToggleIntent(context).flags
        val openFlags = QuickLauncherActivity.createOpenIntent(context).flags

        assertNotEquals(0, toggleFlags and android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
        assertNotEquals(0, openFlags and android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
