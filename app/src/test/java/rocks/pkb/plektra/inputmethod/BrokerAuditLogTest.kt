package rocks.pkb.plektra.inputmethod

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Finding 1.3h: the broker's app-private, bounded audit log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrokerAuditLogTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = File(context.noBackupFilesDir, BrokerAuditLog.FILE_NAME)

    @Before
    fun setUp() {
        BrokerAuditLog.clear(context)
    }

    @Test
    fun entriesRoundTripOldestFirst() {
        BrokerAuditLog.record(context, BrokerAuditLog.Kind.COMMAND, "verify", ok = true)
        BrokerAuditLog.record(context, BrokerAuditLog.Kind.KEY, "deleted", ok = false, detail = "no service")

        val entries = BrokerAuditLog.entries(context)
        assertEquals(listOf("verify", "deleted"), entries.map { it.id })
        assertEquals(BrokerAuditLog.Kind.KEY, entries[1].kind)
        assertFalse(entries[1].ok)
        assertEquals("no service", entries[1].detail)
        assertTrue(entries[0].atMs > 0)
    }

    @Test
    fun repeatsAreCountedInsteadOfPushingOtherEntriesOut() {
        repeat(5) { BrokerAuditLog.record(context, BrokerAuditLog.Kind.COMMAND, "verify", ok = true) }
        val entries = BrokerAuditLog.entries(context)
        assertEquals(1, entries.size)
        assertEquals(5, entries.single().count)
    }

    @Test
    fun onlyTheLastEntriesAreKept() {
        repeat(BrokerAuditLog.MAX_ENTRIES + 50) {
            BrokerAuditLog.record(context, BrokerAuditLog.Kind.COMMAND, "c$it", ok = true)
        }
        val entries = BrokerAuditLog.entries(context)
        assertEquals(BrokerAuditLog.MAX_ENTRIES, entries.size)
        assertEquals("c50", entries.first().id)
        assertEquals("c${BrokerAuditLog.MAX_ENTRIES + 49}", entries.last().id)
    }

    @Test
    fun detailsAreOneShortLine() {
        BrokerAuditLog.record(context, BrokerAuditLog.Kind.COMMAND, "x", ok = false, detail = "a\nb\t c" + "z".repeat(500))
        val detail = BrokerAuditLog.entries(context).single().detail!!
        assertFalse(detail.contains('\n'))
        assertTrue(detail.startsWith("a b c"))
        assertTrue(detail.length <= 160)
    }

    @Test
    fun clearEmptiesTheLog() {
        BrokerAuditLog.record(context, BrokerAuditLog.Kind.KEY, "generated", ok = true)
        BrokerAuditLog.clear(context)
        assertTrue(BrokerAuditLog.entries(context).isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun theLogLivesInTheNoBackupDirAndSurvivesACorruptLine() {
        BrokerAuditLog.record(context, BrokerAuditLog.Kind.KEY, "generated", ok = true)
        assertTrue(file.exists())
        assertEquals(context.noBackupFilesDir, file.parentFile)
        file.appendText("{not json\n")
        BrokerAuditLog.record(context, BrokerAuditLog.Kind.PAIRING, "paired", ok = true)
        assertEquals(listOf("generated", "paired"), BrokerAuditLog.entries(context).map { it.id })
    }
}
