package rocks.pkb.plektra.shizuku

import android.os.Bundle
import android.os.DeadObjectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.ShellRunResult
import java.util.concurrent.CompletableFuture

/**
 * how a call to the Shizuku service ends. A service that dies or disconnects
 * while a command runs (a Shizuku server restart takes it along) must read as a failure the user
 * can retry, never as success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuUserServiceShellTest {

    private val shell = ShizukuUserServiceShell

    @Test
    fun anAnswerCarriesItsOutputExitCodeAndTheServiceUid() {
        val call = CompletableFuture<Bundle>()
        call.complete(ShellRunResult(ok = true, output = "out", exitCode = 0, failure = null, uid = 0).toBundle())
        val result = shell.awaitResult(call, 1_000)
        assertTrue(result.ok)
        assertEquals("out", result.output)
        assertEquals(0, result.uid)
    }

    /** onServiceDisconnected cancels the call in flight. */
    @Test
    fun aDisconnectDuringTheCallIsAFailureNotASuccess() {
        val call = CompletableFuture<Bundle>()
        Thread { Thread.sleep(50); call.cancel(true) }.start()
        val result = shell.awaitResult(call, 10_000)
        assertFalse(result.ok)
        assertTrue(result.serviceUnavailable)
        assertEquals(ShizukuUserServiceShell.REASON_SERVICE_STOPPED, result.failure)
        assertNull(result.exitCode)
    }

    /** The binder call itself throws when the server dies. */
    @Test
    fun aBinderThatDiesDuringTheCallIsAFailureNotASuccess() {
        val call = CompletableFuture<Bundle>()
        Thread { Thread.sleep(50); call.completeExceptionally(DeadObjectException("server died")) }.start()
        val result = shell.awaitResult(call, 10_000)
        assertFalse(result.ok)
        assertTrue(result.serviceUnavailable)
        assertTrue(result.failure, result.failure!!.startsWith(ShizukuUserServiceShell.REASON_SERVICE_STOPPED))
        assertTrue(result.failure, result.failure!!.contains("DeadObjectException"))
    }

    @Test
    fun aCallThatNeverAnswersTimesOutKillsTheServiceAndFails() {
        val call = CompletableFuture<Bundle>()
        var killed = false
        val result = shell.awaitResult(call, 100) { killed = true }
        assertFalse(result.ok)
        assertFalse(result.serviceUnavailable)
        assertTrue(killed)
        assertTrue(call.isCancelled)
        assertEquals(CappedProcessRunner.deadlineFailure(30_000), result.failure)
    }
}
