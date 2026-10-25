package rocks.pkb.plektra.ring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * the ring holds the screen for exactly the configured time and then lets go
 * (brobata/physiboard#15). These pin the arithmetic; the wake lock itself needs a device.
 */
class RingDurationTest {

    @Test
    fun oneMinuteHoldsForExactlyOneMinute() {
        assertEquals(60_000L, RingDuration.holdMillis(1, demo = false))
    }

    @Test
    fun theSliderRangeMapsToWholeMinutes() {
        assertEquals(10 * 60_000L, RingDuration.holdMillis(10, demo = false))
        assertEquals(60 * 60_000L, RingDuration.holdMillis(60, demo = false))
    }

    @Test
    fun aRestoredValueOutsideTheRangeIsClamped() {
        assertEquals(60_000L, RingDuration.holdMillis(0, demo = false))
        assertEquals(60_000L, RingDuration.holdMillis(-5, demo = false))
        assertEquals(60 * 60_000L, RingDuration.holdMillis(10_000, demo = false))
    }

    @Test
    fun theDemoIgnoresTheSetting() {
        assertEquals(RingDuration.DEMO_MS, RingDuration.holdMillis(30, demo = true))
    }
}
