package rocks.pkb.plektra.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.widget.TextView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ui.ClipboardHistoryView

/** Formatted text and images in the clipboard history: capture, storage, limits and paste targets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardRichContentTest {
    private lateinit var context: Context
    private lateinit var clipboard: ClipboardManager
    private lateinit var store: ClipboardTestStore
    private val captureWorker = QueueExecutor()
    private var sourceBytes: () -> java.io.InputStream? = { null }
    private lateinit var capture: ClipboardImageCapture
    private var accessible = true
    private val managers = mutableListOf<ClipboardHistoryManager>()
    private val retention get() = ClipboardRetention.fromSettings(context)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().remove("clipboard_paste_plain_text").commit()
        SettingsManager.setClipboardHistoryEnabled(context, true)
        SettingsManager.setClipboardImageHistoryEnabled(context, true)
        SettingsManager.setClipboardRetentionTime(context, 0)
        SettingsManager.setSensitiveClipboardRetentionTime(context, 30)
        clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        // The real image directory, because the content provider serves only that one.
        store = ClipboardTestStore(context, "clipboard-rich-test.db", File(context.filesDir, ClipboardImageStore.DIRECTORY))
        capture = ClipboardImageCapture({ sourceBytes() }, store.images.stagingDirectory, captureWorker, Executor { it.run() })
        captureWorker.runAll()
    }

    @After
    fun tearDown() {
        managers.forEach { it.onDestroy() }
        store.close()
        clipboard.clearPrimaryClip()
        SettingsManager.getPreferences(context).edit().remove("clipboard_paste_plain_text").commit()
        SettingsManager.setClipboardImageHistoryEnabled(context, true)
        SettingsManager.setClipboardRetentionTime(context, 0)
    }

    private fun manager(limits: ClipboardImageLimits = ClipboardImageLimits.DEFAULT) =
        ClipboardHistoryManager(context, { accessible }, store.history, capture, limits) { store.now }.also {
            managers += it
            it.onCreate()
            clipboard.removePrimaryClipChangedListener(it)
            store.settle()
        }

    private fun setClip(clip: ClipData) {
        clipboard.setPrimaryClip(clip)
        ReflectionHelpers.callInstanceMethod<Unit>(
            clipboard.primaryClip!!.description, "setTimestamp",
            ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, store.now)
        )
    }

    private fun copy(manager: ClipboardHistoryManager, clip: ClipData) {
        setClip(clip)
        manager.onPrimaryClipChanged()
        captureWorker.runAll()
        store.settle()
    }

    private fun copyImage(manager: ClipboardHistoryManager, bytes: ByteArray, sensitive: Boolean = false) {
        sourceBytes = { ByteArrayInputStream(bytes) }
        copy(manager, ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(Uri.parse("content://source.test/image"))).apply {
            if (sensitive) description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        })
    }

    private fun png(color: Int, width: Int = 40, height: Int = 30): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun textEditor() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }

    private fun imageEditor(vararg types: String) = textEditor().apply { EditorInfoCompat.setContentMimeTypes(this, arrayOf(*types)) }

    // --- Schema ---

    @Test
    fun upgradeFromVersionTwoKeepsTextAndAddsEmptyRichColumns() {
        val name = "clipboard-rich-upgrade.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE CLIPBOARD (ID INTEGER PRIMARY KEY, TIMESTAMP INTEGER NOT NULL, PINNED TINYINT NOT NULL, TEXT TEXT, SENSITIVE TINYINT NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO CLIPBOARD VALUES (7, 123, 1, 'kept-text', 0)")
            db.version = 2
        }
        val database = ClipboardDatabase(context, name)
        try {
            val entry = ClipboardDao(database).loadAll().single()
            assertEquals(ClipboardHistoryEntry(7, 123, true, "kept-text"), entry)
            assertEquals(3, database.readableDatabase.version)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    // --- Formatted text ---

    @Test
    fun formattingIsStoredEvenWhenPastingUnformattedByDefault() {
        val manager = manager()
        assertTrue(manager.pastesPlainText())
        copy(manager, ClipData.newHtmlText("rich", "Bold and link", "<b>Bold</b> and <a href=\"https://example.org\">link</a>"))

        val entry = manager.getHistoryEntries().single()
        assertEquals("Bold and link", entry.text)
        assertNotNull(entry.html)
        val restarted = store.restart()
        restarted.start(retention)
        store.settle()
        assertEquals(entry.html, restarted.snapshot.entries.single().html)

        val connection = RecordingConnection(context)
        assertEquals(ClipboardHistoryManager.PasteResult.PASTED, manager.pasteEntry(entry.id, connection, textEditor()))
        val pasted = connection.texts.single()
        assertEquals("Bold and link", pasted.toString())
        assertFalse(pasted is Spanned && pasted.getSpans(0, pasted.length, Any::class.java).isNotEmpty())
    }

    @Test
    fun turningUnformattedPasteOffPastesStoredFormattingWithPlainFallbacks() {
        val manager = manager()
        copy(manager, ClipData.newHtmlText("rich", "Bold text", "<b>Bold</b> text <span style=\"color:#ff0000\">red</span>"))
        val entry = manager.getHistoryEntries().single()

        manager.setPastesPlainText(false)
        assertFalse(SettingsManager.getClipboardPastePlainText(context))

        val styled = RecordingConnection(context)
        manager.pasteEntry(entry.id, styled, textEditor())
        val text = styled.texts.single() as Spanned
        assertEquals("Bold text red", text.toString())
        val bold = text.getSpans(0, text.length, StyleSpan::class.java).single()
        assertEquals(0, text.getSpanStart(bold))
        assertEquals(4, text.getSpanEnd(bold))
        assertTrue("foreign colours are dropped", text.getSpans(0, text.length, ForegroundColorSpan::class.java).isEmpty())

        // Fields where formatting makes no sense, unknown editors and the explicit choice get plain text.
        val password = RecordingConnection(context)
        manager.pasteEntry(entry.id, password, EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        val unknown = RecordingConnection(context)
        manager.pasteEntry(entry.id, unknown)
        val plain = RecordingConnection(context)
        manager.pasteEntry(entry.id, plain, textEditor(), ClipboardHistoryManager.Formatting.PLAIN)
        listOf(password, unknown, plain).forEach { assertEquals("Bold text", it.texts.single().toString()) }

        manager.setPastesPlainText(true)
        val styledOnRequest = RecordingConnection(context)
        manager.pasteEntry(entry.id, styledOnRequest, textEditor(), ClipboardHistoryManager.Formatting.STYLED)
        assertTrue(styledOnRequest.texts.single() is Spanned)
    }

    @Test
    fun spannedClipsAreStoredAsHtmlWithoutUnsafeLinks() {
        val manager = manager()
        val text = SpannableString("safe unsafe").apply {
            setSpan(URLSpan("https://example.org"), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(URLSpan("javascript:alert(1)"), 5, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        copy(manager, ClipData.newPlainText("spanned", text))
        val html = requireNotNull(manager.getHistoryEntries().single().html)
        assertTrue(html.contains("https://example.org"))
        assertFalse(html.contains("javascript"))
    }

    @Test
    fun largeFormattedTextSurvivesChunkedLoadingAndLaterPlainCopyReplacesFormatting() {
        manager()
        val body = "\u0000ä😀".repeat(5_000)
        store.history.addClip(body, store.now, false, retention, "<b>$body</b>")
        store.settle()
        val restarted = store.restart(chunkBytes = 7)
        restarted.start(retention)
        store.settle()
        assertEquals("<b>$body</b>", restarted.snapshot.entries.single().html)
        assertEquals(body, restarted.snapshot.entries.single().text)

        restarted.addClip(body, store.now + 1, false, retention, null)
        store.settle()
        assertNull(restarted.snapshot.entries.single().html)
    }

    @Test
    fun switchesInBothViewsShareOnePersistentSetting() {
        val manager = manager()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, manager)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        val inlineSwitch = findSwitch(view)
        assertTrue(inlineSwitch.isChecked)

        val popup = ClipboardHistoryPopupView(activity, manager)
        try {
            popup.show()
            val popupSwitch = findSwitch(popup.javaClass.getDeclaredField("contentView").run {
                isAccessible = true
                get(popup) as View
            })
            assertTrue(popupSwitch.isChecked)
            popupSwitch.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(SettingsManager.getClipboardPastePlainText(context))
            assertFalse(inlineSwitch.isChecked)
        } finally {
            popup.dismiss()
        }
    }

    @Test
    fun ctrlVPastesNormallyAndCtrlShiftVFollowsItsSetting() {
        SettingsManager.setClipboardPastePlainText(context, true)
        assertEquals(android.R.id.paste, ClipboardNativePaste.actionId(context, shiftActive = false))
        assertEquals(android.R.id.pasteAsPlainText, ClipboardNativePaste.actionId(context, shiftActive = true))
        SettingsManager.setClipboardCtrlShiftVPlainText(context, false)
        assertEquals(android.R.id.paste, ClipboardNativePaste.actionId(context, shiftActive = true))
    }

    // --- Image formats and capture ---

    @Test
    fun formatsAreRecognisedFromContentAndMatchedAgainstEditorTypes() {
        assertEquals(ClipboardImageFormat.PNG, ClipboardImageFormat.sniff(png(Color.RED)))
        assertEquals(ClipboardImageFormat.JPEG, ClipboardImageFormat.sniff(byteArrayOf(-1, -40, -1, -32)))
        assertEquals(ClipboardImageFormat.GIF, ClipboardImageFormat.sniff("GIF89a".toByteArray()))
        assertEquals(ClipboardImageFormat.WEBP, ClipboardImageFormat.sniff("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
        assertNull(ClipboardImageFormat.sniff("<svg/>".toByteArray()))

        assertTrue(ClipboardImageFormat.accepts(arrayOf("image/*"), ClipboardImageFormat.PNG))
        assertTrue(ClipboardImageFormat.accepts(arrayOf("image/gif", "image/png"), ClipboardImageFormat.PNG))
        assertFalse(ClipboardImageFormat.accepts(arrayOf("image/gif"), ClipboardImageFormat.PNG))
        assertFalse(ClipboardImageFormat.accepts(emptyArray(), ClipboardImageFormat.PNG))
    }

    @Test
    fun rejectedImagesAreReportedAndLeaveNoFiles() {
        val manager = manager(ClipboardImageLimits(maxImageBytes = 200, maxImages = 5, maxTotalBytes = 10_000))
        copyImage(manager, ByteArray(201) { 1 })
        assertEquals(context.getString(R.string.clipboard_image_too_large, 0), ShadowToast.getTextOfLatestToast())
        copyImage(manager, "not an image".toByteArray())
        assertEquals(context.getString(R.string.clipboard_image_unsupported_format), ShadowToast.getTextOfLatestToast())
        copyImage(manager, png(Color.RED).copyOf(16))
        assertEquals(context.getString(R.string.clipboard_image_unreadable), ShadowToast.getTextOfLatestToast())
        sourceBytes = { throw SecurityException("grant revoked") }
        copy(manager, ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(Uri.parse("content://source.test/gone"))))
        assertEquals(context.getString(R.string.clipboard_image_unreadable), ShadowToast.getTextOfLatestToast())

        assertTrue(manager.getHistoryEntries().isEmpty())
        assertTrue(store.imageFiles().isEmpty())
        assertTrue(store.images.stagingDirectory.list().isNullOrEmpty())
        assertTrue("an image URI is never stored as text", store.rows().isEmpty())
    }

    @Test
    fun disabledImageHistoryStoresNeitherImageNorItsUri() {
        SettingsManager.setClipboardImageHistoryEnabled(context, false)
        val manager = manager()
        copyImage(manager, png(Color.RED))
        assertTrue(manager.getHistoryEntries().isEmpty())
        assertTrue(store.rows().isEmpty())
    }

    // --- Stored images ---

    @Test
    fun clearHistoryDiscardsAnImageWhoseCaptureFinishesLater() {
        val manager = manager()
        sourceBytes = { ByteArrayInputStream(png(Color.RED)) }
        setClip(ClipData(ClipDescription("image", arrayOf("image/png")),
            ClipData.Item(Uri.parse("content://source.test/image"))))
        manager.onPrimaryClipChanged()
        manager.clearHistory()
        captureWorker.runAll()
        store.settle()
        assertTrue(manager.getHistoryEntries().isEmpty())
        assertTrue(store.images.directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.images.stagingDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun disablingImageHistoryCancelsPendingCaptureEvenWhenEnabledAgain() {
        val manager = manager()
        sourceBytes = { ByteArrayInputStream(png(Color.RED)) }
        setClip(ClipData(ClipDescription("image", arrayOf("image/png")),
            ClipData.Item(Uri.parse("content://source.test/image"))))
        manager.onPrimaryClipChanged()
        SettingsManager.setClipboardImageHistoryEnabled(context, false)
        SettingsManager.setClipboardImageHistoryEnabled(context, true)
        captureWorker.runAll()
        store.settle()
        assertTrue(manager.getHistoryEntries().isEmpty())
        assertTrue(store.images.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun delayedThumbnailCannotRevealAnImageThatHasBecomeSensitive() {
        val manager = manager()
        val bytes = png(Color.RED)
        copyImage(manager, bytes)
        val entry = manager.getHistoryEntries().single()
        ReflectionHelpers.getField<ClipboardThumbnailLoader>(manager, "thumbnails").shutdown()
        val callbacks = QueueExecutor()
        val decoder = java.util.concurrent.Executors.newSingleThreadExecutor()
        val loader = ClipboardThumbnailLoader(callbacks, decoder)
        ReflectionHelpers.setField(manager, "thumbnails", loader)
        var delivered = false
        var preview: Bitmap? = null
        manager.loadThumbnail(entry, 64) { delivered = true; preview = it }
        decoder.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse(delivered)
        copyImage(manager, bytes, sensitive = true)
        assertTrue(manager.getHistoryEntries().single().isSensitive)
        callbacks.runAll()
        assertTrue(delivered)
        assertNull(preview)
    }

    @Test
    fun imagesPersistAcrossRestartAndRepeatedCopiesDoNotDuplicate() {
        val manager = manager()
        copyImage(manager, png(Color.RED))
        copyImage(manager, png(Color.RED))
        val entry = manager.getHistoryEntries().single()
        val image = requireNotNull(entry.image)
        assertEquals(ClipboardImageFormat.PNG, image.mimeType)
        assertEquals(40, image.width)
        assertEquals(30, image.height)
        assertEquals(setOf(image.asset), store.imageFiles())
        assertTrue(store.images.stagingDirectory.list().isNullOrEmpty())

        val restarted = store.restart()
        restarted.start(retention)
        store.settle()
        assertEquals(entry, restarted.snapshot.entries.single())
        assertTrue(store.images.exists(image.asset))
    }

    @Test
    fun loadRemovesFilesWithoutRowsAndRowsWithoutFiles() {
        val manager = manager()
        copyImage(manager, png(Color.RED))
        copyImage(manager, png(Color.BLUE))
        val (first, second) = manager.getHistoryEntries()
        File(store.images.directory, "orphan.png").writeBytes(png(Color.GREEN))
        store.images.file(second.image!!.asset).delete()

        val restarted = store.restart()
        restarted.start(retention)
        store.settle()
        assertEquals(listOf(first.id), restarted.snapshot.entries.map { it.id })
        assertEquals(setOf(first.image!!.asset), store.imageFiles())
        assertEquals(listOf(first.id), store.rows().map { it.id })
    }

    @Test
    fun deleteClearAndExpiryRemoveFilesButPinnedImagesStay() {
        val manager = manager()
        copyImage(manager, png(Color.RED))
        store.now += 1
        copyImage(manager, png(Color.BLUE))
        store.now += 1
        copyImage(manager, png(Color.GREEN))
        val (green, blue, red) = manager.getHistoryEntries()

        manager.removeEntry(green.id, force = true)
        store.settle()
        assertFalse(store.images.exists(green.image!!.asset))

        manager.toggleClipPinned(red.id)
        store.settle()
        manager.clearHistory()
        store.settle()
        assertEquals(listOf(red.id), manager.getHistoryEntries().map { it.id })
        assertEquals(setOf(red.image!!.asset), store.imageFiles())
        assertFalse(store.images.exists(blue.image!!.asset))

        manager.toggleClipPinned(red.id)
        store.settle()
        SettingsManager.setClipboardRetentionTime(context, 1)
        store.now += 2 * 60_000L
        store.settle()
        manager.onHistoryOpened()
        store.settle()
        assertTrue(manager.getHistoryEntries().isEmpty())
        assertTrue(store.imageFiles().isEmpty())
    }

    @Test
    fun limitsReplaceOldestUnpinnedImagesAndNeverPinnedOnes() {
        store.imageLimits = ClipboardImageLimits(maxImageBytes = 1_000_000, maxImages = 2, maxTotalBytes = 1_000_000)
        store.restart()
        val manager = manager()
        copyImage(manager, png(Color.RED))
        store.now += 1
        copyImage(manager, png(Color.BLUE))
        store.now += 1
        val (blue, red) = manager.getHistoryEntries()
        copyImage(manager, png(Color.GREEN))
        val afterEviction = manager.getHistoryEntries()
        assertEquals(2, afterEviction.size)
        assertFalse(afterEviction.any { it.id == red.id })
        assertFalse(store.images.exists(red.image!!.asset))

        afterEviction.forEach { manager.toggleClipPinned(it.id) }
        store.settle()
        store.now += 1
        copyImage(manager, png(Color.YELLOW))
        assertEquals(context.getString(R.string.clipboard_image_limit_reached), ShadowToast.getTextOfLatestToast())
        assertEquals(afterEviction.map { it.id }.toSet(), manager.getHistoryEntries().map { it.id }.toSet())
        assertEquals(afterEviction.map { it.image!!.asset }.toSet(), store.imageFiles())
        assertTrue(store.images.stagingDirectory.list().isNullOrEmpty())
        assertTrue(blue.id in afterEviction.map { it.id })
    }

    @Test
    fun failedInsertRemovesTheAdoptedFile() {
        val manager = manager()
        store.database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_insert BEFORE INSERT ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'blocked'); END"
        )
        copyImage(manager, png(Color.RED))
        assertEquals(context.getString(R.string.clipboard_save_failed), ShadowToast.getTextOfLatestToast())
        assertTrue(manager.getHistoryEntries().isEmpty())
        assertTrue(store.imageFiles().isEmpty())
        assertTrue(store.images.stagingDirectory.list().isNullOrEmpty())
    }

    @Test
    fun thumbnailsAreSampledDown() {
        assertEquals(32, ClipboardThumbnailLoader.sampleSize(1080, 2400, 64))
        assertEquals(1, ClipboardThumbnailLoader.sampleSize(100, 100, 64))
        val file = File(context.cacheDir, "thumbnail-source.png").apply { writeBytes(png(Color.RED, 1000, 400)) }
        val bitmap = requireNotNull(ClipboardThumbnailLoader.decode(file, 64))
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 128)
        file.delete()
    }

    // --- Image paste ---

    @Test
    fun imagesAreCommittedAsContentOnlyToEditorsThatAcceptThem() {
        SettingsManager.setClipboardPastePlainText(context, true)
        val manager = manager()
        copyImage(manager, png(Color.RED))
        val entry = manager.getHistoryEntries().single()

        val accepting = RecordingConnection(context)
        assertEquals(ClipboardHistoryManager.PasteResult.PASTED, manager.pasteEntry(entry.id, accepting, imageEditor("image/*")))
        val (content, flags) = accepting.contents.single()
        assertEquals(ClipboardImageProvider.authority(context), content.contentUri.authority)
        assertTrue(content.description.hasMimeType(ClipboardImageFormat.PNG))
        assertTrue(flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(accepting.texts.isEmpty())

        val textOnly = RecordingConnection(context)
        assertEquals(ClipboardHistoryManager.PasteResult.UNSUPPORTED, manager.pasteEntry(entry.id, textOnly, textEditor()))
        assertEquals(ClipboardHistoryManager.PasteResult.UNSUPPORTED, manager.pasteEntry(entry.id, textOnly, imageEditor("image/gif")))
        assertTrue("never the URI as text", textOnly.texts.isEmpty() && textOnly.contents.isEmpty())
        assertEquals(context.getString(R.string.clipboard_image_unsupported_field), ShadowToast.getTextOfLatestToast())

        val refusing = RecordingConnection(context, acceptContent = false)
        assertEquals(ClipboardHistoryManager.PasteResult.FAILED, manager.pasteEntry(entry.id, refusing, imageEditor("image/png")))
        assertEquals(context.getString(R.string.clipboard_image_paste_failed), ShadowToast.getTextOfLatestToast())

        accessible = false
        val locked = RecordingConnection(context)
        assertEquals(ClipboardHistoryManager.PasteResult.UNAVAILABLE, manager.pasteEntry(entry.id, locked, imageEditor("image/png")))
        assertTrue(locked.contents.isEmpty())
    }

    @Test
    fun pastesFollowTheEditorConnectedAtTheMomentOfThePaste() {
        val manager = manager()
        copyImage(manager, png(Color.RED))
        val entry = manager.getHistoryEntries().single()
        val first = RecordingConnection(context)
        val second = RecordingConnection(context)
        val stale = RecordingConnection(context)
        var target: ClipboardHistoryManager.PasteTarget? = ClipboardHistoryManager.PasteTarget(first, imageEditor("image/png"))
        manager.pasteTargetProvider = ClipboardHistoryManager.PasteTargetProvider { target }

        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, manager).apply { setInputConnection(stale) }
        activity.setContentView(view)
        layout(view)
        assertFalse(visibleTexts(view).any { it.contains(context.getString(R.string.clipboard_image_not_accepted_here)) })

        // The user saw an editor that takes images; another editor connects before the tap.
        target = ClipboardHistoryManager.PasteTarget(second, textEditor())
        manager.onEditorChanged()
        layout(view)
        assertTrue(visibleTexts(view).any { it.contains(context.getString(R.string.clipboard_image_not_accepted_here)) })
        assertEquals(ClipboardHistoryManager.PasteResult.UNSUPPORTED, manager.pasteEntryIntoCurrentEditor(entry.id, fallbackConnection = stale))
        assertTrue(first.contents.isEmpty() && second.contents.isEmpty() && stale.contents.isEmpty())

        target = null
        assertEquals(ClipboardHistoryManager.PasteResult.NO_EDITOR, manager.pasteEntryIntoCurrentEditor(entry.id, fallbackConnection = stale))

        target = ClipboardHistoryManager.PasteTarget(second, imageEditor("image/png"))
        manager.removeEntry(entry.id, force = true)
        store.settle()
        assertEquals(ClipboardHistoryManager.PasteResult.UNAVAILABLE, manager.pasteEntryIntoCurrentEditor(entry.id))
        assertTrue(second.contents.isEmpty())
    }

    @Test
    fun sensitiveImagesAreMaskedUnpinnableAndExpire() {
        val manager = manager()
        copyImage(manager, png(Color.RED), sensitive = true)
        val entry = manager.getHistoryEntries().single()
        assertTrue(entry.isSensitive)
        manager.toggleClipPinned(entry.id)
        store.settle()
        assertFalse(manager.getHistoryEntries().single().isPinned)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = ClipboardHistoryView(activity, manager)
        activity.setContentView(view)
        layout(view)
        val texts = visibleTexts(view)
        assertTrue(texts.any { it.startsWith(context.getString(R.string.clipboard_sensitive_image_preview)) })
        assertFalse(texts.any { it.contains("40 × 30") })
        val previews = imageViews(view).filter { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        assertEquals(1, previews.size)
        assertNull(previews.single().drawable)

        store.now += 31 * 60_000L
        manager.onHistoryOpened()
        store.settle()
        assertTrue(manager.getHistoryEntries().isEmpty())
        assertTrue(store.imageFiles().isEmpty())
    }

    private fun layout(view: View) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun visibleTexts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
            else -> emptyList()
        }
    }

    private fun imageViews(view: View): List<android.widget.ImageView> = when (view) {
        is android.widget.ImageView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { imageViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun findSwitch(view: View): android.widget.Switch = when (view) {
        is android.widget.Switch -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOf { runCatching { findSwitch(view.getChildAt(it)) }.getOrNull() }
        else -> throw NoSuchElementException()
    }

    private class RecordingConnection(context: Context, private val acceptContent: Boolean = true) :
        BaseInputConnection(View(context), true) {
        val texts = mutableListOf<CharSequence>()
        val contents = mutableListOf<Pair<InputContentInfo, Int>>()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            texts += text ?: ""
            return true
        }

        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
            contents += inputContentInfo to flags
            return acceptContent
        }
    }
}
