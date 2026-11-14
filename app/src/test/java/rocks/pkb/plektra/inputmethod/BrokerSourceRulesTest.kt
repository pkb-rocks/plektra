package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level rules for the broker. A new
 * caller makes this fail on purpose: check it is a user action in the app's screens, then add it.
 */
class BrokerSourceRulesTest {

    private val mainRoot: File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private fun sources(): Sequence<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }

    private fun relative(f: File) = f.relativeTo(mainRoot).path.replace(File.separatorChar, '/')

    /** The code of a Kotlin file without its comments, so KDoc links do not count as calls. */
    private fun code(f: File): String =
        f.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")

    /** Nothing writes the ADB key expiry ("Disable adb authorization timeout"). */
    @Test
    fun nothingTouchesTheAdbKeyExpirySetting() {
        val offenders = sources().filter { it.readText().contains("adb_allowed_connection_time", ignoreCase = true) }
            .map { relative(it) }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /** Every place that can make the broker connect. Each is a user action, or the broker itself. */
    private val brokerCallers = mapOf(
        // The door, the closed command set and the setup and reset flows.
        "java/rocks/pkb/plektra/inputmethod/EmbeddedAdbShell.kt" to "the door itself",
        "java/rocks/pkb/plektra/inputmethod/PrivilegedCommand.kt" to "PrivilegedShell, the typed wrapper",
        "java/rocks/pkb/plektra/inputmethod/PrivilegedSetup.kt" to "setup and reset, each given a trigger",
        // Callers with a trigger.
        "java/moe/shizuku/manager/adb/AdbPairingService.kt" to "setup after the user typed a pairing code",
        "java/rocks/pkb/plektra/ui/BrokerStatus.kt" to "verify while the broker screen is resumed",
        "java/rocks/pkb/plektra/DeviceSetupCard.kt" to "forget pairing, on a tap",
        // Plektra features. Screens mint the trigger (a tap; a SCREEN trigger for reads on open);
        // the feature objects only pass the caller's trigger through.
        "java/rocks/pkb/plektra/SmartBacklightSection.kt" to "backlight and tile switches and Apply button (tap), on-phone read (screen)",
        "java/rocks/pkb/plektra/NotificationRingScreen.kt" to "ring grant button and switches, on a tap",
        "java/rocks/pkb/plektra/SystemTweaksScreen.kt" to "tweak changes (tap), read on open (screen)",
        "java/rocks/pkb/plektra/DisplayDensityScreen.kt" to "density apply/keep/revert/reset (tap), read on open (screen)",
        "java/rocks/pkb/plektra/BloatRemoverScreen.kt" to "disable/uninstall/restore (tap), census on open (screen)",
        "java/rocks/pkb/plektra/ToolboxSection.kt" to "Fn -> Ctrl and reset to stock, on a tap",
        "java/rocks/pkb/plektra/inputmethod/KeyboardBacklightManager.kt" to "takes the caller's trigger",
        "java/rocks/pkb/plektra/ring/NotificationRingSetup.kt" to "takes the caller's trigger",
        "java/rocks/pkb/plektra/toolbox/SystemTweaks.kt" to "takes the caller's trigger",
        "java/rocks/pkb/plektra/toolbox/DisplayDensity.kt" to "takes the caller's trigger",
        "java/rocks/pkb/plektra/toolbox/RevertibleChange.kt" to
            "takes the caller's trigger; the timed revert reuses the applying tap's (still fresh)",
        "java/rocks/pkb/plektra/toolbox/FnCtrlRemap.kt" to "takes the caller's trigger",
        "java/rocks/pkb/plektra/toolbox/PackageRemover.kt" to "takes the caller's trigger",
    )

    private val callPattern = Regex(
        "PrivilegedShell\\.(run|runSession)\\b|EmbeddedAdbShell\\.(execute|verify)\\b|" +
            "PrivilegedSetup\\.(applyAll|resetToStock|setWriteSecureSettingsNeeded|finishSetup|queueFinishSetup)\\b|" +
            "BrokerStatusMonitor\\.refresh\\b"
    )

    @Test
    fun theBrokerIsReachedOnlyFromKnownUserActions() {
        val callers = sources().filter { it.extension == "kt" && callPattern.containsMatchIn(code(it)) }
            .map { relative(it) }.toSet()
        val unknown = callers - brokerCallers.keys
        assertTrue("new broker callers; check each is a user action: $unknown", unknown.isEmpty())
    }

    /** The IME's input path (the service and everything it routes keys through) never mints a trigger. */
    @Test
    fun onlyTheAppsScreensMintTriggers() {
        val minting = Regex("BrokerTrigger\\.(fromTap|forScreen|fromPairingCode)\\b")
        val minters = sources().filter { it.extension == "kt" && minting.containsMatchIn(code(it)) }
            .map { relative(it) }.toSet()
        assertEquals(
            setOf(
                "java/moe/shizuku/manager/adb/AdbPairingService.kt",
                "java/rocks/pkb/plektra/ui/BrokerStatus.kt",
                "java/rocks/pkb/plektra/DeviceSetupCard.kt",
                // Plektra's screens: taps, and SCREEN triggers (read-only) through BrokerStatus.kt.
                "java/rocks/pkb/plektra/SmartBacklightSection.kt",
                "java/rocks/pkb/plektra/NotificationRingScreen.kt",
                "java/rocks/pkb/plektra/SystemTweaksScreen.kt",
                "java/rocks/pkb/plektra/DisplayDensityScreen.kt",
                "java/rocks/pkb/plektra/BloatRemoverScreen.kt",
                "java/rocks/pkb/plektra/ToolboxSection.kt",
            ),
            minters
        )
    }

    /** No string reaches the shell except through the closed set: the string runner is private. */
    @Test
    fun theStringLevelShellIsNotReachableFromOutside() {
        val shell = File(mainRoot, "java/rocks/pkb/plektra/inputmethod/EmbeddedAdbShell.kt").readText()
        assertTrue(Regex("private fun runSession\\(").containsMatchIn(shell))
        assertTrue(Regex("internal fun execute\\(\\s*context: Context,\\s*commands: List<PrivilegedCommand>").containsMatchIn(shell))
        assertTrue("no string-taking runShell is left", !Regex("fun runShell\\w*\\(").containsMatchIn(shell))
    }
}
