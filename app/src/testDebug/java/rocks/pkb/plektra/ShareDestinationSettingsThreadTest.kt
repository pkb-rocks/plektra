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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import rocks.pkb.plektra.share.ShareSettings

/** 7. Folder metadata comes from another app's provider and must never be queried on main. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareDestinationSettingsThreadTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun folderLabelIsLoadedOffTheMainThread() {
        val provider = RecordingDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        ShareSettings.localPreferences(context).edit()
            .putString(ShareSettings.KEY_SAVE_FOLDER_URI, tree.toString())
            .commit()

        compose.setContent { ShareDestinationSettings() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Synthetic folder").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(provider.queryThreads.isNotEmpty())
        assertFalse(
            "Folder metadata was queried on the main thread",
            provider.queryThreads.any { it == Looper.getMainLooper().thread }
        )
    }

    class RecordingDocumentsProvider : ContentProvider() {
        val queryThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            queryThreads += Thread.currentThread()
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                .apply { addRow(arrayOf("Synthetic folder")) }
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
