package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grant/revoke audit waits for Android's permission state to settle (issue #1, B3). */
class PermissionSettleTest {
    private var clock = 0L
    private val sleeps = mutableListOf<Long>()

    private fun await(expected: Boolean, read: () -> Boolean) = PrivilegedSetup.awaitState(
        expected = expected,
        timeoutMs = 1_000,
        pollMs = 50,
        now = { clock },
        sleep = { clock += it; sleeps += it },
        read = read,
    )

    @Test
    fun alreadySettledDoesNotSleep() {
        assertTrue(await(expected = true) { true })
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun grantThatLandsAfterAFewPollsIsReportedGranted() {
        var reads = 0
        assertTrue(await(expected = true) { ++reads > 3 })
        assertEquals(listOf(50L, 50L, 50L), sleeps)
    }

    @Test
    fun revokeThatLandsLateIsReportedRevoked() {
        var reads = 0
        assertFalse(await(expected = false) { ++reads <= 2 })
        assertEquals(listOf(50L, 50L), sleeps)
    }

    @Test
    fun neverSettlingGivesUpAtTheDeadlineWithTheLastValue() {
        assertFalse(await(expected = true) { false })
        assertEquals(1_000L, clock)
    }
}
