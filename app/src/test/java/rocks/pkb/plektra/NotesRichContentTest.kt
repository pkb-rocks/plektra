package rocks.pkb.plektra

import android.content.ClipDescription
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.widget.EditText
import android.widget.TextView
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesRichContentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val queue = ArrayDeque<Runnable>()
    private lateinit var originalExecutor: Executor
    private val controllers = mutableListOf<ActivityController<NotesActivity>>()
    private val imageUri = Uri.parse("content://rocks.pkb.plektra.test/image")
    private val attachments get() = File(context.filesDir, NotesAttachmentStore.DIRECTORY)
    private val staging get() = File(context.filesDir, "notes_attachment_staging")

    @Before fun setUp() {
        originalExecutor = NotesAttachments.executor
        NotesAttachments.executor = Executor { queue.addLast(it) }
    }

    @After fun tearDown() {
        controllers.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        drain()
        NotesAttachments.executor = originalExecutor
    }

    private fun open(): ActivityController<NotesActivity> = Robolectric.buildActivity(NotesActivity::class.java).setup().also { controllers += it }

    private fun close(controller: ActivityController<NotesActivity>) = controller.pause().stop().destroy()

    private fun drain() {
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val next = queue.removeFirstOrNull() ?: break
            next.run()
        }
    }

    private fun editor(controller: ActivityController<NotesActivity>) = controller.get().findViewById<EditText>(R.id.notes_body)

    private fun serve(vararg streams: () -> InputStream) {
        var call = 0
        shadowOf(context.contentResolver).registerInputStreamSupplier(imageUri) { streams[minOf(call++, streams.lastIndex)]() }
    }

    private fun commitImage(editor: EditText, mimeType: String = "image/png"): Boolean =
        editor.onCreateInputConnection(EditorInfo())!!.commitContent(
            InputContentInfo(imageUri, ClipDescription("Image", arrayOf(mimeType))), 0, null)

    private fun notes() = NotesRepository(context.getSharedPreferences("notes", Context.MODE_PRIVATE))

    private fun newNote(controller: ActivityController<NotesActivity>) {
        val buttons = ArrayList<View>()
        controller.get().window.decorView.findViewsWithText(buttons, context.getString(R.string.notes_new), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        buttons.single().performClick()
    }

    private val reference = Regex("!\\[Image]\\(attachments/([0-9a-f]{32}\\.png)\\)")

    @Test fun styledTextFromKeyboardIsStoredAsMarkdownAndRendersAfterReopening() {
        val controller = open()
        val styled = SpannableStringBuilder("bold italic link plain").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.ITALIC), 5, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(URLSpan("https://pkb.rocks"), 12, 16, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(UnderlineSpan(), 17, 22, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val editor = editor(controller)
        editor.onCreateInputConnection(EditorInfo())!!.commitText(styled, 1)
        val markdown = "**bold** *italic* [link](https://pkb.rocks) plain"
        assertEquals(markdown, editor.text.toString())
        assertEquals(editor.length(), editor.selectionStart)
        assertTrue(editor.text.getSpans(0, editor.length(), StyleSpan::class.java).isEmpty())
        close(controller)

        val reopened = open()
        assertEquals(markdown, editor(reopened).text.toString())
        reopened.get().findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        val preview = reopened.get().findViewById<TextView>(R.id.notes_preview_body).text as Spanned
        assertEquals("bold italic link plain", preview.toString())
        assertEquals(1, preview.getSpans(0, 4, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java).size)
        assertEquals(1, preview.getSpans(5, 11, io.noties.markwon.core.spans.EmphasisSpan::class.java).size)
        assertEquals("https://pkb.rocks", preview.getSpans(12, 16, URLSpan::class.java).single().url)
    }

    @Test fun plainAndComposedTextStayUnchanged() {
        val controller = open()
        val editor = editor(controller)
        val connection = editor.onCreateInputConnection(EditorInfo())!!
        connection.commitText("*kept* [as](typed) ", 1)
        val composing = SpannableStringBuilder("word").apply { setSpan(UnderlineSpan(), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        connection.setComposingText(composing, 1)
        assertTrue(editor.text.getSpans(0, editor.length(), UnderlineSpan::class.java).isNotEmpty())
        connection.finishComposingText()
        assertEquals("*kept* [as](typed) word", editor.text.toString())
    }

    @Test fun editorAnnouncesTheImageTypesItStores() {
        val info = EditorInfo()
        editor(open()).onCreateInputConnection(info)
        assertTrue(info.contentMimeTypes!!.toList().containsAll(listOf("image/png", "image/jpeg", "image/gif", "image/webp")))
    }

    @Test fun imageStaysWithItsNoteWhenAnotherNoteOpensBeforeTheCopyEnds() {
        serve({ ByteArrayInputStream(NotesTestImages.PNG) })
        val controller = open()
        val editor = editor(controller)
        editor.setText("Hello ")
        editor.setSelection(editor.length())
        assertTrue(commitImage(editor))
        val name = reference.find(editor.text)!!.groupValues[1]
        val original = notes().active().id
        newNote(controller)
        assertEquals("", editor.text.toString())
        drain()

        assertEquals("", editor.text.toString())
        assertEquals("Hello ![Image](attachments/$name)", notes().all().single { it.id == original }.body)
        assertTrue(File(attachments, name).readBytes().contentEquals(NotesTestImages.PNG))
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        close(controller)

        drain()
        assertTrue(File(attachments, name).isFile)
    }

    @Test fun pasteActionWithImageClipImportsTheImage() {
        serve({ ByteArrayInputStream(NotesTestImages.PNG) })
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData("Image", arrayOf("image/png"), android.content.ClipData.Item(imageUri)))
        val controller = open()
        val editor = editor(controller)
        assertTrue(editor.onTextContextMenuItem(android.R.id.paste))
        val name = reference.find(editor.text)!!.groupValues[1]
        drain()
        assertTrue(File(attachments, name).readBytes().contentEquals(NotesTestImages.PNG))

        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Text", "plain"))
        editor.setText("")
        editor.onTextContextMenuItem(android.R.id.paste)
        assertEquals("plain", editor.text.toString())
    }

    @Test fun imagePasteIntoHeadingShowsFailureWithoutChangingTheNote() {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData("Image", arrayOf("image/png"), android.content.ClipData.Item(imageUri)))
        val controller = open()
        val heading = controller.get().findViewById<EditText>(R.id.notes_heading)
        heading.setText("Title")
        editor(controller).setText("Body")
        assertTrue(heading.onTextContextMenuItem(android.R.id.paste))
        assertEquals("Title", heading.text.toString())
        assertEquals("Body", editor(controller).text.toString())
        assertEquals(context.getString(R.string.clipboard_image_unsupported_field), ShadowToast.getTextOfLatestToast())
        assertTrue(queue.isEmpty())
    }

    @Test fun providerIsNotOpenedUntilTheBackgroundImportRuns() {
        var opened = false
        serve({ opened = true; ByteArrayInputStream(NotesTestImages.PNG) })
        val controller = open()
        assertTrue(commitImage(editor(controller)))
        assertFalse(opened)
        drain()
        assertTrue(opened)
        assertEquals(1, attachments.listFiles().orEmpty().size)
    }

    @Test fun storedImageSurvivesRestartAndShowsInThePreview() {
        serve({ ByteArrayInputStream(NotesTestImages.PNG) })
        val controller = open()
        assertTrue(commitImage(editor(controller)))
        drain()
        close(controller)
        drain()
        val reopened = open()
        assertTrue(reference.matches(editor(reopened).text))
        reopened.get().findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        drain()
        val preview = reopened.get().findViewById<TextView>(R.id.notes_preview_body).text as Spanned
        val span = preview.getSpans(0, preview.length, AsyncDrawableSpan::class.java).single()
        assertTrue(span.drawable.destination.startsWith("attachments/"))
        assertTrue(span.drawable.hasResult())
        assertFalse(span.drawable.result.bounds.isEmpty)
    }

    @Test fun previewLoadsNoForeignSources() {
        val controller = open()
        editor(controller).setText("![a](file:///data/data/rocks.pkb.plektra/shared_prefs/notes.xml)\n\n" +
            "![b](https://example.com/a.png)\n\n![c](attachments/../shared_prefs/notes.xml)")
        controller.get().findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        val preview = controller.get().findViewById<TextView>(R.id.notes_preview_body).text as Spanned
        val spans = preview.getSpans(0, preview.length, AsyncDrawableSpan::class.java)
        assertEquals(3, spans.size)
        assertTrue(queue.isEmpty())
        assertTrue(spans.none { it.drawable.hasResult() })
    }

    @Test fun failedCopyRemovesTheReferenceFromItsNoteAndShowsTheError() {
        serve({ object : InputStream() {
            override fun read(): Int = throw IOException("gone")
        } })
        val controller = open()
        val editor = editor(controller)
        editor.setText("Kept")
        editor.setSelection(editor.length())
        assertTrue(commitImage(editor))
        val original = notes().active().id
        newNote(controller)
        drain()

        assertEquals("Kept", notes().all().single { it.id == original }.body)
        assertTrue(attachments.listFiles().orEmpty().isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertEquals(context.getString(R.string.notes_image_import_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun failedCopyInTheOpenNoteRemovesItsReferenceFromTheEditor() {
        serve({ ByteArrayInputStream(ByteArray(0)) })
        val controller = open()
        val editor = editor(controller)
        editor.setText("Before  after")
        editor.setSelection(7)
        assertTrue(commitImage(editor))
        drain()
        assertEquals("Before  after", editor.text.toString())
        assertTrue(attachments.listFiles().orEmpty().isEmpty())
        controller.get().findViewById<View>(R.id.notes_undo_button).performClick()
        controller.get().findViewById<View>(R.id.notes_redo_button).performClick()
        assertFalse(editor.text.contains("attachments/"))
    }

    @Test fun failedImagePasteRestoresReplacedSelectionInTheOriginalNote() {
        serve({ ByteArrayInputStream(ByteArray(0)) })
        val controller = open()
        val editor = editor(controller)
        editor.setText("Before selected after")
        editor.setSelection(7, 15)
        assertTrue(commitImage(editor))
        val original = notes().active().id
        newNote(controller)
        drain()
        assertEquals("Before selected after", notes().all().single { it.id == original }.body)
        assertTrue(attachments.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedImagePasteRestoresSelectionWithoutResurrectingItThroughUndo() {
        serve({ ByteArrayInputStream(ByteArray(0)) })
        val controller = open()
        val editor = editor(controller)
        editor.setText("Before selected after")
        editor.setSelection(7, 15)
        assertTrue(commitImage(editor))
        drain()
        assertEquals("Before selected after", editor.text.toString())
        controller.get().findViewById<View>(R.id.notes_undo_button).performClick()
        controller.get().findViewById<View>(R.id.notes_redo_button).performClick()
        assertEquals("Before selected after", editor.text.toString())
    }

    @Test fun invalidContentIsRefusedWithoutTouchingTheNote() {
        serve({ ByteArrayInputStream("<html>not an image</html>".toByteArray()) })
        val controller = open()
        val editor = editor(controller)
        editor.setText("Text")
        assertTrue(commitImage(editor))
        drain()
        assertEquals(context.getString(R.string.notes_image_unsupported), ShadowToast.getTextOfLatestToast())
        assertFalse(commitImage(editor, mimeType = "text/plain"))
        assertEquals("Text", editor.text.toString())
        assertTrue(queue.isEmpty())
        assertTrue(attachments.listFiles().orEmpty().isEmpty())
    }

    @Test fun undoneImageIsDeletedOnceNoNoteOrHistoryRefersToIt() {
        serve({ ByteArrayInputStream(NotesTestImages.PNG) })
        val controller = open()
        val editor = editor(controller)
        assertTrue(commitImage(editor))
        val name = reference.find(editor.text)!!.groupValues[1]
        controller.get().findViewById<View>(R.id.notes_undo_button).performClick()
        assertEquals("", editor.text.toString())
        drain()
        // Redo still refers to the image, so it stays while the editor is open.
        assertTrue(File(attachments, name).isFile)
        close(controller)
        drain()
        assertFalse(File(attachments, name).exists())
    }
}
