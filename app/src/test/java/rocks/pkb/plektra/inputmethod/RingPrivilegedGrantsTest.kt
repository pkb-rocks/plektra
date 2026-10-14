package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * the ring's broker commands are a closed set. These pin the exact lines, so a change is a
 * visible diff in review, and pin that a bad package name never produces a command.
 */
class RingPrivilegedGrantsTest {

    private val app = "rocks.pkb.plektra"
    private val listener = "rocks.pkb.plektra.ring.NotificationRingListener"

    @Test
    fun listenerClassConstantIsTheRingListener() {
        assertEquals(listener, PrivilegedCommand.RING_LISTENER_CLASS)
    }

    /** A reset withdraws the access the broker gave the ring (finding 1.3c). */
    @Test
    fun theRingListenerIsInTheResetLedgersSet() {
        assertEquals("ring", AppNotificationListener.RING.id)
        assertEquals(listener, AppNotificationListener.RING.className)
        assertEquals(
            "cmd notification disallow_listener $app/$listener",
            PrivilegedCommand.DisallowNotificationListener(AppNotificationListener.RING).commandLine(app)
        )
    }

    /** WRITE_SECURE_SETTINGS goes through the holders, never through a ring grant of its own. */
    @Test
    fun noRingGrantGrantsWriteSecureSettings() {
        PrivilegedCommand.RingGrant.entries.forEach { grant ->
            assertFalse(grant.name, grant.line(app).contains("WRITE_SECURE_SETTINGS"))
        }
    }

    @Test
    fun eachGrantIsExactlyOneKnownLine() {
        assertEquals(
            "cmd notification allow_listener $app/$listener",
            PrivilegedCommand.RingGrant.ALLOW_LISTENER.line(app)
        )
        assertEquals(
            "appops set $app USE_FULL_SCREEN_INTENT allow",
            PrivilegedCommand.RingGrant.ALLOW_FULL_SCREEN_INTENT.line(app)
        )
        assertEquals(
            "appops set $app POST_NOTIFICATION allow",
            PrivilegedCommand.RingGrant.ALLOW_POST_NOTIFICATIONS.line(app)
        )
        assertEquals(
            "cmd notification disallow_listener $app/$listener",
            PrivilegedCommand.RingGrant.DISALLOW_LISTENER.line(app)
        )
        assertEquals(
            "appops set $app USE_FULL_SCREEN_INTENT default",
            PrivilegedCommand.RingGrant.RESET_FULL_SCREEN_INTENT.line(app)
        )
    }

    @Test
    fun grantsAreJoinedInOrderIntoOneLine() {
        val line = PrivilegedCommand.RingGrants(
            listOf(
                PrivilegedCommand.RingGrant.ALLOW_LISTENER,
                PrivilegedCommand.RingGrant.ALLOW_POST_NOTIFICATIONS
            )
        ).commandLine(app)
        assertEquals(
            "cmd notification allow_listener $app/$listener; appops set $app POST_NOTIFICATION allow",
            line
        )
    }

    @Test
    fun noPackageNameThatIsNotPlainEverReachesALine() {
        listOf("", "pastiera", "a.b; reboot", "a.b && id", "a.b\nid", "a.b `id`", "a.b|id", "a. b", "a..b").forEach { bad ->
            PrivilegedCommand.RingGrant.entries.forEach { grant ->
                try {
                    PrivilegedCommand.RingGrants(listOf(grant)).commandLine(bad)
                    fail("'$bad' must be refused for $grant")
                } catch (_: IllegalArgumentException) {
                    // expected
                }
            }
        }
    }

    @Test
    fun anEmptyGrantListIsRefused() {
        try {
            PrivilegedCommand.RingGrants(emptyList()).commandLine(app)
            fail("an empty list must be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun noGrantLineCarriesShellMetacharactersBeyondTheJoiner() {
        PrivilegedCommand.RingGrant.entries.forEach { grant ->
            val line = grant.line(app)
            assertFalse(line, line.any { it in "&|`$<>\n\"'\\" })
            assertTrue(line, !line.contains(";"))
        }
    }
}
