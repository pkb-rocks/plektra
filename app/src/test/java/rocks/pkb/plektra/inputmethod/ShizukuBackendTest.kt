package rocks.pkb.plektra.inputmethod

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import moe.shizuku.manager.adb.CLIENT_DEADLINE_MS
import moe.shizuku.manager.adb.EmbeddedAdbInit
import moe.shizuku.manager.adb.MAX_SHELL_OUTPUT_BYTES
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

/**
 * Shizuku as a second backend behind the broker's one door. The door's checks
 * come first whatever the backend; the backend follows the "Privileged access via" setting and
 * Shizuku's state; the audit names the backend; finishSetup leaves Wireless debugging alone for
 * Shizuku; the reset works through either. A fake stands in for Shizuku (no server under
 * Robolectric). Nothing here reaches a network: every broker case stops before discovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuBackendTest {

    /**
     * Records every argv it is asked to run (as one space-joined string, [lines]; the raw arrays
     * are in [argvs]) and answers from [answer]. A real service reports its uid with every answer;
     * so does this one, as [uid], unless the answer says otherwise.
     */
    private class FakeShizuku(var state: ShizukuState) : ShizukuShell {
        val lines = mutableListOf<String>()
        val argvs = mutableListOf<List<String>>()
        var uid: Int = 2000
        var answer: (String) -> ShellRunResult = { line ->
            val out = if (line == "echo ${EmbeddedAdbShell.VERIFY_MARKER}") "${EmbeddedAdbShell.VERIFY_MARKER}\n" else ""
            ShellRunResult(ok = true, output = out, exitCode = 0, failure = null)
        }

        override fun state(context: Context): ShizukuState = state

        override fun runArgv(context: Context, argv: List<String>): ShellRunResult {
            val line = argv.joinToString(" ")
            lines += line
            argvs += argv
            val result = answer(line)
            return if (result.uid == null && !result.serviceUnavailable) result.copy(uid = uid) else result
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val source = SoftwareAdbKeySource()
    private val savedFactory = EmbeddedAdbInit.keySourceFactory
    private val savedShizuku = PrivilegedBackends.shizuku
    private val shizuku = FakeShizuku(ShizukuState.READY)
    private val tap get() = BrokerTrigger.forTest(BrokerTrigger.Kind.TAP)

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        EmbeddedAdbInit.keySourceFactory = { source }
        PrivilegedBackends.shizuku = shizuku
        BrokerAuditLog.clear(context)
        SettingsManager.setPrivilegedAccessBackend(context, SettingsManager.PRIVILEGED_ACCESS_AUTO)
        source.generate()
        // Wireless debugging off: a command that reaches the broker stops before discovery.
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
    }

    @After
    fun tearDown() {
        EmbeddedAdbInit.keySourceFactory = savedFactory
        PrivilegedBackends.shizuku = savedShizuku
        SettingsManager.setPrivilegedAccessBackend(context, SettingsManager.PRIVILEGED_ACCESS_AUTO)
    }

    private fun setMode(mode: PrivilegedAccessMode) = SettingsManager.setPrivilegedAccessBackend(context, mode.prefValue)

    private fun commandEntries() = BrokerAuditLog.entries(context).filter { it.kind == BrokerAuditLog.Kind.COMMAND }

    // ---- the choice ---------------------------------------------------------------------------

    @Test
    fun everyModeAndShizukuStateChoosesTheDocumentedBackend() {
        val shizuku = PrivilegedBackend.SHIZUKU
        val broker = PrivilegedBackend.BROKER
        val expected = mapOf(
            (PrivilegedAccessMode.AUTOMATIC to ShizukuState.READY) to shizuku,
            (PrivilegedAccessMode.AUTOMATIC to ShizukuState.NOT_ALLOWED) to broker,
            (PrivilegedAccessMode.AUTOMATIC to ShizukuState.NOT_RUNNING) to broker,
            (PrivilegedAccessMode.AUTOMATIC to ShizukuState.NOT_INSTALLED) to broker,
            (PrivilegedAccessMode.AUTOMATIC to ShizukuState.TOO_OLD) to broker,
            (PrivilegedAccessMode.AUTOMATIC to ShizukuState.DENIED_PERMANENTLY) to broker,
            (PrivilegedAccessMode.SHIZUKU_ONLY to ShizukuState.READY) to shizuku,
            (PrivilegedAccessMode.SHIZUKU_ONLY to ShizukuState.NOT_ALLOWED) to shizuku,
            (PrivilegedAccessMode.SHIZUKU_ONLY to ShizukuState.NOT_RUNNING) to shizuku,
            (PrivilegedAccessMode.SHIZUKU_ONLY to ShizukuState.NOT_INSTALLED) to shizuku,
            (PrivilegedAccessMode.SHIZUKU_ONLY to ShizukuState.TOO_OLD) to shizuku,
            (PrivilegedAccessMode.SHIZUKU_ONLY to ShizukuState.DENIED_PERMANENTLY) to shizuku,
            (PrivilegedAccessMode.BROKER_ONLY to ShizukuState.READY) to broker,
            (PrivilegedAccessMode.BROKER_ONLY to ShizukuState.NOT_ALLOWED) to broker,
            (PrivilegedAccessMode.BROKER_ONLY to ShizukuState.NOT_RUNNING) to broker,
            (PrivilegedAccessMode.BROKER_ONLY to ShizukuState.NOT_INSTALLED) to broker,
            (PrivilegedAccessMode.BROKER_ONLY to ShizukuState.TOO_OLD) to broker,
            (PrivilegedAccessMode.BROKER_ONLY to ShizukuState.DENIED_PERMANENTLY) to broker,
        )
        assertEquals(PrivilegedAccessMode.entries.size * ShizukuState.entries.size, expected.size)
        expected.forEach { (key, backend) ->
            assertEquals(key.toString(), backend, PrivilegedBackends.choose(key.first, key.second))
        }
        // And through the setting and the (fake) live state.
        expected.forEach { (key, backend) ->
            setMode(key.first)
            this.shizuku.state = key.second
            assertEquals(key.toString(), backend, PrivilegedBackends.current(context))
        }
    }

    @Test
    fun theSettingDefaultsToAutomaticAndIgnoresUnknownValues() {
        SettingsManager.setPrivilegedAccessBackend(context, "root")
        assertEquals(PrivilegedAccessMode.AUTOMATIC, PrivilegedBackends.mode(context))
        assertEquals(PrivilegedAccessMode.AUTOMATIC, PrivilegedAccessMode.fromPref(null))
        setMode(PrivilegedAccessMode.BROKER_ONLY)
        assertEquals(SettingsManager.PRIVILEGED_ACCESS_BROKER, SettingsManager.getPrivilegedAccessBackend(context))
    }

    @Test
    fun hasAccessAndTheBlockerFollowTheChosenBackend() {
        assertTrue(PrivilegedBackends.hasAccess(context))
        assertNull(PrivilegedDiagnostics.brokerBlocker(context))

        source.delete()
        assertTrue("Shizuku needs no broker key", PrivilegedBackends.hasAccess(context))

        shizuku.state = ShizukuState.NOT_ALLOWED
        assertFalse("Automatic falls back to the broker, which has no key", PrivilegedBackends.hasAccess(context))
        assertEquals(PrivilegedDiagnostics.REASON_NOT_PAIRED, PrivilegedDiagnostics.brokerBlocker(context))

        setMode(PrivilegedAccessMode.SHIZUKU_ONLY)
        assertFalse(PrivilegedBackends.hasAccess(context))
        assertEquals(PrivilegedDiagnostics.REASON_SHIZUKU_UNAVAILABLE, PrivilegedDiagnostics.brokerBlocker(context))
    }

    // ---- the gate comes first ------------------------------------------------------------------

    @Test
    fun theGateRefusesBeforeShizukuRunsAnything() {
        setMode(PrivilegedAccessMode.SHIZUKU_ONLY)
        val stale = BrokerTrigger.forTest(
            BrokerTrigger.Kind.TAP,
            mintedAtMs = SystemClock.elapsedRealtime() - BrokerTrigger.VALID_FOR_MS - 1
        )
        val screen = BrokerTrigger.forTest(BrokerTrigger.Kind.SCREEN)
        val refusals = listOf(
            PrivilegedShell.run(context, PrivilegedCommand.GrantWriteSecureSettings, null).failure,
            PrivilegedShell.run(context, PrivilegedCommand.Verify, stale).failure,
            PrivilegedShell.run(context, PrivilegedCommand.SetDensity(320), screen).failure,
            PrivilegedShell.runSession(
                context, listOf(PrivilegedCommand.DisableWirelessDebugging, PrivilegedCommand.Verify), tap
            ).first().failure,
            PrivilegedShell.run(context, PrivilegedCommand.DisablePackage("com.example.notincatalog"), tap).failure,
        )
        assertEquals(
            listOf(
                EmbeddedAdbShell.REFUSED_NO_TRIGGER,
                EmbeddedAdbShell.REFUSED_STALE_TRIGGER,
                EmbeddedAdbShell.REFUSED_NOT_ALLOWED,
                EmbeddedAdbShell.REFUSED_TERMINAL_NOT_LAST,
            ),
            refusals.take(4)
        )
        assertTrue(refusals[4], refusals[4]!!.startsWith("invalid disable_package"))
        assertEquals("Shizuku ran nothing", emptyList<String>(), shizuku.lines)
        assertTrue(commandEntries().isEmpty())
        assertTrue(BrokerAuditLog.entries(context).all { it.kind == BrokerAuditLog.Kind.REFUSED })
    }

    @Test
    fun aRefusedCommandHasNoBackend() {
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, null)
        assertNull(result.backend)
    }

    // ---- the Shizuku path ----------------------------------------------------------------------

    @Test
    fun automaticWithShizukuReadyRunsTheLinesThroughShizukuAndAuditsTheBackend() {
        val results = PrivilegedShell.runSession(
            context, listOf(PrivilegedCommand.Verify, PrivilegedCommand.GrantWriteSecureSettings), tap
        )
        assertEquals(
            listOf(
                "echo ${EmbeddedAdbShell.VERIFY_MARKER}",
                "pm grant ${context.packageName} ${PrivilegedCommand.WRITE_SECURE_SETTINGS}",
            ),
            shizuku.lines
        )
        assertTrue(results.all { it.ok && it.backend == PrivilegedBackend.SHIZUKU })
        assertEquals(listOf("verify", "grant_write_secure_settings"), commandEntries().map { it.id })
        assertTrue(commandEntries().all { it.ok && it.detail == "via shizuku (uid 2000)" })
    }

    @Test
    fun aScreenTriggerStillRunsReadsThroughShizuku() {
        val screen = BrokerTrigger.forTest(BrokerTrigger.Kind.SCREEN)
        val result = PrivilegedShell.run(context, PrivilegedCommand.ReadDensity, screen)
        assertTrue(result.ok)
        assertEquals(listOf("wm density"), shizuku.lines)
    }

    @Test
    fun aFailedShizukuCommandStopsTheRestAndSaysWhy() {
        shizuku.answer = { ShellRunResult(false, "partial", null, "shell output exceeded $MAX_SHELL_OUTPUT_BYTES bytes") }
        val results = PrivilegedShell.runSession(
            context, listOf(PrivilegedCommand.Verify, PrivilegedCommand.ReadDensity), tap
        )
        assertEquals(1, shizuku.lines.size)
        assertFalse(results[0].ok)
        assertEquals("partial", results[0].output)
        assertEquals(EmbeddedAdbShell.REASON_EARLIER_FAILED, results[1].failure)
        val details = commandEntries().map { it.detail }
        assertEquals(
            listOf(
                "via shizuku (uid 2000): shell output exceeded $MAX_SHELL_OUTPUT_BYTES bytes",
                "via shizuku: ${EmbeddedAdbShell.REASON_EARLIER_FAILED}",
            ),
            details
        )
    }

    @Test
    fun aNonZeroExitCodeIsNotedButDoesNotFailTheCommand() {
        shizuku.answer = { ShellRunResult(true, "", 1, null) }
        val result = PrivilegedShell.run(context, PrivilegedCommand.RevokeWriteSecureSettings, tap)
        assertTrue(result.ok)
        assertEquals("via shizuku (uid 2000), exit 1", commandEntries().single().detail)
    }

    /** Root is allowed (DECIDED 8, djbclark): the command runs and the audit records uid 0. */
    @Test
    fun aServiceRunningAsRootIsAllowedAndItsUidIsAudited() {
        shizuku.uid = 0
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, tap)
        assertTrue(result.ok)
        assertEquals("via shizuku (uid 0)", commandEntries().single().detail)
    }

    /** Every Shizuku run is a program and its arguments: no `sh -c`, no shell syntax. */
    @Test
    fun shizukuGetsArgvArraysAndNeverAShell() {
        PrivilegedShell.runSession(
            context,
            listOf(
                PrivilegedCommand.ListPackageStates,
                PrivilegedCommand.RingGrants(PrivilegedCommand.RingGrant.entries),
                PrivilegedCommand.WriteBacklightTimeout(PrivilegedCommand.BacklightTimeout.ALWAYS_ON),
            ),
            tap
        )
        assertTrue(shizuku.argvs.isNotEmpty())
        shizuku.argvs.forEach { argv ->
            assertFalse(argv.toString(), argv[0] == "sh" || argv[0].endsWith("/sh"))
            assertFalse(argv.toString(), "-c" in argv)
            assertFalse(argv.toString(), argv.any { ';' in it || '"' in it })
        }
        // `a; b; c` became one run per array, in order.
        assertEquals(
            listOf("echo __E__", "pm list packages -e", "echo __D__", "pm list packages -d", "echo __U__", "pm list packages -u"),
            shizuku.lines.take(6)
        )
        assertEquals(
            listOf("service", "call", "agui_functional_service", "2", "s16", "keyboard_brightness_timeout", "s16", "-1"),
            shizuku.argvs.last()
        )
    }

    @Test
    fun aCommandOfSeveralArraysKeepsGoingAfterANonZeroExitAndJoinsTheOutput() {
        shizuku.answer = { line -> ShellRunResult(true, "[$line]", if (line == "echo __D__") 1 else 0, null) }
        val result = PrivilegedShell.run(context, PrivilegedCommand.ListPackageStates, tap)
        assertTrue(result.ok)
        assertEquals(6, shizuku.lines.size)
        assertTrue(result.output!!.startsWith("[echo __E__][pm list packages -e][echo __D__]"))
        assertEquals("via shizuku (uid 2000)", commandEntries().single().detail)
    }

    // ---- the binder dies mid-run ---------------------------------------------------------------

    /** The server restarts while a command runs: the call throws DeadObjectException. */
    @Test
    fun aBinderThatDiesMidRunIsAFailureTheUserCanRetryNeverSuccess() {
        val dead = android.os.DeadObjectException("binder died")
        val original = shizuku.answer
        shizuku.answer = { throw dead }
        val failed = PrivilegedShell.run(context, PrivilegedCommand.GrantWriteSecureSettings, tap)
        assertFalse(failed.ok)
        assertEquals(EmbeddedAdbShell.BrokerStatus.SHIZUKU_UNAVAILABLE, failed.status)
        assertTrue(failed.failure!!.contains("DeadObjectException"))
        val entry = commandEntries().single()
        assertFalse(entry.ok)
        assertTrue(entry.detail!!.startsWith("via shizuku: Shizuku call failed"))

        // Retry: the next command binds a fresh service and works.
        shizuku.answer = original
        val retried = PrivilegedShell.run(context, PrivilegedCommand.Verify, tap)
        assertTrue(retried.ok)
    }

    /** The service goes away between two arrays of one command: no success, no later array, no later command. */
    @Test
    fun aBinderThatDiesBetweenTwoArraysFailsTheWholeCommandAndTheRest() {
        shizuku.answer = { line ->
            if (line == "echo __D__") ShellRunResult.unavailable("service stopped") else ShellRunResult(true, "ok\n", 0, null)
        }
        val results = PrivilegedShell.runSession(
            context, listOf(PrivilegedCommand.ListPackageStates, PrivilegedCommand.Verify), tap
        )
        assertFalse(results[0].ok)
        assertEquals("service stopped", results[0].failure)
        assertEquals(EmbeddedAdbShell.BrokerStatus.SHIZUKU_UNAVAILABLE, results[0].status)
        assertEquals(EmbeddedAdbShell.REASON_EARLIER_FAILED, results[1].failure)
        assertEquals(listOf("echo __E__", "pm list packages -e", "echo __D__"), shizuku.lines)
    }

    @Test
    fun shizukuOnlyWhileShizukuIsDownFailsWithoutTouchingTheBroker() {
        setMode(PrivilegedAccessMode.SHIZUKU_ONLY)
        shizuku.state = ShizukuState.NOT_RUNNING
        // The broker would answer WIRELESS_DEBUGGING_OFF; Shizuku only must not fall back to it.
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, tap)
        assertEquals(EmbeddedAdbShell.BrokerStatus.SHIZUKU_UNAVAILABLE, result.status)
        assertEquals(PrivilegedBackends.REASON_SHIZUKU_NOT_RUNNING, result.failure)
        assertEquals(PrivilegedBackend.SHIZUKU, result.backend)
        assertTrue(shizuku.lines.isEmpty())
        assertEquals("via shizuku: ${PrivilegedBackends.REASON_SHIZUKU_NOT_RUNNING}", commandEntries().single().detail)
    }

    @Test
    fun aServiceThatCannotStartReadsAsShizukuUnavailable() {
        shizuku.answer = { ShellRunResult.unavailable("Shizuku did not start Plektra's shell service.") }
        val verdict = EmbeddedAdbShell.verifyWithBackend(context, tap)
        assertEquals(EmbeddedAdbShell.BrokerStatus.SHIZUKU_UNAVAILABLE, verdict.status)
    }

    // ---- the broker path is audited too --------------------------------------------------------

    @Test
    fun brokerOnlyIgnoresAReadyShizukuAndTheAuditSaysBroker() {
        setMode(PrivilegedAccessMode.BROKER_ONLY)
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, tap)
        assertEquals(EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF, result.status)
        assertEquals(PrivilegedBackend.BROKER, result.backend)
        assertTrue(shizuku.lines.isEmpty())
        assertEquals(
            "via broker: ${EmbeddedAdbShell.REASON_WIRELESS_DEBUGGING_OFF}",
            commandEntries().single().detail
        )
    }

    @Test
    fun automaticFallsBackToTheBrokerWhenPlektraIsNotAllowed() {
        shizuku.state = ShizukuState.NOT_ALLOWED
        val result = PrivilegedShell.run(context, PrivilegedCommand.Verify, tap)
        assertEquals(PrivilegedBackend.BROKER, result.backend)
        assertTrue(shizuku.lines.isEmpty())
    }

    // ---- verify --------------------------------------------------------------------------------

    @Test
    fun verifyIsOkThroughShizukuWithoutAnyBrokerPairing() {
        source.delete()
        val verdict = EmbeddedAdbShell.verifyWithBackend(context, BrokerTrigger.forTest(BrokerTrigger.Kind.SCREEN))
        assertEquals(EmbeddedAdbShell.BrokerStatus.OK, verdict.status)
        assertEquals(PrivilegedBackend.SHIZUKU, verdict.backend)
    }

    @Test
    fun verifyRejectsAShizukuShellThatDoesNotEchoTheMarker() {
        shizuku.answer = { ShellRunResult(true, "something else\n", 0, null) }
        assertEquals(EmbeddedAdbShell.BrokerStatus.REJECTED, EmbeddedAdbShell.verify(context, tap))
    }

    @Test
    fun verifyWithShizukuOnlyAndShizukuDownSaysSoWithoutRunningAnything() {
        setMode(PrivilegedAccessMode.SHIZUKU_ONLY)
        shizuku.state = ShizukuState.NOT_INSTALLED
        assertEquals(EmbeddedAdbShell.BrokerStatus.SHIZUKU_UNAVAILABLE, EmbeddedAdbShell.verify(context, tap))
        assertTrue(shizuku.lines.isEmpty())
    }

    @Test
    fun brokerOnlyVerifyNeverAsksShizuku() {
        val verdict = EmbeddedAdbShell.verifyWithBackend(context, tap, brokerOnly = true)
        assertEquals(EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF, verdict.status)
        assertEquals(PrivilegedBackend.BROKER, verdict.backend)
        assertTrue(shizuku.lines.isEmpty())
    }

    // ---- finishSetup ---------------------------------------------------------------------------

    @Test
    fun finishSetupLeavesWirelessDebuggingOnWhenShizukuRanTheSetupAndSaysWhy() {
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        assertTrue(BrokerSettings.turnOffWirelessDebuggingAfterSetup(context))

        PrivilegedSetup.finishSetup(context, tap)

        assertTrue(EmbeddedAdbShell.isWirelessDebuggingEnabled(context))
        assertTrue("no command ran", shizuku.lines.isEmpty())
        val entry = BrokerAuditLog.entries(context).single()
        assertEquals(BrokerAuditLog.Kind.WIRELESS_DEBUGGING, entry.kind)
        assertEquals(PrivilegedSetup.ID_LEFT_ON_FOR_SHIZUKU, entry.id)
        assertTrue(entry.ok)
        assertEquals(PrivilegedSetup.REASON_LEFT_ON_FOR_SHIZUKU, entry.detail)
    }

    // ---- reset to stock ------------------------------------------------------------------------

    private fun grantWriteSecureSettingsForReal() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        // The fake "pm revoke" takes it back, as the real one would.
        shizuku.answer = { line ->
            if (line.startsWith("pm revoke")) {
                Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
            }
            val out = if (line.startsWith("service call adb")) "Result: Parcel(00000000    '....')\n" else ""
            ShellRunResult(true, out, 0, null)
        }
    }

    @Test
    fun resetThroughShizukuRevokesAndUnpairsThenChecksTheBrokerItself() {
        grantWriteSecureSettingsForReal()
        WriteSecureSettingsHolders.set(context, "backlight", true)
        val fingerprint = EmbeddedAdbShell.keyFingerprint(context)!!

        val result = PrivilegedSetup.resetToStock(context, tap)

        assertEquals(
            listOf(
                "pm revoke ${context.packageName} ${PrivilegedCommand.WRITE_SECURE_SETTINGS}",
                "service call adb ${PrivilegedCommand.TXN_UNPAIR_DEVICE} s16 $fingerprint",
            ),
            shizuku.lines
        )
        assertFalse(result.writeSecureSettingsStillHeld)
        // The proof is a broker connection being refused; with Wireless debugging off there is
        // none to try, so the user is told to check by hand.
        assertFalse(result.unpaired)
        assertEquals("after unpairing, the broker status is WIRELESS_DEBUGGING_OFF", result.reason)
        assertFalse(source.exists())
        assertFalse(WriteSecureSettingsHolders.anyNeeded(context))
        val revoke = BrokerAuditLog.entries(context).single { it.kind == BrokerAuditLog.Kind.REVOKE }
        assertTrue(revoke.ok)
    }

    @Test
    fun resetThroughShizukuWithoutABrokerKeyStillTakesThePermissionBack() {
        grantWriteSecureSettingsForReal()
        source.delete()

        val result = PrivilegedSetup.resetToStock(context, tap)

        assertEquals(listOf("pm revoke ${context.packageName} ${PrivilegedCommand.WRITE_SECURE_SETTINGS}"), shizuku.lines)
        assertFalse(result.writeSecureSettingsStillHeld)
        assertEquals(PrivilegedSetup.REASON_NO_KEY, result.reason)
    }

    @Test
    fun aShizukuUnpairThatReportsAnErrorIsNotTakenForSuccess() {
        shizuku.answer = { line ->
            val out = if (line.startsWith("service call adb")) "Result: Parcel(ffffffff 0000004c 'L...')\n" else ""
            ShellRunResult(true, out, 0, null)
        }
        val result = PrivilegedSetup.resetToStock(context, tap)
        assertFalse(result.unpaired)
        assertEquals("unpairDevice reported an error", result.reason)
    }

    // ---- the Shizuku answer itself -------------------------------------------------------------

    @Test
    fun aShizukuAnswerRoundTripsThroughItsBundle() {
        val sent = ShellRunResult(ok = true, output = "out", exitCode = 2, failure = null)
        assertEquals(sent, ShellRunResult.fromBundle(sent.toBundle(), MAX_SHELL_OUTPUT_BYTES))
        val noExit = ShellRunResult(ok = false, output = null, exitCode = null, failure = "x")
        assertEquals(noExit, ShellRunResult.fromBundle(noExit.toBundle(), MAX_SHELL_OUTPUT_BYTES))
        assertTrue(ShellRunResult.fromBundle(null, MAX_SHELL_OUTPUT_BYTES).serviceUnavailable)
    }

    @Test
    fun anOversizedShizukuAnswerIsRefusedInTheAppToo() {
        val big = ShellRunResult(ok = true, output = "y".repeat(MAX_SHELL_OUTPUT_BYTES + 1), exitCode = 0, failure = null)
        val read = ShellRunResult.fromBundle(big.toBundle(), MAX_SHELL_OUTPUT_BYTES)
        assertFalse(read.ok)
        assertNull(read.output)
        assertEquals("shell output exceeded $MAX_SHELL_OUTPUT_BYTES bytes", read.failure)
        assertEquals(30_000L, CLIENT_DEADLINE_MS)
    }
}
