package rocks.pkb.plektra.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsActivity
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ui.ClipboardHistoryView
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android SQLite, clipboard callbacks, main-thread publication and editor insertion. */
@RunWith(AndroidJUnit4::class)
class ClipboardHistoryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun largeHistoryAndQueuedCopiesSurviveFailuresAndDatabaseReopening() {
        val name = "clipboard-device-${UUID.randomUUID()}.db"
        val database = ClipboardDatabase(context, name)
        val dao = ClipboardDao(database)
        val worker = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val history = ClipboardHistory(dao, worker, { main.post(it) })
        val snapshots = LinkedBlockingQueue<ClipboardHistory.Snapshot>()
        val failures = LinkedBlockingQueue<ClipboardHistory.Operation>()
        history.addListener { snapshots.add(it) }
        history.addFailureListener { failures.add(it) }
        val retention = ClipboardRetention(0, 30)
        val now = System.currentTimeMillis()
        val large = "\u0000" + "ä😀".repeat(500_000) + "\u0000end"
        try {
            dao.insert(now, false, large, false)
            worker.execute { check(gate.await(10, TimeUnit.SECONDS)) }
            history.start(retention)
            history.addClip("queued-on-start", now, false, retention)
            assertEquals(ClipboardHistory.LoadState.LOADING, history.snapshot.loadState)
            gate.countDown()
            val loaded = awaitSnapshot(snapshots) { it.entries.size == 2 }
            assertEquals(large, loaded.entries.single { it.text != "queued-on-start" }.text)
            val queued = loaded.entries.single { it.text == "queued-on-start" }

            worker.submit {
                database.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_delete BEFORE DELETE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'blocked'); END"
                )
            }.get(10, TimeUnit.SECONDS)
            history.delete(queued.id, true, retention)
            assertEquals(ClipboardHistory.Operation.DELETE, failures.poll(10, TimeUnit.SECONDS))
            assertTrue(history.snapshot.entries.any { it.id == queued.id })

            worker.submit { database.writableDatabase.execSQL("DROP TRIGGER reject_delete") }
                .get(10, TimeUnit.SECONDS)
            history.delete(queued.id, true, retention)
            val remaining = awaitSnapshot(snapshots) { it.entries.size == 1 }
            history.togglePinned(remaining.entries.single().id, retention)
            awaitSnapshot(snapshots) { it.entries.singleOrNull()?.isPinned == true }
            worker.submit {
                database.close()
                val reopened = ClipboardDatabase(context, name)
                try {
                    val entry = ClipboardDao(reopened).loadAll().single()
                    assertEquals(large, entry.text)
                    assertTrue(entry.isPinned)
                } finally {
                    reopened.close()
                }
            }.get(10, TimeUnit.SECONDS)
        } finally {
            gate.countDown()
            worker.shutdown()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun systemClipboardIsMaskedAndPastesIntoTheEditorWhileLockBlocksAccess() {
        val name = "clipboard-device-${UUID.randomUUID()}.db"
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): android.content.SharedPreferences {
                val isolatedName = "$name-$key"
                preferenceNames += isolatedName
                return super.getSharedPreferences(isolatedName, mode)
            }
        }
        SettingsManager.setClipboardRetentionTime(isolated, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(isolated, 30)
        SettingsManager.setClipboardHistoryEnabled(isolated, true)
        val database = ClipboardDatabase(isolated, name)
        val worker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val history = ClipboardHistory(ClipboardDao(database), worker, { main.post(it) })
        val snapshots = LinkedBlockingQueue<ClipboardHistory.Snapshot>()
        history.addListener { snapshots.add(it) }
        val accessible = AtomicBoolean(true)
        val manager = ClipboardHistoryManager(isolated, { accessible.get() }, history)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var originalClip: ClipData? = null
        var managerStarted = false
        // Only a focused window is needed; the notes editor would persist its own state into the user's notes.
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            lateinit var editor: EditText
            scenario.onActivity { activity ->
                originalClip = clipboard.primaryClip
                clipboard.setPrimaryClip(ClipData.newPlainText("device-test", "pixel-secret").apply {
                    description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                })
                manager.onCreate()
                managerStarted = true
                editor = EditText(activity).apply { showSoftInputOnFocus = false }
                val view = ClipboardHistoryView(activity, manager)
                activity.setContentView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(editor)
                    addView(view, LinearLayout.LayoutParams(-1, 600))
                })
                editor.requestFocus()
                view.setInputConnection(editor.onCreateInputConnection(EditorInfo()))
            }
            val snapshot = awaitSnapshot(snapshots) { it.entries.any { entry -> entry.text == "pixel-secret" } }
            val secret = snapshot.entries.single { it.text == "pixel-secret" }
            assertTrue(secret.isSensitive)
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.clipboard_sensitive_preview))), 10_000))
            assertFalse(device.hasObject(By.text("pixel-secret")))

            scenario.onActivity {
                manager.pasteEntry(secret.id, editor.onCreateInputConnection(EditorInfo()))
                assertEquals("pixel-secret", editor.text.toString())
                editor.setText("")
                accessible.set(false)
                manager.refreshAccessState()
                manager.pasteEntry(secret.id, editor.onCreateInputConnection(EditorInfo()))
                assertEquals("", editor.text.toString())
            }
            assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.clipboard_locked_state))), 10_000))
            scenario.onActivity {
                accessible.set(true)
                manager.refreshAccessState()
                clipboard.setPrimaryClip(ClipData.newPlainText("device-test", "pixel-normal"))
            }
            val copied = awaitSnapshot(snapshots) { it.entries.any { entry -> entry.text == "pixel-normal" } }
                .entries.single { it.text == "pixel-normal" }
            scenario.onActivity { manager.toggleClipPinned(copied.id) }
            awaitSnapshot(snapshots) { it.entries.any { entry -> entry.id == copied.id && entry.isPinned } }
            scenario.onActivity { manager.removeEntry(copied.id, force = true) }
            awaitSnapshot(snapshots) { it.entries.none { entry -> entry.id == copied.id } }
        } finally {
            scenario.onActivity {
                if (managerStarted) manager.onDestroy()
                originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
            scenario.close()
            worker.shutdown()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            database.close()
            context.deleteDatabase(name)
            preferenceNames.forEach(context::deleteSharedPreferences)
        }
    }

    private fun awaitSnapshot(
        snapshots: LinkedBlockingQueue<ClipboardHistory.Snapshot>,
        predicate: (ClipboardHistory.Snapshot) -> Boolean
    ): ClipboardHistory.Snapshot {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            val snapshot = if (remaining > 0) snapshots.poll(remaining, TimeUnit.MILLISECONDS) else null
            checkNotNull(snapshot) { "Clipboard state did not arrive before the deadline" }
            check(snapshot.loadState != ClipboardHistory.LoadState.ERROR) { "History failed to load" }
            if (predicate(snapshot)) return snapshot
        }
    }
}
