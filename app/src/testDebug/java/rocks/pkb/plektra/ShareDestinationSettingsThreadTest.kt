package rocks.pkb.plektra

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import rocks.pkb.plektra.share.ShareSettings

/** Share folder settings with a slow DocumentsProvider (review rounds 2 and 3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareDestinationSettingsThreadTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = RecordingDocumentsProvider()

    @Before
    fun setUp() {
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        ShareSettings.localPreferences(context).edit().clear().commit()
        ShareSettings.setGalleryAlbumEnabled(context, false)
    }

    @After
    fun tearDown() {
        provider.gates.values.forEach { it.countDown() }
    }

    private fun choose(folderId: String): Uri {
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, folderId)
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        ShareSettings.localPreferences(context).edit()
            .putString(ShareSettings.KEY_SAVE_FOLDER_URI, tree.toString())
            .commit()
        return tree
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun folderLabelIsLoadedOffTheMainThread() {
        choose("a")
        compose.setContent { Column { ShareDestinationSettings() } }
        compose.waitUntil(timeoutMillis = 5_000) { shown("Folder a") }

        assertTrue(provider.queryThreads.isNotEmpty())
        assertFalse(
            "Folder metadata was queried on the main thread",
            provider.queryThreads.any { it == Looper.getMainLooper().thread }
        )
    }

    @Test
    fun actionsDoNotWaitForTheFolderLabel() {
        provider.gates["a"] = CountDownLatch(1)
        choose("a")
        compose.setContent { Column { ShareDestinationSettings() } }

        compose.onNodeWithText(context.getString(R.string.share_save_folder_title)).performClick()

        compose.waitUntil(timeoutMillis = 5_000) { shown(context.getString(R.string.share_save_folder_use_downloads)) }
        assertFalse(shown("Folder a"))
    }

    @Test
    fun newestFolderWinsOverAnOlderSlowLabel() {
        provider.gates["a"] = CountDownLatch(1)
        choose("a")
        compose.setContent { Column { ShareDestinationSettings() } }
        compose.waitUntil(timeoutMillis = 5_000) { provider.started.contains("a") }

        compose.runOnIdle { choose("b") }
        compose.waitUntil(timeoutMillis = 5_000) { shown("Folder b") }
        provider.gates.getValue("a").countDown()
        compose.waitUntil(timeoutMillis = 5_000) { provider.finished.contains("a") }
        compose.waitForIdle()

        assertTrue(shown("Folder b"))
        assertFalse("A late answer for the previous folder replaced the newest", shown("Folder a"))
    }

    @Test
    fun downloadsAndANewChoiceNeverWaitForTheOldFolder() {
        provider.gates["a"] = CountDownLatch(1)
        choose("a")
        compose.setContent { Column { ShareDestinationSettings() } }
        compose.waitUntil(timeoutMillis = 5_000) { provider.started.contains("a") }

        compose.onNodeWithText(context.getString(R.string.share_save_folder_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.share_save_folder_use_downloads)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { ShareSettings.storedFolderUri(context) == null }
        compose.waitUntil(timeoutMillis = 5_000) { shown(context.getString(R.string.share_folder_downloads)) }
        assertFalse("The slow folder answered meanwhile", provider.finished.contains("a"))

        // Choosing another folder only reads the stored URI as well: with every query of the old
        // folder blocked, the choice still completes at once.
        choose("a")
        val b = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "b")
        val selection = Thread { ShareSettings.selectSaveFolder(context, b) }.apply { start() }
        selection.join(2_000)
        assertFalse("Choosing a folder waited for the old folder's provider", selection.isAlive)
        assertTrue(ShareSettings.storedFolderUri(context) == b)
    }

    class RecordingDocumentsProvider : ContentProvider() {
        val queryThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        val gates: MutableMap<String, CountDownLatch> = Collections.synchronizedMap(mutableMapOf())
        val started: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val finished: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        override fun onCreate() = true

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            queryThreads += Thread.currentThread()
            val id = DocumentsContract.getDocumentId(uri)
            started += id
            gates[id]?.await(10, TimeUnit.SECONDS)
            finished += id
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                .apply { addRow(arrayOf("Folder $id")) }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "org.example.slowdocuments"
    }
}
