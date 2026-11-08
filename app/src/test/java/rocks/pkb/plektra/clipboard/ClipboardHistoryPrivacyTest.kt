package rocks.pkb.plektra.clipboard

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.FrameLayout
import android.widget.TextView
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ui.ClipboardHistoryView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardHistoryPrivacyTest {
    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var accessPolicy: MutableAccessPolicy
    private lateinit var historyManager: ClipboardHistoryManager
    private lateinit var store: ClipboardTestStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.setClipboardHistoryEnabled(context, true)
        SettingsManager.setClipboardRetentionTime(context, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(context, 30)
        clipboardManager = context.getSystemService(ClipboardManager::class.java)
        clipboardManager.clearPrimaryClip()
        accessPolicy = MutableAccessPolicy(accessible = true)
        store = ClipboardTestStore(context, "clipboard-privacy-test.db")
        historyManager = ClipboardHistoryManager(context, accessPolicy, store.history) { store.now }
        historyManager.onCreate()
        store.settle()
    }

    @After
    fun tearDown() {
        accessPolicy.accessible = true
        historyManager.onDestroy()
        store.close()
        clipboardManager.clearPrimaryClip()
        SettingsManager.setClipboardHistoryEnabled(context, true)
        SettingsManager.setClipboardRetentionTime(context, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(context, 30)
    }

    @Test
    fun systemPolicyFailsClosedWhileLockedOrScreenIsOff() {
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val policy = SystemClipboardHistoryAccessPolicy(context)

        shadowOf(powerManager).setIsInteractive(true)
        shadowOf(keyguardManager).setIsDeviceLocked(false)
        assertTrue(policy.isHistoryAccessible())

        shadowOf(keyguardManager).setIsDeviceLocked(true)
        assertFalse(policy.isHistoryAccessible())

        shadowOf(keyguardManager).setIsDeviceLocked(false)
        shadowOf(powerManager).setIsInteractive(false)
        assertFalse(policy.isHistoryAccessible())
    }

    @Test
    fun clipboardChangesWhileLockedAreNotCaptured() {
        copyToSystemClipboard("visible-secret")
        historyManager.onPrimaryClipChanged()
        store.settle()
        assertEquals(1, historyManager.getHistorySize())

        setAccessible(false)
        copyToSystemClipboard("locked-secret")
        historyManager.onPrimaryClipChanged()
        store.settle()

        assertEquals(0, historyManager.getHistorySize())
        assertTrue(historyManager.getHistoryEntries().isEmpty())

        setAccessible(true)
        assertEquals(listOf("visible-secret"), historyManager.getHistoryEntries().map { it.text })
        assertEquals(listOf("visible-secret"), store.rows().map { it.text })
    }

    @Test
    fun sensitiveClipboardRemainsPasteableWithoutExposingItsPreview() {
        copyToSystemClipboard("ordinary-text")
        clipboardManager.setPrimaryClip(ClipData.newPlainText("credential", "test-secret").apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        })
        // ShadowClipboardManager uses uptime (100 ms) for the timestamp; Android
        // uses wall-clock time. Match the real service before exercising retention.
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            clipboardManager.primaryClip!!.description, "setTimestamp",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, store.now))
        historyManager.onPrimaryClipChanged()
        store.settle()
        assertTrue(clipboardManager.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals(2, historyManager.getHistorySize())
        val entry = requireNotNull(historyManager.getHistoryEntries().firstOrNull { it.isSensitive })
        assertEquals("test-secret", entry.text)
        val connection = RecordingInputConnection(context)
        historyManager.pasteEntry(entry.id, connection)
        assertEquals(listOf("test-secret"), connection.committedTexts)
        historyManager.toggleClipPinned(entry.id)
        store.settle()
        assertFalse(historyManager.getHistoryEntryContent(entry.id)!!.isPinned)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, historyManager)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(visibleTexts(view).contains("test-secret"))
        assertFalse(visibleContentDescriptions(view).contains("test-secret"))
        assertTrue(visibleTexts(view).contains(context.getString(R.string.clipboard_sensitive_preview)))
        setAccessible(false)
        historyManager.pasteEntry(entry.id, connection)
        assertEquals(listOf("test-secret"), connection.committedTexts)
    }

    @Test
    fun separateTimersExpireSensitiveEntriesEvenWithUnlimitedNormalHistory() {
        SettingsManager.setClipboardRetentionTime(context, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(context, 120)
        val history = store.history
        val retention = ClipboardRetention(0, 120)
        val now = store.now
        history.addClip("old-normal", now - 90 * 24 * 60 * 60_000L, false, retention)
        history.addClip("expired-secret", now - 31 * 60_000L, true, retention)
        history.addClip("fresh-secret", now - 5 * 60_000L, true, retention)
        store.settle()
        val expired = requireNotNull(historyManager.getHistoryEntries().firstOrNull { it.text == "expired-secret" })
        SettingsManager.setSensitiveClipboardRetentionTime(context, 30)
        val connection = RecordingInputConnection(context)
        // The shorter retention applies to reads and paste before any cleanup ran.
        historyManager.pasteEntry(expired.id, connection)
        assertTrue(connection.committedTexts.isEmpty())
        setAccessible(false)
        // Cleanup must still run while the screen is locked.
        historyManager.onHistoryOpened()
        store.settle()
        setAccessible(true)
        assertEquals(setOf("old-normal", "fresh-secret"), historyManager.getHistoryEntries().map { it.text }.toSet())
        assertEquals(setOf("old-normal", "fresh-secret"), store.rows().map { it.text }.toSet())
        SettingsManager.setClipboardRetentionTime(context, 24 * 60)
        assertEquals(1, historyManager.getHistorySize())
    }

    @Test
    fun duplicateCopiesCannotDowngradeSensitivity() {
        val retention = ClipboardRetention(0, 30)
        val now = store.now
        store.history.addClip("duplicate-secret", now, false, retention)
        store.settle()
        historyManager.toggleClipPinned(historyManager.getHistoryEntries().single().id)
        store.settle()
        assertTrue(historyManager.getHistoryEntries().single().isPinned)
        store.history.addClip("duplicate-secret", now + 1, true, retention)
        store.history.addClip("duplicate-secret", now + 2, false, retention)
        store.settle()
        val entry = historyManager.getHistoryEntries().single()
        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertEquals(listOf(ClipboardTestStore.Row(entry.id, "duplicate-secret", pinned = false, sensitive = true)), store.rows())
    }

    @Test
    fun databaseUpgradePreservesExistingClipsAndDefaultsThemToNormal() {
        android.database.sqlite.SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE CLIPBOARD (ID INTEGER PRIMARY KEY, TIMESTAMP INTEGER NOT NULL, PINNED TINYINT NOT NULL, TEXT TEXT)")
            db.execSQL("INSERT INTO CLIPBOARD VALUES (1, 123, 0, 'existing-text')")
            ClipboardDatabase.getInstance(context).onUpgrade(db, 1, 2)
            db.rawQuery("SELECT TEXT, SENSITIVE FROM CLIPBOARD", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing-text", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test
    fun retentionDefaultsAndSensitiveBoundsAreIndependent() {
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().remove("clipboard_retention_time").remove("sensitive_clipboard_retention_time").commit()
        assertEquals(0L, SettingsManager.getClipboardRetentionTime(context))
        assertEquals(30L, SettingsManager.getSensitiveClipboardRetentionTime(context))
        SettingsManager.setSensitiveClipboardRetentionTime(context, 0)
        assertEquals(1L, SettingsManager.getSensitiveClipboardRetentionTime(context))
        SettingsManager.setSensitiveClipboardRetentionTime(context, 999)
        assertEquals(120L, SettingsManager.getSensitiveClipboardRetentionTime(context))
    }

    @Test
    fun allHistoryActionsAreBlockedWhileLocked() {
        copyToSystemClipboard("protected-secret")
        historyManager.onPrimaryClipChanged()
        store.settle()
        val entry = historyManager.getHistoryEntries().single()
        val inputConnection = RecordingInputConnection(context)

        setAccessible(false)
        historyManager.toggleClipPinned(entry.id)
        historyManager.removeEntry(entry.id, force = true)
        historyManager.clearHistory()
        historyManager.pasteText(entry.text, inputConnection)
        historyManager.pasteEntry(entry.id, inputConnection)
        store.settle()

        assertNull(historyManager.getHistoryEntryContent(entry.id))
        assertTrue(inputConnection.committedTexts.isEmpty())

        setAccessible(true)
        assertEquals(listOf(entry), historyManager.getHistoryEntries())
    }

    @Test
    fun accessListenersReceiveOnlyRealStateTransitions() {
        val states = mutableListOf<Boolean>()
        historyManager.addAccessStateListener(states::add)

        setAccessible(false)
        historyManager.refreshAccessState()
        setAccessible(true)

        assertEquals(listOf(false, true), states)
    }

    @Test
    fun screenOffBroadcastFailsClosedBeforeSystemStateCatchesUp() {
        val states = mutableListOf<Boolean>()
        historyManager.addAccessStateListener(states::add)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(historyManager.isHistoryAccessible())
        assertEquals(listOf(false), states)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(historyManager.isHistoryAccessible())
        assertEquals(listOf(false, true), states)
    }

    @Test
    fun inlineHistoryReplacesSensitiveRowsWithLockedMessage() {
        copyToSystemClipboard("view-secret")
        historyManager.onPrimaryClipChanged()
        store.settle()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, historyManager)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(visibleTexts(view).contains("view-secret"))

        setAccessible(false)

        val lockedTexts = visibleTexts(view)
        assertFalse(lockedTexts.contains("view-secret"))
        assertTrue(lockedTexts.contains(context.getString(R.string.clipboard_locked_state)))
        assertFalse(visibleContentDescriptions(view).contains("view-secret"))
    }

    @Test
    fun popupHistoryReplacesSensitiveRowsImmediatelyWhenLocked() {
        copyToSystemClipboard("popup-secret")
        historyManager.onPrimaryClipChanged()
        store.settle()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        activity.setContentView(FrameLayout(activity))
        val popup = ClipboardHistoryPopupView(activity, historyManager)

        try {
            popup.show()
            shadowOf(Looper.getMainLooper()).idle()
            val contentView = popup.javaClass.getDeclaredField("contentView").run {
                isAccessible = true
                get(popup) as View
            }
            assertTrue(visibleTexts(contentView).contains("popup-secret"))

            setAccessible(false)

            val lockedTexts = visibleTexts(contentView)
            assertFalse(lockedTexts.contains("popup-secret"))
            assertFalse(lockedTexts.contains(context.getString(R.string.clipboard_clear_all)))
            assertTrue(lockedTexts.contains(context.getString(R.string.clipboard_locked_state)))
            assertFalse(visibleContentDescriptions(contentView).contains("popup-secret"))
        } finally {
            popup.dismiss()
        }
    }

    private fun copyToSystemClipboard(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("test", text))
    }

    private fun setAccessible(accessible: Boolean) {
        accessPolicy.accessible = accessible
        historyManager.refreshAccessState()
    }

    private fun visibleTexts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
            else -> emptyList()
        }
    }

    private fun visibleContentDescriptions(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own = view.contentDescription?.toString()?.let(::listOf).orEmpty()
        return if (view is ViewGroup) {
            own + (0 until view.childCount).flatMap { visibleContentDescriptions(view.getChildAt(it)) }
        } else {
            own
        }
    }

    private class MutableAccessPolicy(var accessible: Boolean) : ClipboardHistoryAccessPolicy {
        override fun isHistoryAccessible(): Boolean = accessible
    }

    private class RecordingInputConnection(context: Context) :
        BaseInputConnection(View(context), true) {
        val committedTexts = mutableListOf<String>()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committedTexts += text?.toString().orEmpty()
            return true
        }
    }
}
