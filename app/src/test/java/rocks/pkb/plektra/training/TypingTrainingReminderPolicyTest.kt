package rocks.pkb.plektra.training

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TypingTrainingReminderPolicyTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val now = 1_000 * day

    @Test
    fun remindsAtMostWeekly() {
        assertFalse(TypingTrainingReminderPolicy.shouldRemind(now - 3 * day, null, 5, now))
        assertTrue(TypingTrainingReminderPolicy.shouldRemind(now - 7 * day, null, 5, now))
        assertTrue(TypingTrainingReminderPolicy.shouldRemind(0, null, 0, now))
    }

    @Test
    fun staysQuietAfterRecentPracticeWithEverythingShared() {
        assertFalse(TypingTrainingReminderPolicy.shouldRemind(0, now - 2 * day, 0, now))
        assertTrue(TypingTrainingReminderPolicy.shouldRemind(0, now - 2 * day, 1, now))
        assertTrue(TypingTrainingReminderPolicy.shouldRemind(0, now - 8 * day, 0, now))
        // The check slack moves the reminder interval only, not the week without practice.
        assertFalse(TypingTrainingReminderPolicy.shouldRemind(0, now - 7 * day + hour, 0, now))
    }

    @Test
    fun checkShortlyBeforeTheWeekStillReminds() {
        assertTrue(TypingTrainingReminderPolicy.shouldRemind(now - 7 * day + hour, null, 1, now))
    }
}
