package rocks.pkb.plektra.shizuku

import moe.shizuku.manager.adb.CLIENT_DEADLINE_MS
import moe.shizuku.manager.adb.MAX_SHELL_OUTPUT_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * the Shizuku runner keeps the broker's limits (AdbClient's
 * MAX_SHELL_OUTPUT_BYTES and CLIENT_DEADLINE_MS): too much output or too much time kills the
 * command and fails the run. A fake process stands in for the program.
 */
class CappedProcessRunnerTest {

    /** A process whose output is [input] and which has exited (or not) as [exited] says. */
    private class FakeProcess(
        private val input: InputStream,
        private val exit: Int = 0,
        @Volatile var exited: Boolean = true,
    ) : Process() {
        @Volatile
        var destroyed = false
        val stdin = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (!exited) Thread.sleep(5)
            return exit
        }
        override fun exitValue(): Int = if (exited) exit else throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
            input.close()
        }
    }

    /** Streams bytes without end until closed, like `yes`. */
    private class EndlessStream : InputStream() {
        @Volatile
        private var closed = false
        override fun read(): Int = if (closed) throw IOException("closed") else 'y'.code
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("closed")
            b.fill('y'.code.toByte(), off, off + len)
            return len
        }
        override fun close() {
            closed = true
        }
    }

    /** Never produces a byte until closed, like `sleep 3600`. */
    private class SilentStream : InputStream() {
        private val closed = CountDownLatch(1)
        override fun read(): Int {
            closed.await(60, TimeUnit.SECONDS)
            return -1
        }
        override fun close() = closed.countDown()
    }

    @Test
    fun theLimitsAreTheBrokersOwn() {
        assertEquals(256 * 1024, MAX_SHELL_OUTPUT_BYTES)
        assertEquals(30_000L, CLIENT_DEADLINE_MS)
    }

    @Test
    fun aCommandThatFinishesReturnsItsOutputAndExitCode() {
        val process = FakeProcess(ByteArrayInputStream("pastiera_verify\n".toByteArray()), exit = 3)
        val result = CappedProcessRunner.run(process, MAX_SHELL_OUTPUT_BYTES, CLIENT_DEADLINE_MS)
        assertTrue(result.ok)
        assertEquals("pastiera_verify\n", result.output)
        assertEquals(3, result.exitCode)
        assertNull(result.failure)
        assertFalse(process.destroyed)
    }

    @Test
    fun outputPastTheCapKillsTheCommandAndFails() {
        val process = FakeProcess(EndlessStream(), exited = false)
        val result = CappedProcessRunner.run(process, maxOutputBytes = 64 * 1024, deadlineMs = 10_000)
        assertFalse(result.ok)
        assertEquals("shell output exceeded ${64 * 1024} bytes", result.failure)
        assertTrue(process.destroyed)
        assertNull(result.exitCode)
        assertTrue((result.output?.length ?: 0) <= 64 * 1024)
    }

    @Test
    fun outputExactlyAtTheCapIsAccepted() {
        val bytes = ByteArray(1024) { 'a'.code.toByte() }
        val result = CappedProcessRunner.run(FakeProcess(ByteArrayInputStream(bytes)), 1024, 10_000)
        assertTrue(result.failure, result.ok)
        assertEquals(1024, result.output?.length)
    }

    @Test
    fun aCommandThatOutlivesTheDeadlineIsKilled() {
        val process = FakeProcess(SilentStream(), exited = false)
        val started = System.nanoTime()
        val result = CappedProcessRunner.run(process, MAX_SHELL_OUTPUT_BYTES, deadlineMs = 200)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(result.ok)
        assertEquals(CappedProcessRunner.deadlineFailure(200), result.failure)
        assertTrue(process.destroyed)
        assertTrue("took $tookMs ms", tookMs < 5_000)
    }

    @Test
    fun closedOutputButNoExitStillHitsTheDeadline() {
        // The command closed its output but keeps running (a background child, say).
        val process = FakeProcess(ByteArrayInputStream("x".toByteArray()), exited = false)
        val result = CappedProcessRunner.run(process, MAX_SHELL_OUTPUT_BYTES, deadlineMs = 200)
        assertFalse(result.ok)
        assertEquals(CappedProcessRunner.deadlineFailure(200), result.failure)
        assertEquals("x", result.output)
        assertTrue(process.destroyed)
    }

    @Test
    fun nothingIsWrittenToTheCommandsInput() {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(0)))
        CappedProcessRunner.run(process, MAX_SHELL_OUTPUT_BYTES, CLIENT_DEADLINE_MS)
        assertEquals(0, process.stdin.size())
    }

    @Test
    fun runArgvStartsExactlyTheGivenArgvAndNothingElse() {
        var started: List<String>? = null
        val process = FakeProcess(ByteArrayInputStream("ok\n".toByteArray()))
        val result = CappedProcessRunner.runArgv(listOf("pm", "grant", "a.b", "p"), 1024, 10_000) {
            started = it
            process
        }
        assertEquals(listOf("pm", "grant", "a.b", "p"), started)
        assertTrue(result.ok)
        assertEquals("ok\n", result.output)
    }

    /** No `sh` wrapper: the deadline kills the process that was started, so no child is left behind. */
    @Test
    fun runArgvKillsTheStartedProcessAtTheDeadline() {
        val process = FakeProcess(SilentStream(), exited = false)
        val result = CappedProcessRunner.runArgv(listOf("sleep", "60"), MAX_SHELL_OUTPUT_BYTES, 200) { process }
        assertFalse(result.ok)
        assertEquals(CappedProcessRunner.deadlineFailure(200), result.failure)
        assertTrue(process.destroyed)
    }

    @Test
    fun runArgvFailsWhenTheProgramCannotStart() {
        val result = CappedProcessRunner.runArgv(listOf("nope"), 1024, 1_000) { throw IOException("no such file") }
        assertFalse(result.ok)
        assertEquals("IOException: no such file", result.failure)
        assertNull(result.exitCode)
    }

    /** A real process: shell syntax in an argument is just text, since no shell reads it. */
    @Test
    fun runArgvPassesShellSyntaxAsPlainText() {
        val result = CappedProcessRunner.runArgv(listOf("echo", "a; echo b | cat"), 1024, 10_000)
        assertTrue(result.failure, result.ok)
        assertEquals("a; echo b | cat\n", result.output)
        assertEquals(0, result.exitCode)
    }
}
