package rocks.pkb.plektra.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.xmlpull.v1.XmlPullParser
import rocks.pkb.plektra.R
import rocks.pkb.plektra.backup.BackupPreferenceContract

/** Regressions from the review of PR #245. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareReviewRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        // AndroidX caches path strategies across Robolectric methods, whose cache paths differ.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
        ShareClipboardCache.root(context).deleteRecursively()
        ShareTargetJournal.root(context).deleteRecursively()
        ShareTargetJournal.forgetActiveForTest()
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("png", "image/png")
        }
    }

    // 1. A "<userId>@" prefix must not hide Plektra's own providers.
    @Test
    fun ownProviderWithUserPrefixIsRejected() {
        val own = Uri.parse("content://0@${context.packageName}.fileprovider/sbom/x.json")
        assertFalse(ShareIntentParser.isReadableForeignContentUri(context, own))
        val ownOtherUser = Uri.parse("content://10@${context.packageName}.fileprovider/share_clipboard/x/y.png")
        assertFalse(ShareIntentParser.isReadableForeignContentUri(context, ownOtherUser))
        // Work-profile shares of other apps carry the prefix legitimately.
        val foreign = Uri.parse("content://10@com.android.providers.media.documents/document/image%3A1")
        assertTrue(ShareIntentParser.isReadableForeignContentUri(context, foreign))
    }

    // 2. A copy that is still being written is never pruned, however long it takes.
    @Test
    fun copyInProgressSurvivesAPruneAfterAnyDelay() = runBlocking {
        val source = Uri.parse("content://org.example.files/large.bin")
        var firstRead = true
        val input = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!firstRead) return -1
                firstRead = false
                val copy = ShareClipboardCache.root(context).listFiles()!!.single()
                copy.setLastModified(System.currentTimeMillis() - ShareClipboardCache.MAX_AGE_MILLIS - 1)
                ShareClipboardCache.prune(context, clipKnown = true) { null }
                buffer[offset] = 42
                return 1
            }
        }
        shadowOf(context.contentResolver).registerInputStream(source, input)

        val result = ShareOperations.copyToClipboard(
            context,
            SharedItem.Stream(0, source, "application/octet-stream", "large.bin", null)
        )

        assertEquals(ShareItemResult.Copied, result)
        val clipUri = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).uri
        val copy = File(File(ShareClipboardCache.root(context), clipUri.pathSegments[1]), "large.bin")
        assertTrue("Reported copied, but the backing file was pruned", copy.exists())
        assertEquals(1L, copy.length())
    }

    // 3. A replaced copy goes at the next prune, without a grace period, and every copy has a deadline.
    @Test
    fun replacedCopyIsDeletedImmediatelyAndEveryCopyExpires() {
        val first = placedCopy("first.png")
        val second = placedCopy("second.png")
        assertFalse("A replaced copy must not wait for another clipboard event", first.exists())
        assertTrue(second.exists())

        val writing = ShareClipboardCache.newCopy(context, "writing.png", "image/png").apply { writeText("w") }
        second.parentFile!!.setLastModified(System.currentTimeMillis() - ShareClipboardCache.MAX_AGE_MILLIS)
        // A background run cannot read the clipboard; the deadline still applies.
        ShareClipboardCache.prune(context, clipKnown = false) { null }
        assertFalse("A copy must not outlive its deadline", second.exists())
        assertTrue(writing.exists())
        ShareClipboardCache.release(writing)
    }

    @Test
    fun unreadableClipboardKeepsCopiesWithinTheirDeadline() {
        val current = placedCopy("current.png")
        ShareClipboardCache.prune(context, clipKnown = false) { null }
        assertTrue(current.exists())
    }

    private fun placedCopy(name: String): File {
        val file = ShareClipboardCache.newCopy(context, name, "image/png").apply { writeText(name) }
        val uri = FileProvider.getUriForFile(context, ShareClipboardCache.authority(context), file)
        ShareClipboardCache.place(context, file) { ClipData.newRawUri("", uri) }
        return file
    }

    // 4. Targets abandoned by a killed process or a failed delete are removed later.
    @Test
    fun documentAbandonedByAKilledProcessIsDeletedOnRecovery() {
        val documents = installProvider("org.example.documents", FakeDocumentsProvider())
        val document = DocumentsContract.buildDocumentUri("org.example.documents", "partial.bin")
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document, document)
        ShareTargetJournal.forgetActiveForTest() // The process ended before finally ran.

        ShareTargetJournal.recover(context)

        assertEquals(listOf("partial.bin"), documents.deleted)
        assertTrue(ShareTargetJournal.root(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun recoveryDeletesOnlyStillPendingMediaStoreRows() {
        val media = installProvider("media", FakeMediaStore())
        val pending = Uri.parse("content://media/external_primary/downloads/1")
        val published = Uri.parse("content://media/external_primary/downloads/2")
        media.pending += pending.lastPathSegment!!
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.MediaStore, pending)
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.MediaStore, published)
        ShareTargetJournal.forgetActiveForTest()

        ShareTargetJournal.recover(context)

        assertEquals(listOf("1"), media.deleted)
        assertTrue(media.lastQueryMatchedPending)
        assertTrue(ShareTargetJournal.root(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedDeleteIsRetriedByRecoveryAndActiveTargetsAreLeftAlone() = runBlocking {
        val documents = installProvider("org.example.documents", FakeDocumentsProvider().apply { failDeletes = true })
        val tree = DocumentsContract.buildTreeDocumentUri("org.example.documents", "root")
        context.contentResolver.takePersistableUriPermission(
            tree,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val source = Uri.parse("content://org.example.files/broken.bin")
        shadowOf(context.contentResolver).registerInputStream(source, object : InputStream() {
            override fun read(): Int = throw IOException("synthetic source failure")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("synthetic source failure")
        })

        val result = ShareOperations.saveToFolder(
            context,
            SharedItem.Stream(0, source, "application/octet-stream", "broken.bin", null),
            tree,
            "Folder"
        )

        assertEquals(ShareItemResult.Failed(ShareFailure.SourceUnreadable), result)
        assertTrue("The failed delete must stay recorded", documents.deleted.isEmpty())
        assertEquals(1, ShareTargetJournal.root(context).listFiles().orEmpty().size)

        documents.failDeletes = false
        ShareTargetJournal.recover(context)
        assertEquals(listOf(documents.created.single()), documents.deleted)
        assertTrue(ShareTargetJournal.root(context).listFiles().orEmpty().isEmpty())

        // A target of the running process is never recovered.
        val active = ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document,
            DocumentsContract.buildDocumentUri("org.example.documents", "active.bin"))
        ShareTargetJournal.recover(context)
        assertFalse("active.bin" in documents.deleted)
        ShareTargetJournal.complete(context, active)
    }

    // 6. The type a reader gets from Plektra's provider is the one the clip announces.
    @Test
    fun providerReportsTheMimeTypeOfTheClip() = runBlocking {
        val source = Uri.parse("content://org.example.files/photo.jpg")
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream(byteArrayOf(1, 2)))
        assertEquals(
            ShareItemResult.Copied,
            ShareOperations.copyToClipboard(context, SharedItem.Stream(0, source, "image/png", "photo.jpg", null))
        )
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        // Resolved through the provider registered from the manifest, as a reading app would.
        val resolver = context.contentResolver
        assertEquals(clip.description.getMimeType(0), resolver.getType(clip.getItemAt(0).uri))

        // Without an extension AndroidX would answer application/octet-stream.
        val raw = Uri.parse("content://org.example.files/blob")
        shadowOf(context.contentResolver).registerInputStream(raw, ByteArrayInputStream(byteArrayOf(3)))
        ShareOperations.copyToClipboard(context, SharedItem.Stream(1, raw, "application/x-plektra-test", "blob", null))
        val blob = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).uri
        assertEquals("application/x-plektra-test", resolver.getType(blob))
    }

    // 8. The folder URI stays out of Android backup, device transfer and Plektra's own backup.
    @Test
    fun saveFolderUriIsExcludedFromEveryBackupPath() {
        val tree = Uri.parse("content://org.example.documents/tree/root")
        context.contentResolver.takePersistableUriPermission(
            tree,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        ShareSettings.selectSaveFolder(context, tree)
        assertFalse(
            "The folder URI must not live in the backed-up settings file",
            rocks.pkb.plektra.SettingsManager.getPreferences(context).contains(ShareSettings.KEY_SAVE_FOLDER_URI)
        )
        val file = "${ShareSettings.LOCAL_PREFERENCES}.xml"
        assertTrue(file in excludedSharedPreferences(R.xml.backup_rules, "full-backup-content"))
        assertTrue(file in excludedSharedPreferences(R.xml.data_extraction_rules, "cloud-backup"))
        assertTrue(file in excludedSharedPreferences(R.xml.data_extraction_rules, "device-transfer"))
        assertFalse(BackupPreferenceContract.shouldExportPreferenceFile(ShareSettings.LOCAL_PREFERENCES))
    }

    private fun excludedSharedPreferences(xml: Int, section: String): Set<String> {
        val parser = context.resources.getXml(xml)
        val excluded = mutableSetOf<String>()
        var inSection = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    parser.name == section -> inSection = true
                    inSection && parser.name == "exclude" &&
                        parser.getAttributeValue(null, "domain") == "sharedpref" ->
                        excluded += parser.getAttributeValue(null, "path")
                }
                XmlPullParser.END_TAG -> if (parser.name == section) inSection = false
            }
        }
        return excluded
    }

    private fun <T : ContentProvider> installProvider(authority: String, provider: T): T {
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return provider
    }

    class FakeDocumentsProvider : ContentProvider() {
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var failDeletes = false
        private val files = mutableMapOf<String, File>()

        override fun onCreate() = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            val uri = extras?.getParcelable<Uri>("uri") ?: return null
            return when (method) {
                "android:createDocument" -> {
                    val id = "doc-${created.size}"
                    created += id
                    files[id] = File.createTempFile("share-doc", ".bin", context!!.cacheDir)
                    Bundle().apply {
                        putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, id))
                    }
                }
                "android:deleteDocument" -> {
                    if (failDeletes) throw IllegalStateException("synthetic transient failure")
                    val id = DocumentsContract.getDocumentId(uri)
                    deleted += id
                    files.remove(id)?.delete()
                    Bundle()
                }
                else -> null
            }
        }

        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
            val file = files.getOrPut(DocumentsContract.getDocumentId(uri)) {
                File.createTempFile("share-doc", ".bin", context!!.cacheDir)
            }
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.parseMode(mode))
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf("_display_name")).apply { addRow(arrayOf(DocumentsContract.getDocumentId(uri))) }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    class FakeMediaStore : ContentProvider() {
        val pending = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        var lastQueryMatchedPending = false

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, args: Bundle?, signal: CancellationSignal?): Cursor {
            lastQueryMatchedPending = args?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE
            val id = uri.lastPathSegment!!
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.IS_PENDING)).apply {
                if (id !in deleted) addRow(arrayOf(if (id in pending) 1 else 0))
            }
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            query(uri, p, null as Bundle?, null)

        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
            deleted += uri.lastPathSegment!!
            return 1
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
}
