package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The closed command set and the broker's own commands. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegedCommandTest {

    private val pkg = "rocks.pkb.plektra"
    private val fp = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

    private val app = pkg

    private fun everyCase(): List<PrivilegedCommand> = listOfNotNull(
        PrivilegedCommand.Verify,
        PrivilegedCommand.DisableWirelessDebugging,
        PrivilegedCommand.UnpairSelf.forThisDevice(fp),
)

    @Test
    fun brokerCommandLinesAreConstantsPlusValidatedValues() {
        assertEquals("echo pastiera_verify", PrivilegedCommand.Verify.commandLine(pkg))
        assertEquals("settings put global adb_wifi_enabled 0", PrivilegedCommand.DisableWirelessDebugging.commandLine(pkg))
        assertEquals("service call adb 7 s16 $fp", PrivilegedCommand.UnpairSelf.forThisDevice(fp)!!.commandLine(pkg))
    }

    @Test
    fun unpairIsOnlyOfferedOnSdksWhoseTransactionCodeWasChecked() {
        // IAdbManager.unpairDevice is method 7 from android-11.0.0_r1 to android16-qpr2 and main.
        assertEquals(7, PrivilegedCommand.TXN_UNPAIR_DEVICE)
        (30..36).forEach { assertNotNull("sdk $it", PrivilegedCommand.UnpairSelf.forThisDevice(fp, it)) }
        assertNull(PrivilegedCommand.UnpairSelf.forThisDevice(fp, 29))
        assertNull(PrivilegedCommand.UnpairSelf.forThisDevice(fp, 37))
    }

    @Test
    fun unpairRefusesAnythingButAFingerprint() {
        listOf("", "ab:cd", "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89; reboot", fp.lowercase())
            .forEach { assertNull(it, PrivilegedCommand.UnpairSelf.forThisDevice(it, 36)) }
    }

    @Test
    fun unpairReplyIsReadFromTheParcelsExceptionWord() {
        assertTrue(PrivilegedCommand.UnpairSelf.replyIsSuccess("Result: Parcel(00000000    '....')\n"))
        assertTrue(PrivilegedCommand.UnpairSelf.replyIsSuccess("Result: Parcel(00000000)"))
        assertFalse(
            PrivilegedCommand.UnpairSelf.replyIsSuccess(
                "Result: Parcel(ffffffff 0000004c 00650067 'L...g.e.')"
            )
        )
        assertFalse(PrivilegedCommand.UnpairSelf.replyIsSuccess(""))
        assertFalse(PrivilegedCommand.UnpairSelf.replyIsSuccess(null))
    }

    @Test
    fun onlyUnpairAndWirelessOffEndTheSession() {
        val terminal = everyCase().filter { it.terminal }.map { it.id }.toSet()
        assertEquals(setOf("unpair_self", "wireless_debugging_off"), terminal)
    }

    /** A SCREEN trigger runs these and nothing else; each only reads. */
    @Test
    fun onlyTheReadsAreReadOnly() {
        val readOnly = everyCase().filter { it.readOnly }.map { it.id }.toSet()
        assertEquals(setOf("verify"), readOnly)
        everyCase().filter { it.readOnly }.forEach { command ->
            val line = command.commandLine(pkg)
            listOf("put", "delete", "grant", "revoke", "disable", "enable", "install", "uninstall", "reset")
                .forEach { verb -> assertFalse("${command.id}: $verb", line.contains(" $verb ") || line.endsWith(" $verb")) }
        }
    }

    @Test
    fun everyCaseHasAStableDistinctAuditId() {
        val ids = everyCase().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it, Regex("^[a-z_]+$").matches(it)) }
    }

    /** The broker never touches the 7-day key expiry. */
    @Test
    fun noCommandWritesTheAdbKeyExpirySetting() {
        everyCase().forEach { command ->
            assertFalse(command.id, command.commandLine(pkg).contains("adb_allowed_connection_time"))
        }
    }

    @Test
    fun noCommandLineCarriesShellMetacharacters() {
        everyCase().forEach { command ->
            val line = command.commandLine(app)
            assertFalse(command.id + ": " + line, line.any { it in ";&|`$<>\\\n\r\"'" })
        }
    }
}
