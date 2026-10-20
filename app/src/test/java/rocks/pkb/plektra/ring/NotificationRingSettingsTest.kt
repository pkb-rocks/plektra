package rocks.pkb.plektra.ring

import android.content.Context
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** the ring's settings round-trip, and a restored minutes value cannot break the timer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationRingSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun defaults() {
        assertEquals(false, SettingsManager.isNotificationRingEnabled(context))
        assertEquals(SettingsManager.NOTIFICATION_RING_DEFAULT_MINUTES, SettingsManager.getNotificationRingMinutes(context))
        assertEquals(RingBrightness.NORMAL, SettingsManager.getNotificationRingBrightness(context))
        assertEquals(true, SettingsManager.isRingKeyboardDarkEnabled(context))
        assertEquals(NotificationRingPolicy.DEFAULT_COLOR, SettingsManager.getNotificationRingDefaultColor(context))
        assertNull(SettingsManager.getNotificationRingOverride(context))
        assertTrue(SettingsManager.getNotificationRingAppColors(context).isEmpty())
    }

    @Test
    fun minutesAreClampedOnWriteAndOnRead() {
        SettingsManager.setNotificationRingMinutes(context, 0)
        assertEquals(SettingsManager.NOTIFICATION_RING_MIN_MINUTES, SettingsManager.getNotificationRingMinutes(context))
        SettingsManager.setNotificationRingMinutes(context, 500)
        assertEquals(SettingsManager.NOTIFICATION_RING_MAX_MINUTES, SettingsManager.getNotificationRingMinutes(context))
        // A backup restored from elsewhere writes the raw preference.
        SettingsManager.getPreferences(context).edit().putInt("notification_ring_minutes", -3).commit()
        assertEquals(SettingsManager.NOTIFICATION_RING_MIN_MINUTES, SettingsManager.getNotificationRingMinutes(context))
    }

    @Test
    fun appColoursAddAndRemove() {
        SettingsManager.setNotificationRingAppColor(context, "com.example.app", 0xFF112233.toInt())
        assertEquals(0xFF112233.toInt(), SettingsManager.getNotificationRingAppColors(context)["com.example.app"])
        SettingsManager.setNotificationRingAppColor(context, "com.example.app", null)
        assertTrue(SettingsManager.getNotificationRingAppColors(context).isEmpty())
    }

    @Test
    fun theFittedRingRoundTripsAndClears() {
        val ring = RingGeometry.Ring(cx = 78f, cy = 80f, radius = 46f, stroke = 6f)
        SettingsManager.setNotificationRingOverride(context, ring)
        assertEquals(ring, SettingsManager.getNotificationRingOverride(context))
        SettingsManager.clearNotificationRingOverride(context)
        assertNull(SettingsManager.getNotificationRingOverride(context))
    }

    @Test
    fun aRingThatHoldsTheKeyboardOffHandsTheTileTheUsersValue() {
        RingBacklight.switch = object : KeyboardLightSwitch {
            override fun canWrite(context: Context) = true
            override fun read(context: Context, default: Int) = 0
            override fun write(context: Context, value: Int) = true
        }
        try {
            assertNull(RingBacklight.takeUserValueIfSuppressed(context))
            SettingsManager.setRingBacklightSuppressed(context, 1)
            assertEquals(1, RingBacklight.takeUserValueIfSuppressed(context))
            // The claim is gone, so nothing will undo the tile's own write later.
            assertEquals(false, SettingsManager.isRingBacklightSuppressed(context))
        } finally {
            RingBacklight.switch = DirectKeyboardLightSwitch
        }
    }
}
