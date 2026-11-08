package rocks.pkb.plektra.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ui.ClipboardHistoryView

/**
 * Ordering, persistence and failure behaviour of the serial clipboard history.
 *
 * Several failure cases (forced load and insert failures, rows without text, very large
 * rows with NUL characters, log hygiene) follow the scenarios Daniel JB Clark (djbclark)
 * covered in ClipboardDaoLoadTest in pkb-rocks/plektra#120; the tests are written anew
 * for this design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardHistoryTest {
    private lateinit var context: Context
    private lateinit var store: ClipboardTestStore
    private var accessible = true
    private val managers = mutableListOf<ClipboardHistoryManager>()
    private val retention = ClipboardRetention(0, 30)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.setClipboardHistoryEnabled(context, true)
        SettingsManager.setClipboardRetentionTime(context, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(context, 30)
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        store = ClipboardTestStore(context, "clipboard-history-test.db")
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        managers.forEach { it.onDestroy() }
        store.close()
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
    }

    private fun manager(history: ClipboardHistory = store.history) =
        ClipboardHistoryManager(context, { accessible }, history) { store.now }.also {
            managers += it
            it.onCreate()
            // Tests deliver clipboard changes explicitly, after setting a wall-clock timestamp.
            context.getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(it)
        }

    private fun copy(manager: ClipboardHistoryManager, text: String, sensitive: Boolean = false) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", text).apply {
            if (sensitive) description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        })
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            context.getSystemService(ClipboardManager::class.java).primaryClip!!.description, "setTimestamp",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, store.now)
        )
        manager.onPrimaryClipChanged()
    }

    private fun texts(manager: ClipboardHistoryManager) = manager.getHistoryEntries().map { it.text }

    @Test
    fun copiesDuringStartupWaitForTheLoadAndMergeWithStoredRows() {
        store.store("stored-secret", store.now - 60_000L, sensitive = true)
        store.store("stored-pinned", store.now - 120_000L, pinned = true)
        val manager = manager()
        store.now += 1_000L
        copy(manager, "stored-pinned", sensitive = true)
        copy(manager, "stored-secret")
        copy(manager, "fresh")

        assertEquals(ClipboardHistoryManager.Status.LOADING, manager.uiState().status)
        assertEquals(0, manager.getHistorySize())

        store.settle()

        val entries = manager.getHistoryEntries()
        assertEquals(ClipboardHistoryManager.Status.READY, manager.uiState().status)
        assertEquals(setOf("stored-secret", "stored-pinned", "fresh"), entries.map { it.text }.toSet())
        // A stored row and a copy of the same text are one entry; protection never weakens.
        assertTrue(entries.single { it.text == "stored-secret" }.isSensitive)
        val upgraded = entries.single { it.text == "stored-pinned" }
        assertTrue(upgraded.isSensitive)
        assertFalse(upgraded.isPinned)
        assertEquals(entries.map { it.id }.sorted(), store.rows().map { it.id })
        assertEquals(store.rows().map { it.text to it.sensitive }.toSet(), entries.map { it.text to it.isSensitive }.toSet())
    }

    @Test
    fun startupShowsLoadingAndBlocksHistoryActionsUntilReady() {
        val id = store.store("stored", store.now)
        val manager = manager()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, manager)
        activity.setContentView(view)

        assertTrue(visibleTexts(view).contains(context.getString(R.string.clipboard_loading_state)))
        val connection = RecordingInputConnection(context)
        manager.pasteEntry(id, connection)
        manager.toggleClipPinned(id)
        manager.removeEntry(id, force = true)
        store.settle()
        layout(view)

        assertTrue(connection.committedTexts.isEmpty())
        assertEquals(listOf(ClipboardTestStore.Row(id, "stored", pinned = false, sensitive = false)), store.rows())
        assertTrue(visibleTexts(view).contains("stored"))
    }

    @Test
    fun changesArePublishedOnlyAfterTheTransactionCommitted() {
        val manager = manager()
        store.settle()
        val published = mutableListOf<ClipboardHistory.Snapshot>()
        store.history.addListener { published += it }
        val failures = mutableListOf<ClipboardHistory.Operation>()
        store.history.addFailureListener { failures += it }
        val secret = "never-log-this-clipboard-value"
        store.database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_clip BEFORE INSERT ON CLIPBOARD BEGIN SELECT RAISE(ABORT, '$secret'); END"
        )

        copy(manager, secret, sensitive = true)
        store.worker.runAll()
        // Publication stays on the main thread; a failure refresh never adds a provisional entry.
        assertTrue(published.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(published.single().entries.isEmpty())
        assertEquals(listOf(ClipboardHistory.Operation.SAVE), failures)
        assertEquals(0, manager.getHistorySize())
        assertTrue(store.rows().isEmpty())
        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { it.tag == "ClipboardHistory" && it.msg.startsWith("Clipboard save failed") })
        assertFalse(logs.any { it.msg.contains(secret) || it.throwable?.toString()?.contains(secret) == true })

        store.database.writableDatabase.execSQL("DROP TRIGGER reject_clip")
        published.clear()
        copy(manager, "accepted")
        store.worker.runAll()
        assertTrue(published.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()

        val entry = published.single().entries.single()
        assertEquals(store.rows().single().id, entry.id)
        assertTrue(entry.id > 0)
        assertEquals(listOf(entry), manager.getHistoryEntries())
    }

    @Test
    fun failedUpdatesLeaveThePublishedEntryUnchanged() {
        val manager = manager()
        copy(manager, "keep-me")
        store.settle()
        val entry = manager.getHistoryEntries().single()
        store.database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_change BEFORE UPDATE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'no'); END"
        )
        store.database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_delete BEFORE DELETE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'no'); END"
        )

        manager.toggleClipPinned(entry.id)
        manager.removeEntry(entry.id, force = true)
        manager.clearHistory()
        store.settle()

        assertEquals(listOf(entry), manager.getHistoryEntries())
        assertEquals(listOf(ClipboardTestStore.Row(entry.id, "keep-me", pinned = false, sensitive = false)), store.rows())
    }

    @Test
    fun sensitivityOnlyChangeIsPublishedEvenWithTheSameTimestamp() {
        store.store("same-timestamp", store.now, pinned = true)
        val manager = manager()
        store.settle()
        val published = mutableListOf<ClipboardHistory.Snapshot>()
        store.history.addListener { published += it }

        store.history.addClip("same-timestamp", store.now, true, retention)
        store.settle()

        val entry = published.single().entries.single()
        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertEquals(entry, manager.getHistoryEntries().single())
        assertTrue(store.rows().single().sensitive)
    }

    @Test
    fun failedSensitiveUpgradeStaysMaskedAndRecoveryPersistsProtectionWithoutReplayingPin() {
        store.store("protect-me", store.now, pinned = true)
        val manager = manager()
        store.settle()
        val id = manager.getHistoryEntries().single().id
        val failures = mutableListOf<ClipboardHistory.Operation>()
        store.history.addFailureListener { failures += it }
        store.database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_protection BEFORE UPDATE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'blocked'); END"
        )

        store.history.addClip("protect-me", store.now, true, retention)
        store.settle()
        val protected = manager.getHistoryEntries().single()
        assertTrue(protected.isSensitive)
        assertFalse(protected.isPinned)
        assertFalse(store.rows().single().sensitive)
        assertTrue(store.rows().single().pinned)
        assertEquals(listOf(ClipboardHistory.Operation.SAVE), failures)

        manager.toggleClipPinned(id)
        store.settle()
        assertFalse(manager.getHistoryEntries().single().isPinned)
        store.database.writableDatabase.execSQL("DROP TRIGGER reject_protection")
        manager.onHistoryOpened()
        store.settle()
        assertTrue(store.rows().single().sensitive)
        assertFalse(store.rows().single().pinned)

        val restarted = manager(store.restart())
        store.settle()
        assertEquals(protected, restarted.getHistoryEntries().single())
    }

    @Test
    fun retentionChangesPurgeAndRefreshWithoutOpeningHistory() {
        store.store("ten-minutes-old", store.now - 10 * 60_000L, sensitive = true)
        val manager = manager()
        store.settle()
        assertEquals(1, manager.getHistorySize())
        val snapshots = mutableListOf<ClipboardHistory.Snapshot>()
        store.history.addListener { snapshots += it }

        SettingsManager.setSensitiveClipboardRetentionTime(context, 5)
        store.settle()

        assertTrue(store.rows().isEmpty())
        assertTrue(snapshots.last().entries.isEmpty())
        assertEquals(0, manager.getHistorySize())
    }

    @Test
    fun recreatedManagerRestoresExpiryTimerForAnAlreadyLoadedHistory() {
        SettingsManager.setSensitiveClipboardRetentionTime(context, 1)
        store.store("expires-after-recreation", store.now, sensitive = true)
        val first = manager()
        store.settle()
        first.onDestroy()
        val second = manager()
        store.settle()
        assertEquals(1, second.getHistorySize())

        store.now += 61_000L
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))
        store.settle()

        assertTrue(store.rows().isEmpty())
        assertEquals(0, second.getHistorySize())
    }

    @Test
    fun failedCleanupUsesBackoffAndNeverQueuesAnImmediateRetry() {
        store.store("expires", store.now, sensitive = true)
        val manager = manager()
        store.settle()
        store.database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_cleanup BEFORE DELETE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'blocked'); END"
        )
        store.now += 31 * 60_000L
        manager.onHistoryOpened()
        store.settle()
        assertEquals(0, manager.getHistorySize())
        assertEquals(1, store.rows().size)
        assertEquals(store.now + 60_000L, store.history.snapshot.nextExpiry)
        assertEquals(0, store.worker.pending)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(59))
        assertEquals(0, store.worker.pending)
        store.now += 60_000L
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        store.settle()
        assertEquals(store.now + 120_000L, store.history.snapshot.nextExpiry)
        assertEquals(0, store.worker.pending)

        store.database.writableDatabase.execSQL("DROP TRIGGER reject_cleanup")
        manager.onHistoryOpened()
        store.settle()
        assertTrue(store.rows().isEmpty())
        assertEquals(Long.MAX_VALUE, store.history.snapshot.nextExpiry)
    }

    @Test
    fun deleteAndPinActOnStableIdsAndAreRecheckedWhenTheyRun() {
        val manager = manager()
        store.settle()
        listOf("a", "b", "c").forEach { text ->
            store.now += 1_000L
            store.history.addClip(text, store.now, false, retention)
        }
        store.settle()
        val (c, b, a) = manager.getHistoryEntries()
        assertEquals(listOf("c", "b", "a"), listOf(c.text, b.text, a.text))

        // A queued copy reorders the history before the delete runs.
        store.now += 1_000L
        store.history.addClip("a", store.now, false, retention)
        manager.removeEntry(b.id)
        // The UI still shows "c" as normal text, but a queued copy makes it sensitive first.
        store.history.addClip("c", store.now, true, retention)
        manager.toggleClipPinned(c.id)
        manager.toggleClipPinned(a.id)
        store.settle()

        val entries = manager.getHistoryEntries()
        assertEquals(listOf("a", "c"), entries.map { it.text })
        assertTrue(entries[0].isPinned)
        assertTrue(entries[1].isSensitive)
        assertFalse(entries[1].isPinned)
        assertEquals(listOf(a.id, c.id), entries.map { it.id })

        // Without force a pinned entry stays.
        manager.removeEntry(a.id)
        store.settle()
        assertEquals(listOf("a", "c"), texts(manager))
        manager.removeEntry(a.id, force = true)
        store.settle()
        assertEquals(listOf("c"), texts(manager))
        assertEquals(listOf(c.id), store.rows().map { it.id })
    }

    @Test
    fun sensitiveUpgradeOfTheSameTextSurvivesARestart() {
        val manager = manager()
        copy(manager, "same-text")
        store.settle()
        manager.toggleClipPinned(manager.getHistoryEntries().single().id)
        store.settle()
        store.now += 1_000L
        copy(manager, "same-text", sensitive = true)
        store.now += 1_000L
        copy(manager, "same-text")
        store.settle()

        val restarted = manager(store.restart())
        store.settle()
        val entry = restarted.getHistoryEntries().single()
        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertEquals(store.now, entry.timeStamp)
        restarted.toggleClipPinned(entry.id)
        store.settle()
        assertFalse(restarted.getHistoryEntries().single().isPinned)
    }

    @Test
    fun expiryAndLockAreCheckedWhenADelayedPublicationArrives() {
        val manager = manager()
        store.settle()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, manager)
        activity.setContentView(view)
        layout(view)
        copy(manager, "late-secret", sensitive = true)
        store.worker.runAll()

        // Publication is still queued on the main thread when the device locks.
        accessible = false
        manager.refreshAccessState()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(visibleTexts(view).contains(context.getString(R.string.clipboard_sensitive_preview)))
        assertTrue(visibleTexts(view).contains(context.getString(R.string.clipboard_locked_state)))
        assertEquals(0, manager.getHistorySize())

        accessible = true
        manager.refreshAccessState()
        val entry = manager.getHistoryEntries().single()
        val connection = RecordingInputConnection(context)

        // Past the sensitive retention, before the scheduled cleanup ran.
        store.now += 30 * 60_000L
        assertEquals(0, manager.getHistorySize())
        manager.pasteEntry(entry.id, connection)
        assertTrue(connection.committedTexts.isEmpty())
        assertEquals(1, store.rows().size)

        // The cleanup scheduled for the expiry time removes the row.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(31))
        store.settle()
        assertTrue(store.rows().isEmpty())
        assertTrue(visibleTexts(view).contains(context.getString(R.string.clipboard_empty_state)))
    }

    @Test
    fun startupFailureDropsWaitingRequestsAndRetriesOnlyWhenOpenedAgain() {
        store.store("expired-secret", store.now - 40 * 60_000L, sensitive = true)
        store.store("stored", store.now - 1_000L)
        store.database.writableDatabase.execSQL("ALTER TABLE CLIPBOARD RENAME COLUMN TEXT TO UNREADABLE_TEXT")
        val manager = manager()
        copy(manager, "copied-during-failed-start")
        store.settle()

        assertEquals(ClipboardHistoryManager.Status.ERROR, manager.uiState().status)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, manager)
        // Reads are pure: they neither retry nor queue work.
        repeat(3) { manager.getHistorySize(); manager.uiState() }
        assertEquals(0, store.worker.pending)
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("copied-during-failed-start") })

        store.database.writableDatabase.execSQL("ALTER TABLE CLIPBOARD RENAME COLUMN UNREADABLE_TEXT TO TEXT")
        activity.setContentView(view) // Opening the history is the explicit retry.
        assertTrue(store.worker.pending > 0)
        store.settle()
        layout(view)

        assertEquals(ClipboardHistoryManager.Status.READY, manager.uiState().status)
        assertEquals(listOf("stored"), texts(manager))
        assertEquals(listOf("stored"), store.rows().map { it.text })
        assertTrue(visibleTexts(view).contains("stored"))
    }

    @Test
    fun restartLoadsTheSameEntriesAndKeepsDeletionsAndExpiry() {
        val manager = manager()
        store.settle()
        store.history.addClip("keep", store.now, false, retention)
        store.history.addClip("delete-me", store.now - 1, false, retention)
        store.history.addClip("short-secret", store.now - 2, true, retention)
        store.settle()
        val before = manager.getHistoryEntries()
        manager.removeEntry(before.single { it.text == "delete-me" }.id)
        store.settle()
        manager.onDestroy()

        store.now += 31 * 60_000L
        val restarted = manager(store.restart())
        store.settle()

        assertEquals(listOf(before.single { it.text == "keep" }), restarted.getHistoryEntries())
        assertEquals(listOf("keep"), store.rows().map { it.text })
    }

    @Test
    fun largeStoredTextsLoadCompletelyWithUnicodeAndNul() {
        val big = "\u0000" + "x😀ä".repeat(600_000) + "\u0000end"
        val bigId = store.store(big, store.now, pinned = true)
        store.store("small \u0000 text ✓", store.now - 1)
        store.store(null, store.now - 2, sensitive = true)

        val manager = manager()
        store.settle()
        assertEquals(ClipboardHistoryManager.Status.READY, manager.uiState().status)
        val entries = manager.getHistoryEntries()
        assertEquals(listOf(bigId), entries.take(1).map { it.id })
        assertEquals(big, entries[0].text)
        assertEquals("small \u0000 text ✓", entries[1].text)
        assertEquals(2, entries.size)
    }

    @Test
    fun chunksSmallerThanOneCharacterReassembleTheExactText() {
        val text = " x😀ä€".repeat(40) + " "
        store.store(text, store.now)
        store.store("ok", store.now - 1)

        val manager = manager(store.restart(chunkBytes = 5))
        store.settle()
        assertEquals(listOf(text, "ok"), texts(manager))
    }

    @Test
    fun largeCopiesAreStoredWithoutTruncation() {
        val manager = manager()
        store.settle()
        val big = "€".repeat(1_500_000) + "😀"
        store.history.addClip(big, store.now, false, retention)
        store.settle()

        val restarted = manager(store.restart())
        store.settle()
        assertEquals(big, restarted.getHistoryEntries().single().text)
        assertEquals(big, manager.getHistoryEntries().single().text)
    }

    private fun layout(view: View) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun visibleTexts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
            else -> emptyList()
        }
    }

    private class RecordingInputConnection(context: Context) : BaseInputConnection(View(context), true) {
        val committedTexts = mutableListOf<String>()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committedTexts += text?.toString().orEmpty()
            return true
        }
    }
}
