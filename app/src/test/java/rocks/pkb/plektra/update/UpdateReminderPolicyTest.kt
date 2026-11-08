package rocks.pkb.plektra.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdateReminderPolicyTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test
    fun newReleaseIsAnnouncedOnceUnlessOff() {
        for (interval in UpdateReminderInterval.entries - UpdateReminderInterval.OFF) {
            assertTrue(interval.name, UpdateReminderPolicy.shouldNotify(interval, "v2", "v1", 0, 10 * day))
            assertTrue(interval.name, UpdateReminderPolicy.shouldNotify(interval, "v2", null, 0, 10 * day))
        }
        assertFalse(UpdateReminderPolicy.shouldNotify(UpdateReminderInterval.OFF, "v2", null, 0, 10 * day))
    }

    @Test
    fun onceNeverRepeatsTheSameRelease() {
        assertFalse(UpdateReminderPolicy.shouldNotify(UpdateReminderInterval.ONCE, "v2", "v2", 0, 30 * day))
    }

    @Test
    fun repeatingIntervalsWaitUntilDueWithCheckSlack() {
        val sent = 100 * day
        assertFalse(UpdateReminderPolicy.shouldNotify(UpdateReminderInterval.EVERY_THREE_DAYS, "v2", "v2", sent, sent + 2 * day))
        // A check six hours early would otherwise postpone the reminder by a whole check period.
        assertTrue(UpdateReminderPolicy.shouldNotify(UpdateReminderInterval.EVERY_THREE_DAYS, "v2", "v2", sent, sent + 3 * day - hour))
        assertFalse(UpdateReminderPolicy.shouldNotify(UpdateReminderInterval.WEEKLY, "v2", "v2", sent, sent + 6 * day))
        assertTrue(UpdateReminderPolicy.shouldNotify(UpdateReminderInterval.DAILY, "v2", "v2", sent, sent + day))
    }

    @Test
    fun defaultsDependOnChannel() {
        assertEquals(UpdateReminderInterval.ONCE, UpdateReminderInterval.defaultFor("nightly"))
        assertEquals(UpdateReminderInterval.EVERY_THREE_DAYS, UpdateReminderInterval.defaultFor("stable"))
        assertEquals(UpdateReminderInterval.WEEKLY, UpdateReminderInterval.fromPersistedValue("weekly"))
    }
}
