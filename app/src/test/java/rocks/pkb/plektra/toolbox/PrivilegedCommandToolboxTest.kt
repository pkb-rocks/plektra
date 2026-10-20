package rocks.pkb.plektra.toolbox

import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The toolbox cases of [PrivilegedCommand]. The command line is the only thing that reaches the
 * broker's shell, so these pin its exact shape and prove that a value which fails validation can
 * never become part of one.
 */
class PrivilegedCommandToolboxTest {

    private val app = "rocks.pkb.plektra"

    private fun line(command: PrivilegedCommand) = command.commandLine(app)

    private fun assertRejected(command: PrivilegedCommand) {
        try {
            command.commandLine(app)
            fail("expected the command to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `package commands have the exact shape`() {
        assertEquals("pm disable-user --user 0 com.agui.factorytest", line(PrivilegedCommand.DisablePackage("com.agui.factorytest")))
        assertEquals("pm uninstall --user 0 com.agui.factorytest", line(PrivilegedCommand.UninstallPackage("com.agui.factorytest")))
        assertEquals(
            "cmd package install-existing --user 0 com.agui.factorytest",
            line(PrivilegedCommand.InstallExistingPackage("com.agui.factorytest"))
        )
        assertEquals("pm enable --user 0 com.agui.factorytest", line(PrivilegedCommand.EnablePackage("com.agui.factorytest")))
    }

    @Test
    fun `removal refuses anything the catalog does not allow`() {
        listOf(
            "com.whatsapp",
            "com.agui.settings",
            "com.iqqijni.bbkeyboard",
            "com.android.systemui",
            app
        ).forEach {
            assertRejected(PrivilegedCommand.DisablePackage(it))
            assertRejected(PrivilegedCommand.UninstallPackage(it))
        }
    }

    @Test
    fun `no package command accepts shell syntax`() {
        listOf(
            "com.agui.factorytest; reboot",
            "com.agui.factorytest && id",
            "com.agui.factorytest `id`",
            "com.agui.factorytest\$(id)",
            "com.agui.factorytest | sh",
            "com.agui.factorytest\nreboot",
            "com.agui.factorytest com.agui.update",
            "-rf",
            "",
            "nodots",
            ".leading.dot",
            "trailing.dot.",
            "com..double"
        ).forEach { bad ->
            assertFalse("'$bad' passed validation", PrivilegedCommand.isValidPackageName(bad))
            assertRejected(PrivilegedCommand.DisablePackage(bad))
            assertRejected(PrivilegedCommand.UninstallPackage(bad))
            assertRejected(PrivilegedCommand.InstallExistingPackage(bad))
            assertRejected(PrivilegedCommand.EnablePackage(bad))
        }
    }

    @Test
    fun `restore accepts a retired catalog name but only the name shape`() {
        // A journaled package must come back even if the catalog dropped it since.
        assertEquals(
            "pm enable --user 0 com.agui.retired_thing",
            line(PrivilegedCommand.EnablePackage("com.agui.retired_thing"))
        )
    }

    @Test
    fun `density is an Int in a fixed range`() {
        assertEquals("wm density 420", line(PrivilegedCommand.SetDensity(420)))
        assertEquals("wm density reset", line(PrivilegedCommand.ResetDensity))
        assertEquals("wm density", line(PrivilegedCommand.ReadDensity))
        listOf(-1, 0, 99, 1001, Int.MAX_VALUE, Int.MIN_VALUE).forEach { assertRejected(PrivilegedCommand.SetDensity(it)) }
    }

    @Test
    fun `fn programmable key rows are small non-negative Ints`() {
        assertEquals(
            "settings put system fn_programmable_key_enable 1; settings put system fn_programmable_key_function 1",
            line(PrivilegedCommand.SetFnProgrammableKey(1, 1))
        )
        assertRejected(PrivilegedCommand.SetFnProgrammableKey(-1, 0))
        assertRejected(PrivilegedCommand.SetFnProgrammableKey(0, 1001))
        assertRejected(PrivilegedCommand.SetFnProgrammableKey(Int.MIN_VALUE, Int.MIN_VALUE))
    }

    @Test
    fun `tweak commands are built from constants`() {
        assertEquals(
            "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5",
            line(PrivilegedCommand.SetAnimationSpeed(SystemTweaks.AnimationSpeed.FAST))
        )
        assertEquals(
            "settings put secure one_handed_mode_enabled 1",
            line(PrivilegedCommand.SetTweak(SystemTweaks.Toggle.ONE_HANDED_MODE, true))
        )
        // Off deletes the key: these ship unset.
        assertEquals(
            "settings delete secure notification_history_enabled",
            line(PrivilegedCommand.SetTweak(SystemTweaks.Toggle.NOTIFICATION_HISTORY, false))
        )
        assertTrue(line(PrivilegedCommand.ResetSystemTweaks).contains("settings delete secure one_handed_mode_enabled"))
        assertEquals(
            "settings get global window_animation_scale; settings get secure notification_history_enabled; " +
                "settings get secure one_handed_mode_enabled",
            line(PrivilegedCommand.ReadSystemTweaks)
        )
    }

    /**
     * The tripwire for security rule 1: toolbox code reaches the broker only through
     * [PrivilegedShell]. A direct call to `runShell*` from a toolbox file would bypass the typed
     * layer, so no file below may mention either function.
     */
    @Test
    fun `toolbox sources never call the raw shell`() {
        val root = listOf(File("src/main/java/rocks/pkb/plektra"), File("app/src/main/java/rocks/pkb/plektra"))
            .first { it.exists() }
        val files = (root.resolve("toolbox").listFiles { f -> f.extension == "kt" }.orEmpty().toList()) +
            listOf("ToolboxSection", "BloatRemoverScreen", "DisplayDensityScreen", "SystemTweaksScreen", "KeyMappingScreen", "SystemChangeManager")
                .map { root.resolve("$it.kt") }
        assertTrue(files.size > 6)
        files.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} calls the raw shell", Regex("runShell(Capturing)?\\(").containsMatchIn(text))
        }
    }
}
