package rocks.pkb.plektra.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

/** A copy whose content can't be read is skipped, never the keyboard's end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardUnreadableClipTest {
    private lateinit var context: Context
    private lateinit var store: ClipboardTestStore
    private lateinit var manager: ClipboardHistoryManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.setClipboardHistoryEnabled(context, true)
        SettingsManager.setClipboardRetentionTime(context, 0)
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        store = ClipboardTestStore(context, "clipboard-unreadable-test.db")
        manager = ClipboardHistoryManager(context, object : ClipboardHistoryAccessPolicy {
            override fun isHistoryAccessible() = true
        }, store.history) { store.now }
        manager.onCreate()
        store.settle()
    }

    @After
    fun tearDown() {
        manager.onDestroy()
        store.close()
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
    }

    private fun useClipboard(clipboard: ClipboardManager) {
        ClipboardHistoryManager::class.java.getDeclaredField("clipboardManager").apply { isAccessible = true }
            .set(manager, clipboard)
    }

    @Test
    fun aClipboardThatFailsToReadIsSkipped() {
        val failing = Mockito.mock(ClipboardManager::class.java)
        Mockito.`when`(failing.primaryClip).thenThrow(SecurityException("denied"))
        useClipboard(failing)
        manager.onPrimaryClipChanged()
        store.settle()
        assertEquals(emptyList<ClipboardTestStore.Row>(), store.rows())
    }

    @Test
    fun aClipWhoseContentFailsIsSkippedAndTheNextIsKept() {
        val real = context.getSystemService(ClipboardManager::class.java)
        val unreadable = Mockito.mock(ClipData.Item::class.java)
        Mockito.`when`(unreadable.coerceToText(Mockito.any())).thenThrow(SecurityException("no permission"))
        val clip = Mockito.spy(ClipData.newPlainText("label", "x"))
        Mockito.doReturn(unreadable).`when`(clip).getItemAt(0)
        val failing = Mockito.mock(ClipboardManager::class.java)
        Mockito.`when`(failing.primaryClip).thenReturn(clip)
        useClipboard(failing)
        manager.onPrimaryClipChanged()

        useClipboard(real)
        real.setPrimaryClip(ClipData.newPlainText("label", "kept"))
        store.settle()
        assertEquals(listOf("kept"), store.rows().map { it.text })
    }
}
