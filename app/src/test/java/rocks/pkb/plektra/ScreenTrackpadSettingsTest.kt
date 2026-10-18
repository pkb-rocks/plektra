package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenTrackpadSettingsTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("plektra_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsAreOffWithSpaceHoldAndTitan2EliteSensitivity() {
        val context = RuntimeEnvironment.getApplication()

        assertFalse(SettingsManager.isScreenTrackpadEnabled(context))
        assertEquals(SettingsManager.SCREEN_TRACKPAD_TRIGGER_SPACE, SettingsManager.getScreenTrackpadTriggerKey(context))
        assertEquals(SettingsManager.SCREEN_TRACKPAD_ACTIVATION_HOLD, SettingsManager.getScreenTrackpadActivation(context))
        assertEquals(32, SettingsManager.getScreenTrackpadStepPx(context))
        assertEquals(true, SettingsManager.isScreenTrackpadHintEnabled(context))
    }

    @Test
    fun valuesPersistAndUnknownChoicesFallBackToDefaults() {
        val context = RuntimeEnvironment.getApplication()

        SettingsManager.setScreenTrackpadEnabled(context, true)
        SettingsManager.setScreenTrackpadTriggerKey(context, SettingsManager.SCREEN_TRACKPAD_TRIGGER_SHIFT_EITHER)
        SettingsManager.setScreenTrackpadActivation(context, SettingsManager.SCREEN_TRACKPAD_ACTIVATION_DOUBLE_TAP)
        SettingsManager.setScreenTrackpadHintEnabled(context, false)

        assertEquals(true, SettingsManager.isScreenTrackpadEnabled(context))
        assertEquals(SettingsManager.SCREEN_TRACKPAD_TRIGGER_SHIFT_EITHER, SettingsManager.getScreenTrackpadTriggerKey(context))
        assertEquals(SettingsManager.SCREEN_TRACKPAD_ACTIVATION_DOUBLE_TAP, SettingsManager.getScreenTrackpadActivation(context))
        assertFalse(SettingsManager.isScreenTrackpadHintEnabled(context))

        SettingsManager.setScreenTrackpadTriggerKey(context, "bogus")
        SettingsManager.setScreenTrackpadActivation(context, "bogus")
        assertEquals(SettingsManager.SCREEN_TRACKPAD_TRIGGER_SPACE, SettingsManager.getScreenTrackpadTriggerKey(context))
        assertEquals(SettingsManager.SCREEN_TRACKPAD_ACTIVATION_HOLD, SettingsManager.getScreenTrackpadActivation(context))
    }

    @Test
    fun stepPxIsClampedToTheSupportedRange() {
        val context = RuntimeEnvironment.getApplication()

        SettingsManager.setScreenTrackpadStepPx(context, 1)
        assertEquals(SettingsManager.MIN_SCREEN_TRACKPAD_STEP_PX, SettingsManager.getScreenTrackpadStepPx(context))

        SettingsManager.setScreenTrackpadStepPx(context, 500)
        assertEquals(SettingsManager.MAX_SCREEN_TRACKPAD_STEP_PX, SettingsManager.getScreenTrackpadStepPx(context))

        SettingsManager.setScreenTrackpadStepPx(context, 40)
        assertEquals(40, SettingsManager.getScreenTrackpadStepPx(context))
    }

    @Test
    fun settingLinksRouteThroughTheTrackpadScreenInAdvancedSettings() {
        val ids = setOf(
            SettingLinkIds.TRACKPAD_SCREEN_TRACKPAD,
            SettingLinkIds.SCREEN_TRACKPAD_ENABLED,
            SettingLinkIds.SCREEN_TRACKPAD_TRIGGER_KEY,
            SettingLinkIds.SCREEN_TRACKPAD_ACTIVATION,
            SettingLinkIds.SCREEN_TRACKPAD_SENSITIVITY,
            SettingLinkIds.SCREEN_TRACKPAD_SHOW_HINT
        )

        ids.forEach { id ->
            val entry = requireNotNull(SettingLinkRegistry.byId(id)) { "missing registry entry for $id" }
            assertEquals(SettingsDestination.Advanced, entry.route.destination)
        }
        assertNotNull(SettingLinkRegistry.keywordsById[SettingLinkIds.TRACKPAD_SCREEN_TRACKPAD])
    }
}
