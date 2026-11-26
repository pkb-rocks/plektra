package rocks.pkb.plektra.backlight

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import moe.shizuku.server.IRemoteProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuRemoteProcess
import rikka.shizuku.newShizukuRemoteProcessForTest
import rikka.shizuku.simulateBinderDeathForTest
import rocks.pkb.plektra.ShizukuStatus

/** Drives [ShizukuBacklightShell] through the real `ShizukuRemoteProcess` client class. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuBacklightShellTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val reply = "Result: Parcel(\t00000000 00000002 0031002d 00000000 '........-.1.....')\n"

    /**
     * Mirrors the Shizuku server: `exitValue()` of a running process fails, and the platform
     * delivers that IllegalThreadStateException to the client as IllegalArgumentException.
     */
    private inner class FakeServerProcess(
        private val exitsWithinTimeout: Boolean = true,
        output: String = reply
    ) : IRemoteProcess {
        var exited = false
        val waits = mutableListOf<Pair<Long, String>>()
        var destroyed = false
        var onWait: () -> Unit = {}
        var destroyFailure: Exception? = null
        private val stdout = folder.newFile().apply { writeText(output) }
        private val stdin = folder.newFile()
        private val binder = Binder()

        override fun asBinder(): IBinder = binder
        override fun getOutputStream(): ParcelFileDescriptor =
            ParcelFileDescriptor.open(stdin, ParcelFileDescriptor.MODE_WRITE_ONLY)
        override fun getInputStream(): ParcelFileDescriptor =
            ParcelFileDescriptor.open(stdout, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getErrorStream(): ParcelFileDescriptor =
            ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun waitFor(): Int {
            exited = true
            return 0
        }
        override fun exitValue(): Int {
            if (!exited) throw IllegalArgumentException("process hasn't exited")
            return 0
        }
        override fun destroy() {
            destroyFailure?.let { throw it }
            destroyed = true
        }
        override fun alive(): Boolean = !exited
        override fun waitForTimeout(timeout: Long, unit: String): Boolean {
            waits += timeout to unit
            onWait()
            exited = exitsWithinTimeout
            return exitsWithinTimeout
        }
    }

    private fun shell(server: FakeServerProcess, onStart: (ShizukuRemoteProcess) -> Unit = {}) =
        ShizukuBacklightShell(
            statusProvider = { ShizukuStatus.Connected },
            startProcess = { newShizukuRemoteProcessForTest(server).also(onStart) },
            timeoutMillis = 5_000
        )

    @Test
    fun aStillRunningProcessIsAwaitedThroughTheRemoteTimeout() {
        val server = FakeServerProcess()

        val result = shell(server).run(UnihertzBacklightVendorCommand.ReadTimeout)

        assertTrue(result.completed)
        assertEquals(0, result.exitCode)
        assertEquals("-1", UnihertzVendorParcel.parseString(result.output))
        assertEquals(listOf(5_000L to "MILLISECONDS"), server.waits)
        assertTrue(server.destroyed)
    }

    @Test
    fun theInheritedProcessTimeoutWouldFailOnTheSameRunningProcess() {
        val process = newShizukuRemoteProcessForTest(FakeServerProcess())

        val failure = runCatching { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun aTimeoutIsNotCompletedAndTheProcessIsDestroyed() {
        val server = FakeServerProcess(exitsWithinTimeout = false)

        val result = shell(server).run(UnihertzBacklightVendorCommand.WriteTimeout.followDisplay())

        assertFalse(result.completed)
        assertTrue(server.destroyed)
    }

    @Test
    fun binderDeathWhileWaitingDoesNotEscapeTheCleanup() {
        val server = FakeServerProcess()
        lateinit var process: ShizukuRemoteProcess
        server.onWait = {
            process.simulateBinderDeathForTest()
            throw DeadObjectException()
        }

        val result = shell(server) { process = it }.run(UnihertzBacklightVendorCommand.ReadTimeout)

        assertFalse(result.completed)
    }

    @Test
    fun aFailingDestroyAfterExitKeepsTheCompletedResult() {
        val server = FakeServerProcess().apply { destroyFailure = DeadObjectException() }

        val result = shell(server).run(UnihertzBacklightVendorCommand.ReadTimeout)

        assertTrue(result.completed)
        assertEquals("-1", UnihertzVendorParcel.parseString(result.output))
    }

    @Test
    fun aFailedStartIsNotCompleted() {
        val shell = ShizukuBacklightShell(
            statusProvider = { ShizukuStatus.Connected },
            startProcess = { throw IllegalStateException("binder gone") }
        )

        assertFalse(shell.run(UnihertzBacklightVendorCommand.ReadTimeout).completed)
    }
}
