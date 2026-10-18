package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackpadFeatureExclusivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("plektra_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun confirmationIsNeededOnlyWhenTheOtherFeatureIsOn() {
        for (feature in TrackpadFeature.entries) {
            assertTrue(TrackpadFeatureExclusivity.enablingNeedsConfirmation(feature, otherEnabled = true))
            assertFalse(TrackpadFeatureExclusivity.enablingNeedsConfirmation(feature, otherEnabled = false))
        }
    }

    @Test
    fun screenTrackpadIsKeptWhenBothAreOn() {
        assertEquals(TrackpadFeature.SCREEN_TRACKPAD, TrackpadFeatureExclusivity.featureKeptWhenBothOn(true, true))
        assertNull(TrackpadFeatureExclusivity.featureKeptWhenBothOn(true, false))
        assertNull(TrackpadFeatureExclusivity.featureKeptWhenBothOn(false, true))
        assertNull(TrackpadFeatureExclusivity.featureKeptWhenBothOn(false, false))
        assertEquals(TrackpadFeature.TRACKPAD_GESTURES, TrackpadFeature.SCREEN_TRACKPAD.other)
        assertEquals(TrackpadFeature.SCREEN_TRACKPAD, TrackpadFeature.TRACKPAD_GESTURES.other)
    }

    @Test
    fun enablingOneTurnsTheOtherOff() {
        TrackpadFeatureExclusivity.setEnabled(context, TrackpadFeature.TRACKPAD_GESTURES, true)
        assertTrue(SettingsManager.getTrackpadGesturesEnabled(context))
        assertFalse(SettingsManager.isScreenTrackpadEnabled(context))

        TrackpadFeatureExclusivity.setEnabled(context, TrackpadFeature.SCREEN_TRACKPAD, true)
        assertTrue(SettingsManager.isScreenTrackpadEnabled(context))
        assertFalse(SettingsManager.getTrackpadGesturesEnabled(context))

        TrackpadFeatureExclusivity.setEnabled(context, TrackpadFeature.TRACKPAD_GESTURES, true)
        assertTrue(SettingsManager.getTrackpadGesturesEnabled(context))
        assertFalse(SettingsManager.isScreenTrackpadEnabled(context))
    }

    @Test
    fun disablingNeverTouchesTheOther() {
        SettingsManager.setScreenTrackpadEnabled(context, true)
        TrackpadFeatureExclusivity.setEnabled(context, TrackpadFeature.TRACKPAD_GESTURES, false)
        assertTrue(SettingsManager.isScreenTrackpadEnabled(context))
        assertFalse(SettingsManager.getTrackpadGesturesEnabled(context))

        TrackpadFeatureExclusivity.setEnabled(context, TrackpadFeature.SCREEN_TRACKPAD, false)
        assertFalse(SettingsManager.isScreenTrackpadEnabled(context))
        assertFalse(SettingsManager.getTrackpadGesturesEnabled(context))
    }

    @Test
    fun reconcileRepairsBothOnByKeepingScreenTrackpad() {
        // Written directly, as a restored backup or an older build would.
        SettingsManager.setScreenTrackpadEnabled(context, true)
        SettingsManager.setTrackpadGesturesEnabled(context, true)

        assertEquals(TrackpadFeature.TRACKPAD_GESTURES, TrackpadFeatureExclusivity.reconcile(context))
        assertTrue(SettingsManager.isScreenTrackpadEnabled(context))
        assertFalse(SettingsManager.getTrackpadGesturesEnabled(context))

        assertNull(TrackpadFeatureExclusivity.reconcile(context))
        assertTrue(SettingsManager.isScreenTrackpadEnabled(context))
    }

    @Test
    fun reconcileLeavesASingleFeatureAlone() {
        SettingsManager.setTrackpadGesturesEnabled(context, true)
        assertNull(TrackpadFeatureExclusivity.reconcile(context))
        assertTrue(SettingsManager.getTrackpadGesturesEnabled(context))
        assertFalse(SettingsManager.isScreenTrackpadEnabled(context))
    }
}
