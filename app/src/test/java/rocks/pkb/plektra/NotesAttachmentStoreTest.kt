package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesAttachmentStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(limit: Long = NotesAttachmentStore.MAX_IMAGE_BYTES) =
        NotesAttachmentStore(File(folder.root, "attachments"), File(folder.root, "staging"), limit)

    private fun assertFails(failure: NotesAttachmentStore.Failure, block: () -> Unit) {
        try {
            block()
            fail("Import should fail")
        } catch (e: NotesAttachmentStore.ImportException) {
            assertEquals(failure, e.failure)
        }
    }

    @Test fun importKeepsImageBytesUnderAGeneratedName() {
        val store = store()
        val name = store.newName("image/png")
        store.import(ByteArrayInputStream(NotesTestImages.PNG), name)
        assertTrue(store.file(name)!!.readBytes().contentEquals(NotesTestImages.PNG))
        assertTrue(store.stagingDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(NotesAttachmentStore.isValidBackupFile(store.file(name)!!))
    }

    @Test fun wrongSignatureAndOversizedImagesLeaveNothingBehind() {
        val store = store(limit = 64)
        assertFails(NotesAttachmentStore.Failure.UNSUPPORTED) {
            store.import(ByteArrayInputStream("not an image at all".toByteArray()), store.newName("image/png"))
        }
        assertFails(NotesAttachmentStore.Failure.UNSUPPORTED) {
            store.import(ByteArrayInputStream(NotesTestImages.PNG), store.newName("image/jpeg"))
        }
        assertFails(NotesAttachmentStore.Failure.TOO_LARGE) {
            store.import(ByteArrayInputStream(NotesTestImages.PNG + ByteArray(64)), store.newName("image/png"))
        }
        assertTrue(store.directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.stagingDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun imageSignatureWithoutDecodableBoundsLeavesNothingBehind() {
        val store = store()
        assertFails(NotesAttachmentStore.Failure.UNSUPPORTED) {
            store.import(ByteArrayInputStream(NotesTestImages.PNG.copyOf(12)), store.newName("image/png"))
        }
        assertTrue(store.directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.stagingDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test fun onlyGeneratedAttachmentNamesResolve() {
        val store = store()
        val name = store.newName("image/webp")
        assertEquals(File(store.directory, name), store.resolve("attachments/$name"))
        listOf("attachments/../shared_prefs/notes.xml", "attachments/x.png", "/data/data/x.png", "file:///sdcard/a.png",
            "https://example.com/a.png", name, "attachments/$name/../$name").forEach {
            assertNull(it, store.resolve(it))
        }
        assertFalse(NotesAttachmentStore.isValidBackupFile(File(folder.root, "other.png").apply { writeBytes(NotesTestImages.PNG) }))
    }

    @Test fun referencedNamesFindsAttachmentsInMarkdown() {
        val first = store().newName("image/png")
        val second = store().newName("image/gif")
        assertEquals(setOf(first, second), NotesAttachmentStore.referencedNames(
            "![a](attachments/$first) text ![](attachments/$second) ![x](https://e/attachments/y.png)"))
    }
}

internal object NotesTestImages {
    /** A 1×1 PNG. */
    val PNG: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
}
