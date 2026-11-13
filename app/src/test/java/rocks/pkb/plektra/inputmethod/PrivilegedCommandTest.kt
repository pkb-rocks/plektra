package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    // The tests below name the package `app`.
    private val app = pkg

    private fun everyCase(): List<PrivilegedCommand> = listOfNotNull(
        PrivilegedCommand.Verify,
        PrivilegedCommand.GrantWriteSecureSettings,
        PrivilegedCommand.RevokeWriteSecureSettings,
        PrivilegedCommand.DisableWirelessDebugging,
        PrivilegedCommand.UnpairSelf.forThisDevice(fp),
        PrivilegedCommand.ReadBacklightTimeout,
        PrivilegedCommand.ListPackageStates,
        PrivilegedCommand.DisablePackage("com.agui.factorytest"),
        PrivilegedCommand.UninstallPackage("com.agui.factorytest"),
        PrivilegedCommand.InstallExistingPackage("com.agui.factorytest"),
        PrivilegedCommand.EnablePackage("com.agui.factorytest"),
        PrivilegedCommand.ReadDensity,
        PrivilegedCommand.ResetDensity,
        PrivilegedCommand.SetDensity(320),
        PrivilegedCommand.ReadSystemTweaks,
        PrivilegedCommand.SetAnimationSpeed(rocks.pkb.plektra.toolbox.SystemTweaks.AnimationSpeed.entries.first()),
        PrivilegedCommand.SetTweak(rocks.pkb.plektra.toolbox.SystemTweaks.Toggle.entries.first(), true),
        PrivilegedCommand.ResetSystemTweaks,
        PrivilegedCommand.SetFnProgrammableKey(1, 1),
    ) + PrivilegedCommand.BacklightTimeout.entries.map { PrivilegedCommand.WriteBacklightTimeout(it) }

    @Test
    fun brokerCommandLinesAreConstantsPlusValidatedValues() {
        assertEquals("echo pastiera_verify", PrivilegedCommand.Verify.commandLine(pkg))
        assertEquals(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            PrivilegedCommand.GrantWriteSecureSettings.commandLine(pkg)
        )
        assertEquals(
            "pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS",
            PrivilegedCommand.RevokeWriteSecureSettings.commandLine(pkg)
        )
        assertEquals("settings put global adb_wifi_enabled 0", PrivilegedCommand.DisableWirelessDebugging.commandLine(pkg))
        assertEquals("service call adb 7 s16 $fp", PrivilegedCommand.UnpairSelf.forThisDevice(fp)!!.commandLine(pkg))
    }

    @Test
    fun anInvalidPackageNameNeverReachesTheShell() {
        listOf("x; reboot", "rocks.pkb.plektra && id", "").forEach { bad ->
            try {
                PrivilegedCommand.RevokeWriteSecureSettings.commandLine(bad)
                fail("accepted '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
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
        assertEquals(
            setOf("verify", "read_backlight_timeout", "list_package_states", "read_density", "read_system_tweaks"),
            readOnly
        )
        everyCase().filter { it.readOnly }.forEach { command ->
            val line = command.commandLine(pkg)
            listOf("put", "delete", "grant", "revoke", "disable", "enable", "install", "uninstall", "reset", " s16 \"-1\"")
                .forEach { verb -> assertFalse("${command.id}: $verb", line.contains(" $verb ") || line.endsWith(" $verb")) }
            assertFalse(command.id, line.contains("service call agui_functional_service 2"))
            assertFalse(command.id, Regex("wm density \\d").containsMatchIn(line))
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
    fun writeSecureSettingsIsHeldOnlyWhileNeeded() {
        assertEquals(PrivilegedCommand.GrantWriteSecureSettings, WriteSecureSettingsHolders.action(needed = true, held = false))
        assertEquals(PrivilegedCommand.RevokeWriteSecureSettings, WriteSecureSettingsHolders.action(needed = false, held = true))
        assertNull(WriteSecureSettingsHolders.action(needed = true, held = true))
        assertNull(WriteSecureSettingsHolders.action(needed = false, held = false))
    }

    // Backlight: exact command lines and package-name validation.

    @Test
    fun grantIsExactlyPmGrantOfWriteSecureSettingsToTheAppItself() {
        assertEquals(
            "pm grant rocks.pkb.plektra android.permission.WRITE_SECURE_SETTINGS",
            PrivilegedCommand.GrantWriteSecureSettings.commandLine(app)
        )
    }

    @Test
    fun grantRefusesAnythingThatIsNotAPlainPackageName() {
        listOf(
            "",
            "pastiera",
            "a.b; reboot",
            "a.b && id",
            "a.b\nid",
            "a.b `id`",
            "a.b\$(id)",
            "a.b|id",
            "a. b",
            "1a.b",
            "a..b",
            ".a.b",
            "a.b."
        ).forEach { name ->
            try {
                PrivilegedCommand.GrantWriteSecureSettings.commandLine(name)
                fail("accepted '$name'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun packageNameValidationAcceptsRealPackageNames() {
        listOf("rocks.pkb.plektra", "rocks.pkb.plektra.nightly", "com.a_b.c1").forEach {
            assertTrue(it, PrivilegedCommand.isValidPackageName(it))
        }
    }

    @Test
    fun backlightTimeoutCommandsAreBuiltFromConstantsOnly() {
        assertEquals(
            "service call agui_functional_service 1 s16 \"keyboard_brightness_timeout\"",
            PrivilegedCommand.ReadBacklightTimeout.commandLine(app)
        )
        assertEquals(
            "service call agui_functional_service 2 s16 \"keyboard_brightness_timeout\" s16 \"-1\"",
            PrivilegedCommand.WriteBacklightTimeout(PrivilegedCommand.BacklightTimeout.ALWAYS_ON)
                .commandLine(app)
        )
        assertEquals(
            "service call agui_functional_service 2 s16 \"keyboard_brightness_timeout\" s16 \"30000\"",
            PrivilegedCommand.WriteBacklightTimeout(PrivilegedCommand.BacklightTimeout.STOCK)
                .commandLine(app)
        )
    }

    @Test
    fun theTimeoutValuesAreTheOnlyTwoTheAppEverWrites() {
        assertEquals(
            listOf("-1", "30000"),
            PrivilegedCommand.BacklightTimeout.entries.map { it.wireValue }
        )
    }

    @Test
    fun noCommandLineCarriesShellMetacharacters() {
        val lines = listOf(
            PrivilegedCommand.GrantWriteSecureSettings.commandLine(app),
            PrivilegedCommand.ReadBacklightTimeout.commandLine(app)
        ) + PrivilegedCommand.BacklightTimeout.entries.map {
            PrivilegedCommand.WriteBacklightTimeout(it).commandLine(app)
        }
        lines.forEach { line ->
            // The double quotes around the vendor key and value are the only quoting in use.
            assertFalse(line, line.any { it in ";&|`$<>\\\n\r" })
        }
    }
}
