package rocks.pkb.plektra.clipboard

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rocks.pkb.plektra.SettingsManager
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * Exercises the background load of stored history. Each test parks the database worker
 * on a latch before creating the DAO. Additional gates cover the query-to-merge window
 * and operations after the merge but before a pending insert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardDaoLoadTest {
    private lateinit var context: Context
    private lateinit var database: ClipboardDatabase
    private val gate = CountDownLatch(1)
    private val dao get() = requireNotNull(createdDao)
    private var createdDao: ClipboardDao? = null
    private val extraGates = mutableListOf<CountDownLatch>()
    private var historyManager: ClipboardHistoryManager? = null
    private var previousDao: ClipboardDao? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = ClipboardDatabase.getInstance(context)
        ClipboardDao.awaitDatabaseIdle()
        previousDao = ReflectionHelpers.getStaticField(ClipboardDao::class.java, "instance")
        database.writableDatabase.delete(ClipboardDao.TABLE, null, null)
    }

    @After
    fun tearDown() {
        gate.countDown()
        extraGates.forEach { it.countDown() }
        ClipboardDao.awaitDatabaseIdle()
        historyManager?.onDestroy()
        createdDao?.listener = null
        ReflectionHelpers.setStaticField(ClipboardDao::class.java, "instance", previousDao)
        shadowOf(Looper.getMainLooper()).idle()
        database.writableDatabase.delete(ClipboardDao.TABLE, null, null)
        SettingsManager.setClipboardRetentionTime(context, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(context, 30)
    }

    private fun store(text: String, timestamp: Long, pinned: Boolean = false, sensitive: Boolean = false) {
        database.writableDatabase.insert(ClipboardDao.TABLE, null, ContentValues().apply {
            put("TIMESTAMP", timestamp)
            put("PINNED", pinned)
            put("TEXT", text)
            put("SENSITIVE", sensitive)
        })
    }

    /** Creates the DAO with its load queued behind the gate. */
    private fun createDaoBeforeLoad(beforeLoadMerge: (() -> Unit)? = null) {
        ClipboardDao.dbWorker.execute { gate.await() }
        createdDao = ClipboardDao(database, beforeLoadMerge)
    }

    private fun createManager(): ClipboardHistoryManager {
        SettingsManager.setClipboardHistoryEnabled(context, true)
        SettingsManager.setClipboardRetentionTime(context, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(context, 30)
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        ReflectionHelpers.setStaticField(ClipboardDao::class.java, "instance", dao)
        return ClipboardHistoryManager(context, ClipboardHistoryAccessPolicy { true }).also {
            historyManager = it
            it.onCreate()
        }
    }

    private fun release() {
        gate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private data class Row(val text: String, val pinned: Boolean, val sensitive: Boolean)

    private fun rows(): List<Row> {
        ClipboardDao.awaitDatabaseIdle()
        return database.readableDatabase.query(
            ClipboardDao.TABLE, arrayOf("TEXT", "PINNED", "SENSITIVE"), null, null, null, null, "TEXT"
        ).use {
            buildList {
                while (it.moveToNext()) add(Row(it.getString(0), it.getInt(1) != 0, it.getInt(2) != 0))
            }
        }
    }

    private fun latestStoredTimestamp(): Long {
        ClipboardDao.awaitDatabaseIdle()
        return database.readableDatabase.rawQuery("SELECT MAX(TIMESTAMP) FROM CLIPBOARD", null).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }
    }

    private fun texts() = (0 until dao.count()).mapNotNull(dao::getAt).map { it.text }

    @Test
    fun copyBeforeLoadKeepsStoredSensitivityInCacheAndOnDisk() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 60_000L, sensitive = true)
        createDaoBeforeLoad()

        dao.addClip(now, false, "stored-secret")
        release()

        val entry = requireNotNull(dao.getAt(0))
        assertEquals(1, dao.count())
        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertEquals(listOf(Row("stored-secret", pinned = false, sensitive = true)), rows())
    }

    @Test
    fun copyBeforeLoadKeepsStoredPinOnDisk() {
        val now = System.currentTimeMillis()
        store("stored-pinned", now - 60_000L, pinned = true)
        createDaoBeforeLoad()

        dao.addClip(now, false, "stored-pinned")
        release()

        assertTrue(requireNotNull(dao.getAt(0)).isPinned)
        assertEquals(listOf(Row("stored-pinned", pinned = true, sensitive = false)), rows())
    }

    @Test
    fun expirySweepBeforeLoadDoesNotLetStoredSecretsOutliveRetention() {
        val now = System.currentTimeMillis()
        store("expired-secret", now - 40 * 60_000L, sensitive = true)
        store("fresh-secret", now - 60_000L, sensitive = true)
        createDaoBeforeLoad()

        // Runs against the still-empty cache and used to record "nothing expires".
        dao.clearOldClips(now = true, retentionMinutes = 0, sensitiveRetentionMinutes = 30)
        release()
        assertEquals(2, dao.count())

        // Manager reads force a sweep, bypassing debounce; the load must also invalidate
        // the next-expiry shortcut computed while the cache was incomplete.
        dao.clearOldClips(now = true, retentionMinutes = 0, sensitiveRetentionMinutes = 30)

        assertEquals(listOf("fresh-secret"), texts())
        assertEquals(listOf(Row("fresh-secret", pinned = false, sensitive = true)), rows())
    }

    @Test
    fun historyLoadedIsDeliveredOnTheMainThread() {
        store("stored", System.currentTimeMillis())
        createDaoBeforeLoad()
        val deliveredOn = mutableListOf<Thread>()
        dao.listener = object : ClipboardDao.Listener {
            override fun onClipInserted(position: Int) {}
            override fun onClipsRemoved(position: Int, count: Int) {}
            override fun onClipMoved(oldPosition: Int, newPosition: Int) {}
            override fun onHistoryLoaded() { deliveredOn += Thread.currentThread() }
        }
        gate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        assertTrue(deliveredOn.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Looper.getMainLooper().thread), deliveredOn)
    }

    @Test
    fun clearAllBeforeLoadAlsoClearsStoredHistory() {
        val now = System.currentTimeMillis()
        store("stored-normal", now - 1_000L)
        store("stored-pinned", now - 2_000L, pinned = true)
        createDaoBeforeLoad()

        dao.clear()
        release()

        assertEquals(0, dao.count())
        assertEquals(emptyList<Row>(), rows())
    }

    @Test
    fun clearNonPinnedBeforeLoadAlsoClearsStoredNormalRows() {
        val now = System.currentTimeMillis()
        store("stored-normal", now - 1_000L)
        store("stored-secret", now - 1_500L, sensitive = true)
        store("stored-pinned", now - 2_000L, pinned = true)
        createDaoBeforeLoad()

        dao.clearNonPinned()
        release()

        assertEquals(listOf("stored-pinned"), texts())
        assertEquals(listOf(Row("stored-pinned", pinned = true, sensitive = false)), rows())
    }

    @Test
    fun clearBeforeLoadDoesNotDropClipsCopiedAfterIt() {
        store("stored", System.currentTimeMillis() - 1_000L)
        createDaoBeforeLoad()

        dao.clear()
        dao.addClip(System.currentTimeMillis(), false, "after-clear")
        release()

        assertEquals(listOf("after-clear"), texts())
        assertEquals(listOf(Row("after-clear", pinned = false, sensitive = false)), rows())
    }

    @Test
    fun deleteByIdRemovesTheIntendedClipAfterTheLoadShiftsPositions() {
        val now = System.currentTimeMillis()
        store("stored-newer", now + 10_000L)
        createDaoBeforeLoad()
        dao.addClip(now, false, "copied")
        val target = requireNotNull(dao.getAt(0))
        assertEquals("copied", target.text)

        release()
        // The merge put the stored clip in front, so position 0 now holds a different clip.
        assertEquals("stored-newer", requireNotNull(dao.getAt(0)).text)

        dao.deleteClip(target.id)

        assertEquals(listOf("stored-newer"), texts())
        assertEquals(listOf(Row("stored-newer", pinned = false, sensitive = false)), rows())
        assertNull(dao.get(target.id))
    }

    @Test
    fun deleteBeforeLoadAlsoRemovesTheStoredCopy() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        createDaoBeforeLoad()
        dao.addClip(now, false, "stored-secret")

        dao.deleteClip(requireNotNull(dao.getAt(0)).id)
        release()

        assertEquals(emptyList<String>(), texts())
        assertEquals(emptyList<Row>(), rows())
    }

    @Test
    fun clearNonPinnedMergesStoredSensitivityBeforeKeepingAPreLoadPin() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        createDaoBeforeLoad()
        dao.addClip(now, false, "stored-secret")
        val entry = requireNotNull(dao.getAt(0))
        dao.togglePinned(entry.id)
        assertTrue(entry.isPinned)

        dao.clearNonPinned()
        release()

        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertNull(dao.get(entry.id))
        assertEquals(emptyList<Row>(), rows())
    }

    @Test
    fun clearNonPinnedKeepsACopyMadeAfterTheClearButMergesItsSensitivity() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        createDaoBeforeLoad()
        dao.clearNonPinned()
        dao.addClip(now, false, "stored-secret")

        release()

        assertEquals(listOf("stored-secret"), texts())
        assertTrue(requireNotNull(dao.getAt(0)).isSensitive)
        assertEquals(listOf(Row("stored-secret", pinned = false, sensitive = true)), rows())
    }

    @Test
    fun duplicateStoredRowsPersistTheMergedFlagsAndTimestamp() {
        val now = System.currentTimeMillis()
        store("duplicate", now - 60_000L, pinned = true)
        store("duplicate", now, sensitive = true)
        createDaoBeforeLoad()

        release()

        val entry = requireNotNull(dao.getAt(0))
        assertEquals(1, dao.count())
        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertEquals(now, entry.timeStamp)
        assertEquals(listOf(Row("duplicate", pinned = false, sensitive = true)), rows())
        database.readableDatabase.rawQuery("SELECT TIMESTAMP FROM CLIPBOARD", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(now, it.getLong(0))
        }
        createdDao = ClipboardDao(database)
        ClipboardDao.awaitDatabaseIdle()
        assertTrue(requireNotNull(dao.getAt(0)).isSensitive)
        assertFalse(requireNotNull(dao.getAt(0)).isPinned)
    }

    @Test
    fun failedLoadRetriesOnTheNextManagerReadAndPurgesUnreadExpiredRows() {
        val now = System.currentTimeMillis()
        store("expired-secret", now - 40 * 60_000L, sensitive = true)
        store("expired-normal", now - 40 * 60_000L)
        store("stored-pinned", now - 40 * 60_000L, pinned = true)
        store("fresh", now)
        database.writableDatabase.execSQL("ALTER TABLE CLIPBOARD RENAME COLUMN TEXT TO UNREADABLE_TEXT")
        try {
            createDaoBeforeLoad()
            val manager = createManager()
            SettingsManager.setClipboardRetentionTime(context, 30)
            release()
            assertEquals(0, manager.getHistorySize())
            ClipboardDao.awaitDatabaseIdle()
            database.readableDatabase.rawQuery("SELECT UNREADABLE_TEXT FROM CLIPBOARD ORDER BY UNREADABLE_TEXT", null).use {
                val remaining = buildList {
                    while (it.moveToNext()) add(it.getString(0))
                }
                assertEquals(listOf("fresh", "stored-pinned"), remaining)
            }
        } finally {
            database.writableDatabase.execSQL("ALTER TABLE CLIPBOARD RENAME COLUMN UNREADABLE_TEXT TO TEXT")
        }

        val manager = requireNotNull(historyManager)
        manager.getHistorySize()
        ClipboardDao.awaitDatabaseIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, manager.getHistorySize())
        assertEquals(listOf("stored-pinned", "fresh"), texts())
    }

    @Test
    fun nullStoredTextDoesNotPreventLoadingValidRows() {
        store("valid", System.currentTimeMillis())
        database.writableDatabase.execSQL(
            "INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT, SENSITIVE) VALUES (?, 0, NULL, 1)",
            arrayOf(System.currentTimeMillis())
        )
        createDaoBeforeLoad()

        release()

        assertEquals(listOf("valid"), texts())
        assertEquals(listOf(Row("valid", pinned = false, sensitive = false)), rows())
    }

    @Test
    fun managerHistoryLoadedRefreshesSensitiveSnapshotsOnMain() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        store("expired-secret", now - 40 * 60_000L, sensitive = true)
        createDaoBeforeLoad()
        val manager = createManager()
        val snapshots = mutableListOf<List<ClipboardHistoryEntry>>()
        val deliveredOn = mutableListOf<Thread>()
        manager.addHistoryChangeListener {
            deliveredOn += Thread.currentThread()
            snapshots += (0 until manager.getHistorySize()).mapNotNull { manager.getHistoryEntry(it)?.copy() }
        }
        dao.addClip(now, false, "stored-secret")
        assertFalse(snapshots.last().single().isSensitive)
        snapshots.clear()
        deliveredOn.clear()

        gate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        assertTrue(snapshots.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(snapshots.isNotEmpty())
        assertTrue(deliveredOn.all { it === Looper.getMainLooper().thread })
        assertEquals(listOf("stored-secret"), snapshots.last().map { it.text })
        assertTrue(snapshots.last().single().isSensitive)
        assertFalse(snapshots.last().single().isPinned)
        assertEquals(listOf(Row("stored-secret", pinned = false, sensitive = true)), rows())
    }

    @Test
    fun managerHistoryLoadFailingDuringTheWriteStillRefreshesSensitiveSnapshotsOnMain() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        // Expired, so every read sweeps it out of the cache and a failed retry re-adds it.
        store("old-secret", now - 31 * 60_000L, sensitive = true)
        database.writableDatabase.execSQL(
            "CREATE TRIGGER keep_stored BEFORE DELETE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'blocked'); END"
        )
        try {
            createDaoBeforeLoad()
            val manager = createManager()
            val snapshots = mutableListOf<List<ClipboardHistoryEntry>>()
            val deliveredOn = mutableListOf<Thread>()
            manager.addHistoryChangeListener {
                deliveredOn += Thread.currentThread()
                snapshots += (0 until manager.getHistorySize()).mapNotNull { manager.getHistoryEntry(it)?.copy() }
            }
            dao.addClip(now, false, "stored-secret")
            assertFalse(snapshots.last().single().isSensitive)
            snapshots.clear()
            deliveredOn.clear()

            gate.countDown()
            ClipboardDao.awaitDatabaseIdle()
            assertTrue(snapshots.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(snapshots.isNotEmpty())
            assertTrue(deliveredOn.all { it === Looper.getMainLooper().thread })
            assertEquals(listOf("stored-secret"), snapshots.last().map { it.text })
            assertTrue(snapshots.last().single().isSensitive)
            assertFalse(snapshots.last().single().isPinned)
            // The retries that the refresh triggers, and the rows they re-add, stay silent.
            val delivered = snapshots.size
            repeat(3) {
                ClipboardDao.awaitDatabaseIdle()
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(delivered, snapshots.size)
        } finally {
            ClipboardDao.awaitDatabaseIdle()
            database.writableDatabase.execSQL("DROP TRIGGER keep_stored")
        }
    }

    @Test
    fun secondCopyBeforeLoadPersistsStoredSensitivityAndLatestTimestamp() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 60_000L, sensitive = true)
        // Blocks the stored row's removal, so the first load fails after its insert has run.
        database.writableDatabase.execSQL(
            "CREATE TRIGGER keep_stored BEFORE DELETE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'blocked'); END"
        )
        try {
            createDaoBeforeLoad()
            dao.addClip(now - 1_000L, false, "stored-secret")
            val entry = requireNotNull(dao.getAt(0))
            release()
            assertTrue(entry.isSensitive)
            assertTrue(entry.rowId != ClipboardDao.PENDING_ID)

            // The history is still not loaded, and this copy finds an entry that has a row.
            dao.addClip(now, false, "stored-secret")
            ClipboardDao.awaitDatabaseIdle()

            assertEquals(now, entry.timeStamp)
            assertEquals(now, latestStoredTimestamp())
        } finally {
            database.writableDatabase.execSQL("DROP TRIGGER keep_stored")
        }

        dao.clearOldClips()
        release()

        val entry = requireNotNull(dao.getAt(0))
        assertEquals(1, dao.count())
        assertEquals(now, entry.timeStamp)
        assertTrue(entry.isSensitive)
        assertEquals(listOf(Row("stored-secret", pinned = false, sensitive = true)), rows())
        assertEquals(now, latestStoredTimestamp())
    }

    @Test
    fun pinBeforeLoadCannotWeakenStoredSensitivityOnDisk() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        createDaoBeforeLoad()
        dao.addClip(now, false, "stored-secret")
        val entry = requireNotNull(dao.getAt(0))
        dao.togglePinned(entry.id)
        assertTrue(entry.isPinned)

        release()

        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertEquals(listOf(Row("stored-secret", pinned = false, sensitive = true)), rows())
    }

    @Test
    fun sweepAfterTheLoadIsNotDebouncedByASweepBeforeIt() {
        val now = System.currentTimeMillis()
        store("old-normal", now - 10 * 60_000L)
        store("old-secret", now - 10 * 60_000L, sensitive = true)
        store("pinned", now - 10 * 60_000L, pinned = true)
        createDaoBeforeLoad()
        // Moves the clock past the first sweep's debounce window; the clock is paused otherwise.
        SystemClock.sleep(10_000L)
        dao.clearOldClips(retentionMinutes = 30, sensitiveRetentionMinutes = 30)

        release()
        assertEquals(3, dao.count())
        // A shorter retention, inside the debounce window of the sweep before the load.
        dao.clearOldClips(retentionMinutes = 5, sensitiveRetentionMinutes = 5)

        assertEquals(listOf("pinned"), texts())
        assertEquals(listOf(Row("pinned", pinned = true, sensitive = false)), rows())
    }

    @Test
    fun managerRemovesByIdAfterLoadShiftsPositionsAndHonorsForce() {
        val now = System.currentTimeMillis()
        store("stored-pinned", now + 10_000L, pinned = true)
        createDaoBeforeLoad()
        val manager = createManager()
        dao.addClip(now, false, "target")
        val target = requireNotNull(manager.getHistoryEntry(0)).copy()

        release()
        val pinned = requireNotNull(manager.getHistoryEntry(0)).copy()
        assertEquals("stored-pinned", pinned.text)
        manager.removeEntryById(target.id, force = true)
        manager.removeEntryById(target.id, force = true)
        manager.removeEntryById(pinned.id)

        assertEquals(listOf("stored-pinned"), texts())
        assertEquals(listOf(Row("stored-pinned", pinned = true, sensitive = false)), rows())
        manager.removeEntryById(pinned.id, force = true)
        assertEquals(0, manager.getHistorySize())
        assertEquals(emptyList<Row>(), rows())
    }

    @Test
    fun deleteBetweenTheQueryAndMergeDoesNotResurrectTheStoredCopy() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        val queried = CountDownLatch(1)
        val mergeGate = CountDownLatch(1).also { extraGates += it }
        createDaoBeforeLoad {
            queried.countDown()
            check(mergeGate.await(10, TimeUnit.SECONDS))
        }
        dao.addClip(now, false, "stored-secret")
        val id = requireNotNull(dao.getAt(0)).id
        gate.countDown()
        assertTrue(queried.await(10, TimeUnit.SECONDS))

        dao.deleteClip(id)
        mergeGate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, dao.count())
        assertEquals(emptyList<Row>(), rows())
    }

    @Test
    fun deleteAfterMergeBeforePendingInsertDoesNotResurrectTheClip() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 1_000L, sensitive = true)
        createDaoBeforeLoad()
        val merged = CountDownLatch(1)
        val insertGate = CountDownLatch(1).also { extraGates += it }
        ClipboardDao.dbWorker.execute {
            merged.countDown()
            check(insertGate.await(10, TimeUnit.SECONDS))
        }
        dao.addClip(now, false, "stored-secret")
        val entry = requireNotNull(dao.getAt(0))
        gate.countDown()
        assertTrue(merged.await(10, TimeUnit.SECONDS))
        assertTrue(entry.isSensitive)
        assertEquals(ClipboardDao.PENDING_ID, entry.rowId)

        dao.deleteClip(entry.id)
        insertGate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, dao.count())
        assertEquals(emptyList<Row>(), rows())
    }

    @Test
    fun failedInsertDoesNotLogClipboardTextOrExceptionValues() {
        createDaoBeforeLoad()
        release()
        val secret = "never-log-this-clipboard-value"
        database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_clip BEFORE INSERT ON CLIPBOARD BEGIN SELECT RAISE(ABORT, '$secret'); END"
        )
        try {
            ShadowLog.clear()
            dao.addClip(System.currentTimeMillis(), false, secret, sensitive = true)
            ClipboardDao.awaitDatabaseIdle()

            val logs = ShadowLog.getLogs()
            assertTrue(logs.any { it.tag == "ClipboardDao" && it.msg == "Clipboard insert failed" })
            assertFalse(logs.any { it.msg.contains(secret) || it.throwable?.toString()?.contains(secret) == true })
            assertEquals(emptyList<Row>(), rows())
        } finally {
            database.writableDatabase.execSQL("DROP TRIGGER reject_clip")
        }
    }

}
