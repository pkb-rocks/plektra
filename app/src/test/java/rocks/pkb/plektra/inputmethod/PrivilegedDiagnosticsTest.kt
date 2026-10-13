package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.provider.Settings
import moe.shizuku.manager.adb.EmbeddedAdbInit
import moe.shizuku.manager.adb.SoftwareAdbKeySource
import org.junit.After
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
class PrivilegedDiagnosticsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val source = SoftwareAdbKeySource()
    private val savedFactory = EmbeddedAdbInit.keySourceFactory

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        EmbeddedAdbInit.keySourceFactory = { source }
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
    }

    @After
    fun tearDown() {
        EmbeddedAdbInit.keySourceFactory = savedFactory
    }

    private val tap get() = BrokerTrigger.forTest(BrokerTrigger.Kind.TAP)

    private fun storeKey() {
        source.generate()
    }

    @Test
    fun brokerStatusIsNullUntilChecked() {
        assertNull(PrivilegedDiagnostics.lastBrokerStatus(context))
    }

    @Test
    fun brokerStatusRoundTripsEveryValue() {
        EmbeddedAdbShell.BrokerStatus.entries.forEach { status ->
            PrivilegedDiagnostics.recordBrokerStatus(context, status)
            assertEquals(status, PrivilegedDiagnostics.lastBrokerStatus(context)?.first)
        }
    }

    @Test
    fun unknownStoredStatusIsIgnoredRatherThanCrashing() {
        EmbeddedAdbInit.prefs(context).edit()
            .putString("privileged_broker_status", "NO_SUCH_STATUS")
            .putLong("privileged_broker_status_at", 1L)
            .commit()
        assertNull(PrivilegedDiagnostics.lastBrokerStatus(context))
    }

    @Test
    fun brokerStatusLivesInTheBackupExcludedPrefsFile() {
        PrivilegedDiagnostics.recordBrokerStatus(context, EmbeddedAdbShell.BrokerStatus.OK)
        assertTrue(EmbeddedAdbInit.prefs(context).contains("privileged_broker_status"))
        assertFalse(
            context.getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE)
                .contains("privileged_broker_status")
        )
    }

    @Test
    fun blockerNamesTheFirstThingMissing() {
        assertEquals(PrivilegedDiagnostics.REASON_NOT_PAIRED, PrivilegedDiagnostics.brokerBlocker(context))

        storeKey()
        assertEquals(
            PrivilegedDiagnostics.REASON_WIRELESS_DEBUGGING_OFF,
            PrivilegedDiagnostics.brokerBlocker(context)
        )

        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        assertNull(PrivilegedDiagnostics.brokerBlocker(context))
    }

    @Test
    fun verifyReportsMissingPrerequisitesWithoutTouchingTheNetwork() {
        assertEquals(EmbeddedAdbShell.BrokerStatus.NOT_PAIRED, EmbeddedAdbShell.verify(context, tap))

        storeKey()
        assertEquals(
            EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF,
            EmbeddedAdbShell.verify(context, tap)
        )
    }

    @Test
    fun forgetPairingDropsTheKeySoTheAppStopsReadingAsPaired() {
        storeKey()
        assertTrue(EmbeddedAdbShell.isPaired(context))

        EmbeddedAdbShell.forgetPairing(context)

        assertFalse(EmbeddedAdbShell.isPaired(context))
        assertNull(EmbeddedAdbShell.lastError)
    }

    @Test
    fun executeFailsGracefullyWhenNotPaired() {
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, tap)
        assertFalse(result.ok)
        assertEquals(EmbeddedAdbShell.BrokerStatus.NOT_PAIRED, result.status)
        assertTrue(EmbeddedAdbShell.lastError?.contains("Not paired") == true)
    }
}
