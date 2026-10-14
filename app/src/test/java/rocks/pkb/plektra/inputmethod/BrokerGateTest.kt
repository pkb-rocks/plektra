package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import moe.shizuku.manager.adb.EmbeddedAdbInit
import moe.shizuku.manager.adb.SoftwareAdbKeySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The one door to the broker (finding 1.3g), its audit trail (1.3h), the reset flow (1.3b, 1.3c)
 * and the Wireless-debugging setting (1.3e). Nothing here reaches a network: every case is
 * decided before discovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrokerGateTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val source = SoftwareAdbKeySource()
    private val savedFactory = EmbeddedAdbInit.keySourceFactory

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        EmbeddedAdbInit.keySourceFactory = { source }
        BrokerAuditLog.clear(context)
        source.generate()
        // Paired and Wireless debugging on: only the gate stands between a call and the network.
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
    }

    @After
    fun tearDown() {
        EmbeddedAdbInit.keySourceFactory = savedFactory
    }

    private fun refusals() = BrokerAuditLog.entries(context).filter { it.kind == BrokerAuditLog.Kind.REFUSED }

    @Test
    fun noTriggerMeansNoConnection() {
        val result = PrivilegedShell.run(context, PrivilegedCommand.DisableWirelessDebugging, null)
        assertFalse(result.ok)
        assertEquals(EmbeddedAdbShell.REFUSED_NO_TRIGGER, result.failure)
        assertEquals(listOf("wireless_debugging_off"), refusals().map { it.id })
    }

    @Test
    fun aStaleTriggerIsRefused() {
        val old = BrokerTrigger.forTest(
            BrokerTrigger.Kind.TAP,
            mintedAtMs = SystemClock.elapsedRealtime() - BrokerTrigger.VALID_FOR_MS - 1
        )
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, old)
        assertEquals(EmbeddedAdbShell.REFUSED_STALE_TRIGGER, result.failure)
    }

    @Test
    fun anOpenScreenMayOnlyVerify() {
        val screen = BrokerTrigger.forTest(BrokerTrigger.Kind.SCREEN)
        assertTrue(screen.allows(PrivilegedCommand.Verify))
        val result = PrivilegedShell.run(context, PrivilegedCommand.DisableWirelessDebugging, screen)
        assertEquals(EmbeddedAdbShell.REFUSED_NOT_ALLOWED, result.failure)
    }

    /** The lead's rule for Plektra's screens: an open screen may read what the phone holds, never write. */
    @Test
    fun aScreenTriggerRefusesEveryWritingCommand() {
        val screen = BrokerTrigger.forTest(BrokerTrigger.Kind.SCREEN)
        val writes = listOf(
            PrivilegedCommand.DisableWirelessDebugging,
            PrivilegedCommand.UnpairSelf.forThisDevice("AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89")!!,
        )
        writes.forEach { command ->
            assertFalse(command.id, command.readOnly)
            val result = PrivilegedShell.run(context, command, screen)
            assertFalse(command.id, result.ok)
            assertEquals(command.id, EmbeddedAdbShell.REFUSED_NOT_ALLOWED, result.failure)
        }
        // A read followed by a write is refused as a whole, before anything connects.
        val mixed = PrivilegedShell.runSession(
            context, listOf(PrivilegedCommand.Verify, PrivilegedCommand.DisableWirelessDebugging), screen
        )
        assertTrue(mixed.all { it.failure == EmbeddedAdbShell.REFUSED_NOT_ALLOWED })
        // Every refusal is audited (repeats of one id coalesce into one entry).
        assertEquals((writes.map { it.id } + PrivilegedCommand.Verify.id).toSet(), refusals().map { it.id }.toSet())
    }

    @Test
    fun aScreenTriggerLetsReadOnlyCommandsPastTheGate() {
        val screen = BrokerTrigger.forTest(BrokerTrigger.Kind.SCREEN)
        // Wireless debugging off, so a command the gate lets through stops before discovery.
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        val reads = listOf(PrivilegedCommand.Verify)
        reads.forEach { command ->
            assertTrue(command.id, command.readOnly)
            val result = PrivilegedShell.run(context, command, screen)
            assertEquals(command.id, EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF, result.status)
        }
        assertTrue(refusals().isEmpty())
    }

    @Test
    fun aCommandThatEndsTheSessionMustBeLast() {
        val tap = BrokerTrigger.forTest(BrokerTrigger.Kind.TAP)
        val results = PrivilegedShell.runSession(
            context,
            listOf(PrivilegedCommand.DisableWirelessDebugging, PrivilegedCommand.Verify),
            tap
        )
        assertEquals(2, results.size)
        results.forEach { assertEquals(EmbeddedAdbShell.REFUSED_TERMINAL_NOT_LAST, it.failure) }
    }

    @Test
    fun aTriggerCanOnlyBeMintedFromAResumedActivity() {
        assertNull("the application is not an activity", BrokerTrigger.fromTap(context, "x"))
        val controller = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java)
        val activity: Activity = controller.setup().get()
        assertNotNull(BrokerTrigger.fromTap(activity, "x"))
        controller.pause()
        assertNull("a paused screen cannot start the broker", BrokerTrigger.fromTap(activity, "x"))
    }

    @Test
    fun blockedCommandsAreRecordedWithTheirReason() {
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        val tap = BrokerTrigger.forTest(BrokerTrigger.Kind.TAP)
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, tap)
        assertEquals(EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF, result.status)
        val entry = BrokerAuditLog.entries(context).last()
        assertEquals(BrokerAuditLog.Kind.COMMAND, entry.kind)
        assertEquals("verify", entry.id)
        assertFalse(entry.ok)
    }

    // ---- reset to stock / forget pairing ------------------------------------------------

    @Test
    fun resetWithoutAReachableBrokerStillDeletesTheKeyAndSaysWhatIsLeft() {
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)

        val result = PrivilegedSetup.resetToStock(context, BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))

        assertFalse("nothing could confirm the unpair", result.unpaired)
        assertEquals(EmbeddedAdbShell.REASON_WIRELESS_DEBUGGING_OFF, result.reason)
        assertFalse(source.exists())
        assertFalse(EmbeddedAdbShell.isPaired(context))
        val kinds = BrokerAuditLog.entries(context).map { it.kind to it.id }
        assertTrue(kinds.toString(), (BrokerAuditLog.Kind.COMMAND to "unpair_self") in kinds)
        assertTrue(kinds.toString(), (BrokerAuditLog.Kind.UNPAIR to "self") in kinds)
        assertTrue(kinds.toString(), (BrokerAuditLog.Kind.KEY to "deleted") in kinds)
    }

    @Test
    fun resetWithNoKeyHasNothingToUnpair() {
        source.delete()
        val result = PrivilegedSetup.resetToStock(context, BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))
        assertFalse(result.unpaired)
        assertEquals(PrivilegedSetup.REASON_NO_KEY, result.reason)
        assertTrue(BrokerAuditLog.entries(context).none { it.kind == BrokerAuditLog.Kind.COMMAND })
    }

    @Test
    fun resetWithoutAUserActionNeverConnects() {
        val result = PrivilegedSetup.resetToStock(context, null)
        assertFalse(result.unpaired)
        assertEquals(EmbeddedAdbShell.REFUSED_NO_TRIGGER, result.reason)
        assertFalse("the local key is forgotten anyway", source.exists())
    }

    // ---- the Wireless debugging setting ---------------------------------------------------

    @Test
    fun wirelessDebuggingOffAfterSetupIsOnByDefault() {
        assertTrue(BrokerSettings.turnOffWirelessDebuggingAfterSetup(context))
        BrokerSettings.setTurnOffWirelessDebuggingAfterSetup(context, false)
        assertFalse(BrokerSettings.turnOffWirelessDebuggingAfterSetup(context))
    }

    @Test
    fun finishSetupLeavesWirelessDebuggingAloneWhenTheUserSaidSo() {
        BrokerSettings.setTurnOffWirelessDebuggingAfterSetup(context, false)
        PrivilegedSetup.finishSetup(context, BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))
        assertTrue(EmbeddedAdbShell.isWirelessDebuggingEnabled(context))
        assertTrue(BrokerAuditLog.entries(context).isEmpty())
    }
}
