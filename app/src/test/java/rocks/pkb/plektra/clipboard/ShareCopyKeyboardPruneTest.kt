package rocks.pkb.plektra.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.share.ShareClipboardCache

/** Round 3, finding 6: a clipboard the keyboard cannot read is unknown, not empty. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareCopyKeyboardPruneTest {
    private lateinit var context: Context
    private lateinit var store: ClipboardTestStore
    private lateinit var manager: ClipboardHistoryManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
        ShareClipboardCache.root(context).deleteRecursively()
        SettingsManager.setClipboardHistoryEnabled(context, true)
        store = ClipboardTestStore(context, "share-copy-prune-test.db")
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
    }

    private fun placedCopy(): File {
        val file = ShareClipboardCache.newCopy(context, "copy.png", "image/png").apply { writeText("synthetic") }
        val uri = FileProvider.getUriForFile(context, ShareClipboardCache.authority(context), file)
        ShareClipboardCache.place(context, file) { ClipData.newRawUri("", uri) }
        return file
    }

    private fun keyboardSees(clip: ClipData?) {
        val clipboard = Mockito.mock(ClipboardManager::class.java)
        Mockito.`when`(clipboard.primaryClip).thenReturn(clip)
        ClipboardHistoryManager::class.java.getDeclaredField("clipboardManager").apply { isAccessible = true }
            .set(manager, clipboard)
        manager.onPrimaryClipChanged()
    }

    @Test
    fun knownReplacementDeletesTheCopy() {
        val copy = placedCopy()
        keyboardSees(ClipData.newPlainText("label", "other text"))
        val deadline = System.currentTimeMillis() + 5_000
        while (copy.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(copy.exists())
    }

    @Test
    fun unreadableClipboardKeepsTheCurrentCopy() {
        val copy = placedCopy()
        keyboardSees(null) // No read access or a locked device.
        // The known-replacement case above finishes well within this time.
        Thread.sleep(500)
        assertTrue("An unknown clipboard must not delete the current copy", copy.exists())
    }
}
