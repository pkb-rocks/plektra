package rocks.pkb.plektra.share

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** Save targets, their journal and recovery after a killed process (review rounds 2 and 3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareSaveRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scheduled = Collections.synchronizedList(mutableListOf<ShareCleanupWorker.Run>())
    private val originalScheduler = ShareCleanupWorker.scheduler

    @Before
    fun reset() {
        ShareTargetJournal.root(context).deleteRecursively()
        ShareTargetJournal.forgetActiveForTest()
        ShareCleanupWorker.scheduler = { _, run -> scheduled += run }
    }

    @After
    fun restore() {
        ShareCleanupWorker.scheduler = originalScheduler
    }

    private fun records() = ShareTargetJournal.root(context).listFiles().orEmpty().map { it.name }

    private fun grantedTree(provider: FakeDocumentsProvider): Uri {
        installProvider(AUTHORITY, provider)
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return tree
    }

    private fun source(name: String, bytes: ByteArray? = null, failing: Boolean = false, onRead: () -> Unit = {}): Uri {
        val uri = Uri.parse("content://org.example.files/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            var position = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                onRead()
                if (failing) throw IOException("synthetic source failure")
                val data = bytes ?: return -1
                if (position >= data.size) return -1
                val count = minOf(len, data.size - position)
                System.arraycopy(data, position, b, off, count)
                position += count
                return count
            }
        })
        return uri
    }

    // Round 3, finding 1: SAF writes go to a unique temporary document that recovery can identify.
    @Test
    fun folderSaveWritesAHiddenTemporaryDocumentAndRenamesItWhenComplete() = runBlocking {
        val documents = FakeDocumentsProvider()
        val tree = grantedTree(documents)
        val bytes = ByteArray(100_000) { (it * 7).toByte() }
        var nameWhileWriting: Set<String> = emptySet()
        val item = SharedItem.Stream(0, source("data.bin", bytes, onRead = {
            if (nameWhileWriting.isEmpty()) nameWhileWriting = documents.names()
        }), "application/octet-stream", "data.bin", null)

        val result = ShareOperations.saveToFolder(context, item, tree, "Folder")

        assertEquals(ShareItemResult.Saved("data.bin", "Folder"), result)
        assertTrue(nameWhileWriting.single().startsWith(".plektra-partial-"))
        assertEquals(setOf("data.bin"), documents.names())
        assertTrue(bytes.contentEquals(documents.bytesOf("data.bin")))
        assertTrue(records().isEmpty())
    }

    @Test
    fun recoveryDeletesADocumentThatStillCarriesItsTemporaryName() {
        val documents = installProvider(AUTHORITY, FakeDocumentsProvider())
        val partial = documents.add(".plektra-partial-x")
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document, partial, ".plektra-partial-x", FOLDER)
        ShareTargetJournal.forgetActiveForTest() // The process ended before finally ran.

        ShareTargetJournal.recover(context)

        assertEquals(listOf(".plektra-partial-x"), documents.deleted)
        assertTrue(records().isEmpty())
    }

    @Test
    fun recoveryNeverDeletesACompletedOrReplacedDocument() {
        val documents = installProvider(AUTHORITY, FakeDocumentsProvider())
        // Completed: renamed away from its temporary name, then the process died before unlinking.
        documents.add("report.pdf")
        val renamedAway = DocumentsContract.buildDocumentUri(AUTHORITY, ".plektra-partial-a")
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document, renamedAway, ".plektra-partial-a", FOLDER)
        // Replaced: a path-based URI that now points to a different file.
        val replaced = documents.add("replaced.bin", id = "slot")
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document, replaced, ".plektra-partial-b", FOLDER)
        ShareTargetJournal.forgetActiveForTest()

        ShareTargetJournal.recover(context)

        assertTrue(documents.deleted.isEmpty())
        assertEquals(setOf("report.pdf", "replaced.bin"), documents.names())
        assertTrue(records().isEmpty())
    }

    @Test
    fun failedDeleteAndUnreachableProviderStayRecordedForRetry() = runBlocking {
        val documents = FakeDocumentsProvider().apply { failDeletes = true }
        val tree = grantedTree(documents)
        val item = SharedItem.Stream(0, source("broken.bin", failing = true), "application/octet-stream", "broken.bin", null)

        assertEquals(
            ShareItemResult.Failed(ShareFailure.SourceUnreadable),
            ShareOperations.saveToFolder(context, item, tree, "Folder")
        )
        assertEquals(1, records().size)
        // A provider that is not reachable right now keeps its record too.
        ShareTargetJournal.begin(
            context,
            ShareTargetJournal.Kind.Document,
            DocumentsContract.buildDocumentUri("org.example.unmounted", "x"),
            ".plektra-partial-y",
            DocumentsContract.buildDocumentUri("org.example.unmounted", "root")
        )
        ShareTargetJournal.forgetActiveForTest()
        ShareTargetJournal.recover(context)
        assertEquals(2, records().size)

        documents.failDeletes = false
        ShareTargetJournal.recover(context)
        assertTrue(documents.deleted.single().startsWith(".plektra-partial-"))
        assertEquals(1, records().size)
    }

    @Test
    fun providerWithoutRenameWritesTheFinalDocumentDirectlyAndRecordsNothing() = runBlocking {
        val documents = FakeDocumentsProvider().apply { supportsRename = false }
        val tree = grantedTree(documents)
        var recordsWhileWriting = -1
        val item = SharedItem.Stream(0, source("direct.bin", byteArrayOf(1, 2, 3), onRead = {
            if (recordsWhileWriting < 0) recordsWhileWriting = records().size
        }), "application/octet-stream", "direct.bin", null)

        assertEquals(ShareItemResult.Saved("direct.bin", "Folder"), ShareOperations.saveToFolder(context, item, tree, "Folder"))

        assertEquals("No record: a crash must leave the file in place", 0, recordsWhileWriting)
        assertEquals(setOf("direct.bin"), documents.names())
    }

    // Round 3, finding 3: MediaStore rows are deleted only on proof that they are pending.
    @Test
    fun mediaStoreRowIsDeletedOnlyWhenProvenPending() {
        val media = installProvider("media", FakeMediaStore())
        val pending = Uri.parse("content://media/external_primary/downloads/1")
        val published = Uri.parse("content://media/external_primary/downloads/2")
        val unanswered = Uri.parse("content://media/external_primary/downloads/3")
        media.pending += "1"
        media.unanswered += "3"
        listOf(pending, published, unanswered).forEach {
            ShareTargetJournal.begin(context, ShareTargetJournal.Kind.MediaStore, it)
        }
        ShareTargetJournal.forgetActiveForTest()

        ShareTargetJournal.recover(context)

        assertEquals(listOf("1"), media.deleted)
        assertTrue(media.lastQueryMatchedPending)
        assertEquals("The unanswered row stays recorded", 1, records().size)
    }

    // Round 3, finding 3: the cleanup run is scheduled before any byte is written.
    @Test
    fun recoveryIsScheduledBeforeAnInterruptibleWrite() = runBlocking {
        val documents = FakeDocumentsProvider()
        val tree = grantedTree(documents)
        var scheduledWhileWriting = emptyList<ShareCleanupWorker.Run>()
        val item = SharedItem.Stream(0, source("timed.bin", byteArrayOf(9), onRead = {
            if (scheduledWhileWriting.isEmpty()) scheduledWhileWriting = scheduled.toList()
        }), "application/octet-stream", "timed.bin", null)

        ShareOperations.saveToFolder(context, item, tree, "Folder")

        assertTrue(ShareCleanupWorker.Run.Recovery in scheduledWhileWriting)
    }

    // Round 3, finding 3: a slow recovery never delays a fresh share.
    @Test
    fun freshShareLoadsWhileRecoveryIsBlocked() {
        val release = CountDownLatch(1)
        val documents = installProvider(AUTHORITY, FakeDocumentsProvider().apply { blockQueries = release })
        ShareTargetJournal.begin(
            context,
            ShareTargetJournal.Kind.Document,
            DocumentsContract.buildDocumentUri(AUTHORITY, ".plektra-partial-slow"),
            ".plektra-partial-slow",
            FOLDER
        )
        ShareTargetJournal.forgetActiveForTest()
        val viewModel = ShareReceiverViewModel(context as Application)

        viewModel.load(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "synthetic"))
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.loading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertFalse("The share waited for recovery", viewModel.state.loading)
        assertEquals(1, viewModel.state.payload.items.size)
        assertTrue(documents.queryStarted.await(5, TimeUnit.SECONDS))
        release.countDown()
    }

    // Round 3, finding 2: begin and recover never race on record files.
    @Test
    fun concurrentBeginAndRecoverKeepEveryRunningRecord() {
        installProvider("media", FakeMediaStore().apply { pendingByDefault = true })
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val entries = Collections.synchronizedList(mutableListOf<ShareTargetJournal.Entry>())
        var running = true
        val recoverer = thread {
            while (running) runCatching { ShareTargetJournal.recover(context) }.onFailure { failures += it }
        }
        val writers = (0 until 4).map { worker ->
            thread {
                repeat(100) { index ->
                    runCatching {
                        entries += ShareTargetJournal.begin(
                            context,
                            ShareTargetJournal.Kind.MediaStore,
                            Uri.parse("content://media/external_primary/downloads/${worker * 1000 + index}")
                        )
                    }.onFailure { failures += it }
                }
            }
        }
        writers.forEach { it.join() }
        running = false
        recoverer.join()

        assertTrue("begin or recover failed: $failures", failures.isEmpty())
        assertEquals(400, entries.size)
        val present = records().toSet()
        assertTrue("A running target lost its record", entries.all { it.id in present })
    }

    // Pre-check round, finding 1: a failed or ignored rename is a failure, never "saved".
    @Test
    fun renameReturningNullFailsAndLeavesNoFile() = runBlocking {
        val documents = FakeDocumentsProvider().apply { renameFailsSilently = true }
        val tree = grantedTree(documents)
        val item = SharedItem.Stream(0, source("a.bin", byteArrayOf(1)), "application/octet-stream", "a.bin", null)

        assertEquals(
            ShareItemResult.Failed(ShareFailure.WriteFailed),
            ShareOperations.saveToFolder(context, item, tree, "Folder")
        )
        assertTrue(documents.names().isEmpty())
        assertTrue(records().isEmpty())
    }

    @Test
    fun renameThatKeepsTheTemporaryNameFails() = runBlocking {
        val documents = FakeDocumentsProvider().apply { renameIgnored = true }
        val tree = grantedTree(documents)
        val item = SharedItem.Stream(0, source("b.bin", byteArrayOf(2)), "application/octet-stream", "b.bin", null)

        assertEquals(
            ShareItemResult.Failed(ShareFailure.WriteFailed),
            ShareOperations.saveToFolder(context, item, tree, "Folder")
        )
        assertTrue(documents.names().isEmpty())
    }

    @Test
    fun temporaryNameWithoutOurTokenIsNeverRecorded() = runBlocking {
        // The provider replaces the requested name, so recovery could not identify the file.
        val documents = FakeDocumentsProvider().apply { createdName = "untitled" }
        val tree = grantedTree(documents)
        var recordsWhileWriting = -1
        val item = SharedItem.Stream(0, source("c.bin", byteArrayOf(3), onRead = {
            if (recordsWhileWriting < 0) recordsWhileWriting = records().size
        }), "application/octet-stream", "c.bin", null)

        ShareOperations.saveToFolder(context, item, tree, "Folder")

        assertEquals(0, recordsWhileWriting)
    }

    // Pre-check round, finding 2: unanswered lookups, false deletes and dead providers are retried.
    @Test
    fun unansweredLookupIsRetriedUnlessTheFolderStillAnswers() {
        val documents = installProvider(AUTHORITY, FakeDocumentsProvider().apply { folderAnswers = false })
        val missing = DocumentsContract.buildDocumentUri(AUTHORITY, ".plektra-partial-gone")
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document, missing, ".plektra-partial-gone", FOLDER)
        ShareTargetJournal.forgetActiveForTest()

        ShareTargetJournal.recover(context)
        assertEquals("No answer from document and folder: keep for retry", 1, records().size)

        documents.folderAnswers = true
        ShareTargetJournal.recover(context)
        assertTrue("The folder answers, so the document is gone", records().isEmpty())
        assertTrue(documents.deleted.isEmpty())
    }

    @Test
    fun deleteReportingFalseIsRetried() {
        val documents = installProvider(AUTHORITY, FakeDocumentsProvider().apply { deleteFailsSilently = true })
        val partial = documents.add(".plektra-partial-f")
        ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document, partial, ".plektra-partial-f", FOLDER)
        ShareTargetJournal.forgetActiveForTest()

        ShareTargetJournal.recover(context)
        assertEquals(1, records().size)

        documents.deleteFailsSilently = false
        ShareTargetJournal.recover(context)
        assertEquals(listOf(".plektra-partial-f"), documents.deleted)
        assertTrue(records().isEmpty())
    }

    @Test
    fun deadProviderLeavesNoRecordStrandedInRecovery() {
        val documents = installProvider(AUTHORITY, FakeDocumentsProvider().apply { dead = true })
        val names = listOf(".plektra-partial-1", ".plektra-partial-2", ".plektra-partial-3")
        names.forEach { name ->
            ShareTargetJournal.begin(context, ShareTargetJournal.Kind.Document, documents.add(name), name, FOLDER)
        }
        ShareTargetJournal.forgetActiveForTest()

        ShareTargetJournal.recover(context) // Must not throw, must not keep records claimed.
        assertEquals(3, records().size)

        documents.dead = false
        ShareTargetJournal.recover(context)
        assertEquals(names.toSet(), documents.deleted.toSet())
        assertTrue(records().isEmpty())
    }

    // Pre-check round, finding 3: the job runs again while records remain or a save started meanwhile.
    @Test
    fun cleanupJobAsksForAnotherRunWhileWorkRemains() {
        installProvider(AUTHORITY, FakeDocumentsProvider().apply { dead = true })
        ShareTargetJournal.begin(
            context,
            ShareTargetJournal.Kind.Document,
            DocumentsContract.buildDocumentUri(AUTHORITY, ".plektra-partial-r"),
            ".plektra-partial-r",
            FOLDER
        )
        ShareTargetJournal.forgetActiveForTest()
        assertTrue("A record kept for retry needs another run", ShareCleanupWorker.runOnce(context, System.nanoTime()))

        ShareTargetJournal.root(context).deleteRecursively()
        assertFalse(ShareCleanupWorker.runOnce(context, System.nanoTime()))

        val startedAt = System.nanoTime()
        ShareCleanupWorker.scheduleRecovery(context) // A save begins while the job runs.
        assertTrue("A save that began during the run needs another run", ShareCleanupWorker.runOnce(context, startedAt))
    }

    private fun <T : ContentProvider> installProvider(authority: String, provider: T): T {
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return provider
    }

    /** Path-like document provider: the document ID is its name, as with external storage. */
    class FakeDocumentsProvider : ContentProvider() {
        private class Doc(var name: String, val file: File)

        private val docs = Collections.synchronizedMap(linkedMapOf<String, Doc>())
        val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var failDeletes = false
        var deleteFailsSilently = false
        var supportsRename = true
        var renameFailsSilently = false
        var renameIgnored = false
        var createdName: String? = null
        var folderAnswers = true
        var dead = false
        var blockQueries: CountDownLatch? = null
        val queryStarted = CountDownLatch(1)

        override fun onCreate() = true

        fun names(): Set<String> = synchronized(docs) { docs.values.map { it.name }.toSet() }
        fun bytesOf(name: String): ByteArray = synchronized(docs) { docs.values.first { it.name == name }.file.readBytes() }

        fun add(name: String, id: String = name): Uri {
            docs[id] = Doc(name, File.createTempFile("doc", ".bin", context!!.cacheDir))
            return DocumentsContract.buildDocumentUri(AUTHORITY, id)
        }

        private fun uniqueName(name: String): String {
            var candidate = name
            var n = 1
            while (names().contains(candidate)) candidate = "$name (${n++})"
            return candidate
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            val uri = extras?.getParcelable<Uri>("uri") ?: return null
            return when (method) {
                "android:createDocument" -> {
                    val name = uniqueName(createdName ?: extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                    add(name)
                    Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, name)) }
                }
                "android:renameDocument" -> {
                    // A checked exception is swallowed by DocumentsContract, which then returns null.
                    if (renameFailsSilently) throw IOException("synthetic rename failure")
                    val id = DocumentsContract.getDocumentId(uri)
                    if (renameIgnored) return Bundle().apply { putParcelable("uri", uri) }
                    val doc = docs.remove(id) ?: throw IllegalArgumentException("Missing $id")
                    doc.name = uniqueName(extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                    docs[doc.name] = doc
                    Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, doc.name)) }
                }
                "android:deleteDocument" -> {
                    if (failDeletes) throw IllegalStateException("synthetic transient failure")
                    if (deleteFailsSilently) throw IOException("synthetic delete failure") // -> false
                    val doc = docs.remove(DocumentsContract.getDocumentId(uri))
                        ?: throw IllegalArgumentException("Missing document")
                    deleted += doc.name
                    doc.file.delete()
                    Bundle()
                }
                else -> null
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val doc = docs[DocumentsContract.getDocumentId(uri)] ?: throw java.io.FileNotFoundException()
            return ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
            queryStarted.countDown()
            blockQueries?.await(10, TimeUnit.SECONDS)
            if (dead) throw android.os.DeadObjectException()
            val id = DocumentsContract.getDocumentId(uri)
            if (id == "root") {
                return if (folderAnswers) MatrixCursor(arrayOf(Document.COLUMN_DISPLAY_NAME)).apply { addRow(arrayOf("root")) }
                else null
            }
            // Like DocumentsProvider, a missing document yields no cursor at all.
            val doc = docs[id] ?: return null
            val flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                (if (supportsRename) Document.FLAG_SUPPORTS_RENAME else 0)
            val columns = p ?: arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_FLAGS)
            return MatrixCursor(columns).apply {
                addRow(columns.map { if (it == Document.COLUMN_FLAGS) flags else doc.name }.toTypedArray())
            }
        }

        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    class FakeMediaStore : ContentProvider() {
        val pending: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val unanswered: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var pendingByDefault = false
        @Volatile var lastQueryMatchedPending = false

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, args: Bundle?, signal: CancellationSignal?): Cursor? {
            lastQueryMatchedPending = args?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE
            val id = uri.lastPathSegment!!
            if (id in unanswered) return null
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.IS_PENDING)).apply {
                if (id !in deleted) addRow(arrayOf(if (pendingByDefault || id in pending) 1 else 0))
            }
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? =
            query(uri, p, null as Bundle?, null)

        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
            deleted += uri.lastPathSegment!!
            return 1
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "org.example.documents"
        val FOLDER: Uri = DocumentsContract.buildDocumentUri(AUTHORITY, "root")
    }
}
