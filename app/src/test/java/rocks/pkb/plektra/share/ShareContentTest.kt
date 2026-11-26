package rocks.pkb.plektra.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingLinkIds
import rocks.pkb.plektra.SettingLinkRegistry
import rocks.pkb.plektra.SettingsDestination
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.PreferenceValueType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareContentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        ShareClipboardCache.root(context).deleteRecursively()
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        // Robolectric starts with an empty extension table.
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("png", "image/png")
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("pdf", "application/pdf")
            addExtensionMimeTypeMapping("txt", "text/plain")
            addExtensionMimeTypeMapping("gz", "application/gzip")
        }
    }

    @Test
    fun namesLoseTheirPathAndInvisibleOrReservedCharacters() {
        assertEquals("passwd", ShareFileNames.sanitize("../../etc/passwd", "application/octet-stream", "x"))
        assertEquals("evil.txt", ShareFileNames.sanitize("C:\\temp\\evil.txt", "text/plain", "x"))
        assertEquals("hidden.txt", ShareFileNames.sanitize("..hidden.txt", "text/plain", "x"))
        // U+202E would display "photo.exe" as "photo.gpj" in a file list.
        assertEquals("photo.exe", ShareFileNames.sanitize("photo\u202E.exe", "application/octet-stream", "x"))
        assertEquals("a_b_c.pdf", ShareFileNames.sanitize("a:b*c.pdf", "application/pdf", "x"))
        assertEquals("line break.txt", ShareFileNames.sanitize("line\nbreak.txt", "text/plain", "x"))
    }

    @Test
    fun missingNameOrExtensionComesFromTheFallbackAndType() {
        assertEquals("Shared file.png", ShareFileNames.sanitize(null, "image/png", "Shared file"))
        assertEquals("Shared file.png", ShareFileNames.sanitize(" .. ", "image/png", "Shared file"))
        assertEquals("scan.pdf", ShareFileNames.sanitize("scan", "application/pdf", "x"))
        // A known name keeps its own extension, even when the provider reports another type.
        assertEquals("photo.jpg", ShareFileNames.sanitize("photo.jpg", "image/png", "x"))
        assertEquals("archive.tar.gz", ShareFileNames.sanitize("archive.tar.gz", "application/gzip", "x"))
    }

    @Test
    fun longNamesStayWithinTheByteLimitAndKeepTheirExtension() {
        val name = ShareFileNames.sanitize("ä".repeat(300) + ".jpeg", "image/jpeg", "x")
        assertTrue(name.endsWith(".jpeg"))
        assertTrue(name.toByteArray().size <= ShareFileNames.MAX_NAME_BYTES)
        assertTrue(name.removeSuffix(".jpeg").all { it == 'ä' })
    }

    @Test
    fun mimeTypePrefersTheProviderThenTheSenderThenTheExtension() {
        assertEquals("image/png", ShareMimeTypes.resolve("IMAGE/PNG; q=1", "image/*", "x.jpg"))
        assertEquals("application/pdf", ShareMimeTypes.resolve(null, "application/pdf", "x"))
        assertEquals("image/jpeg", ShareMimeTypes.resolve("application/octet-stream", "*/*", "x.jpg"))
        assertEquals(ShareMimeTypes.OCTET_STREAM, ShareMimeTypes.resolve(null, "*/*", "data"))
        assertNull(ShareMimeTypes.normalize("not a type"))
    }

    @Test
    fun onlyContentUrisOfOtherAppsAreAccepted() {
        assertFalse(ShareIntentParser.isReadableForeignContentUri(context, Uri.parse("file:///data/data/${context.packageName}/shared_prefs/plektra_prefs.xml")))
        assertFalse(ShareIntentParser.isReadableForeignContentUri(context, Uri.parse("content://${context.packageName}.fileprovider/sbom/x.json")))
        assertFalse(ShareIntentParser.isReadableForeignContentUri(context, Uri.parse("content:///no-authority")))
        assertFalse(ShareIntentParser.isReadableForeignContentUri(context, Uri.parse("https://example.org/a.png")))
        assertTrue(ShareIntentParser.isReadableForeignContentUri(context, Uri.parse("content://org.example.files/a.png")))
    }

    @Test
    fun singleTextShareKeepsTextSubjectAndHtml() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.org/a?b=c")
            putExtra(Intent.EXTRA_SUBJECT, "Example")
            putExtra(Intent.EXTRA_HTML_TEXT, "<a href=\"https://example.org/a?b=c\">Example</a>")
        }
        val payload = ShareIntentParser.parse(context, intent)
        val text = payload.items.single() as SharedItem.Text
        assertEquals("https://example.org/a?b=c", text.text)
        assertEquals("Example", text.subject)
        assertTrue(text.html!!.contains("href"))
        assertEquals(0, payload.rejectedCount)
    }

    @Test
    fun multipleShareDropsUnsafeReferencesAndDuplicates() {
        val first = Uri.parse("content://org.example.files/one.png")
        val second = Uri.parse("content://org.example.files/two.bin")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(first, Uri.parse("file:///sdcard/secret.txt"), second, first)
            )
        }
        val payload = ShareIntentParser.parse(context, intent)
        assertEquals(listOf(first, second), payload.items.map { (it as SharedItem.Stream).uri })
        assertEquals(1, payload.rejectedCount)
        assertEquals("image/png", (payload.items[0] as SharedItem.Stream).mimeType)
        assertEquals(1, payload.imageCount)
    }

    @Test
    fun streamsOnlyInClipDataAreRead() {
        val uri = Uri.parse("content://org.example.files/clip.pdf")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            clipData = ClipData.newRawUri("", uri)
        }
        val item = ShareIntentParser.parse(context, intent).items.single() as SharedItem.Stream
        assertEquals(uri, item.uri)
        assertEquals("application/pdf", item.mimeType)
    }

    @Test
    fun otherActionsYieldNothing() {
        assertTrue(ShareIntentParser.parse(context, Intent(Intent.ACTION_VIEW)).items.isEmpty())
    }

    @Test
    fun textGoesToTheClipboardAsText() = runBlocking {
        val item = SharedItem.Text(0, "Synthetic text ✓", html = null, subject = null)
        assertTrue(ShareOperations.copyToClipboard(context, item) == ShareItemResult.Copied)
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("Synthetic text ✓", clip.getItemAt(0).text.toString())
    }

    @Test
    fun oversizedTextIsRefusedWithAClearReason() = runBlocking {
        val item = SharedItem.Text(0, "x".repeat(ShareOperations.MAX_CLIPBOARD_TEXT_CHARS + 1), null, null)
        assertEquals(
            ShareItemResult.Failed(ShareFailure.TooLargeForClipboard),
            ShareOperations.copyToClipboard(context, item)
        )
    }

    @Test
    fun fileClipIsAByteExactCopyServedByPlektrasProvider() = runBlocking {
        val source = Uri.parse("content://org.example.files/picture.png")
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream(bytes))
        val item = SharedItem.Stream(0, source, "image/png", "picture.png", bytes.size.toLong())

        assertTrue(ShareOperations.copyToClipboard(context, item) == ShareItemResult.Copied)

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("image/png", clip.description.getMimeType(0))
        val uri = clip.getItemAt(0).uri
        assertEquals(ShareClipboardCache.authority(context), uri.authority)
        val copy = File(ShareClipboardCache.root(context), uri.pathSegments[1]).resolve("picture.png")
        assertArrayEquals(bytes, copy.readBytes())
    }

    @Test
    fun unreadableSourceLeavesNoClipboardCopy() = runBlocking {
        val item = SharedItem.Stream(0, Uri.parse("content://org.example.files/gone.png"), "image/png", "gone.png", null)
        val result = ShareOperations.copyToClipboard(context, item)
        assertEquals(ShareItemResult.Failed(ShareFailure.SourceUnreadable), result)
        assertTrue(ShareClipboardCache.root(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pruneKeepsTheCurrentClipAndYoungCopiesOnly() {
        val now = System.currentTimeMillis()
        val current = ShareClipboardCache.newFile(context, "current.png").apply { writeText("a") }
        val replaced = ShareClipboardCache.newFile(context, "replaced.png").apply { writeText("b") }
        val young = ShareClipboardCache.newFile(context, "young.png").apply { writeText("c") }
        listOf(current, replaced).forEach { it.parentFile!!.setLastModified(now - 3_600_000) }
        val currentUri = Uri.parse(
            "content://${ShareClipboardCache.authority(context)}/share_clipboard/${current.parentFile!!.name}/current.png"
        )

        ShareClipboardCache.prune(context, ClipData.newRawUri("", currentUri), now)

        assertTrue(current.exists())
        assertFalse(replaced.exists())
        assertTrue(young.exists())
    }

    @Test
    fun revokedFolderIsReportedWithoutWriting() = runBlocking {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AShared")
        SettingsManager.getPreferences(context).edit().putString(ShareSettings.KEY_SAVE_FOLDER_URI, tree.toString()).commit()

        val folder = ShareSettings.saveFolder(context) as ShareSettings.SaveFolder.Tree
        assertFalse(folder.accessible)
        val item = SharedItem.Text(0, "Synthetic", null, null)
        assertEquals(
            ShareItemResult.Failed(ShareFailure.FolderAccessRevoked),
            ShareOperations.saveToFolder(context, item, tree, "Shared")
        )
    }

    @Test
    fun galleryRejectsNonPicturesBeforeWriting() = runBlocking {
        val source = Uri.parse("content://org.example.files/fake.png")
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream("not a picture".toByteArray()))
        val item = SharedItem.Stream(0, source, "image/png", "fake.png", null)
        assertEquals(ShareItemResult.Failed(ShareFailure.NotAnImage), ShareOperations.saveToGallery(context, item))
        val text = SharedItem.Text(1, "Synthetic", null, null)
        assertEquals(ShareItemResult.Failed(ShareFailure.NotAnImage), ShareOperations.saveToGallery(context, text))
    }

    @Test
    fun galleryAlbumSettingSelectsThePicturesPath() {
        assertEquals("Pictures/", ShareSettings.galleryRelativePath(context))
        ShareSettings.setGalleryAlbumEnabled(context, true)
        assertEquals("Pictures/Plektra/", ShareSettings.galleryRelativePath(context))
    }

    @Test
    fun textFilesAreNamedAfterTheSubjectWithATextExtension() {
        val withSubject = SharedItem.Text(0, "body", null, "Report.pdf")
        assertEquals("Report.pdf.txt", ShareOperations.suggestedName(context, withSubject))
        val withoutSubject = SharedItem.Text(0, "body", null, null)
        val name = ShareOperations.suggestedName(context, withoutSubject)
        assertTrue(name, name.endsWith(".txt") && !name.contains(':'))
    }

    @Test
    fun destinationSettingsAreLinkableSearchableAndOnlyTheAlbumIsBackedUp() {
        listOf(
            SettingLinkIds.ADVANCED_SHARE_SAVE_FOLDER to R.string.share_save_folder_title,
            SettingLinkIds.ADVANCED_SHARE_GALLERY_ALBUM to R.string.share_gallery_album_title
        ).forEach { (id, title) ->
            val entry = requireNotNull(SettingLinkRegistry.byId(id))
            assertEquals(SettingsDestination.Advanced, entry.route.destination)
            assertTrue(entry.isAvailable(context))
            assertEquals(id, SettingLinkRegistry.parseSettingLinkUri(Uri.parse("plektra://setting/$id")))
            assertTrue(SettingLinkRegistry.search(context, context.getString(title)).any { it.id == id })
        }
        assertTrue(SettingLinkRegistry.search(context, "downloads").any { it.id == SettingLinkIds.ADVANCED_SHARE_SAVE_FOLDER })
        assertNull(BackupPreferenceContract.expectedExportType("plektra_prefs", ShareSettings.KEY_SAVE_FOLDER_URI))
        assertEquals(
            PreferenceValueType.BOOLEAN,
            BackupPreferenceContract.expectedExportType("plektra_prefs", ShareSettings.KEY_GALLERY_ALBUM)
        )
    }
}
