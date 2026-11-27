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
                ShareClipboardCache.prune(context) { null }
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
        ShareClipboardCache.prune(context) { null }
        assertFalse("A copy must not outlive its deadline", second.exists())
        assertTrue(writing.exists())
        ShareClipboardCache.release(writing)
    }

    @Test
    fun unreadableClipboardKeepsCopiesWithinTheirDeadline() {
        val current = placedCopy("current.png")
        ShareClipboardCache.prune(context) { null }
        assertTrue(current.exists())
    }

    private fun placedCopy(name: String): File {
        val file = ShareClipboardCache.newCopy(context, name, "image/png").apply { writeText(name) }
        val uri = FileProvider.getUriForFile(context, ShareClipboardCache.authority(context), file)
        ShareClipboardCache.place(context, file) { ClipData.newRawUri("", uri) }
        return file
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
}
