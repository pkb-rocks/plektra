package rocks.pkb.plektra.clipboard

import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import java.util.concurrent.Executor
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager

/**
 * Connects the Android clipboard and the history UI to [ClipboardHistory].
 * Reads are pure functions of the latest published snapshot, the device lock state and the
 * current time; changes are requests by stable entry id that the history validates again
 * when it executes them.
 *
 * Pastes go to the editor the IME is connected to at the moment of the paste, as reported by
 * [pasteTargetProvider]; nothing is delivered asynchronously, so a paste cannot reach an
 * editor that replaced the one the user saw.
 */
class ClipboardHistoryManager internal constructor(
    private val context: Context,
    private val accessPolicy: ClipboardHistoryAccessPolicy,
    private val history: ClipboardHistory,
    private val imageCapture: ClipboardImageCapture = ClipboardImageCapture.getInstance(context),
    private val imageLimits: ClipboardImageLimits = ClipboardImageLimits.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis
) : ClipboardManager.OnPrimaryClipChangedListener {

    constructor(context: Context) : this(
        context,
        SystemClipboardHistoryAccessPolicy(context),
        ClipboardHistory.getInstance(context)
    )

    /** What the history UI may show right now. */
    enum class Status { LOCKED, LOADING, ERROR, READY }

    data class UiState(val status: Status, val entries: List<ClipboardHistoryEntry>)

    /** The editor a paste goes to. */
    class PasteTarget(val inputConnection: InputConnection, val editorInfo: EditorInfo?)

    fun interface PasteTargetProvider {
        /** The editor the IME is connected to now, or null while it has none. */
        fun currentTarget(): PasteTarget?
    }

    enum class PasteResult {
        PASTED,
        /** The history is locked or the entry is gone or expired. */
        UNAVAILABLE,
        /** No editor is connected. */
        NO_EDITOR,
        /** The editor does not accept this kind of content. */
        UNSUPPORTED,
        /** The editor or the stored file refused the content. */
        FAILED
    }

    /** How formatted text is pasted. */
    enum class Formatting {
        /** As chosen by [pastesPlainText]. */
        DEFAULT,
        PLAIN,
        /** Styled where the editor is a general text field; plain elsewhere. */
        STYLED
    }

    /** Set by the IME; views resolve the editor through it at the moment of a paste. */
    var pasteTargetProvider: PasteTargetProvider? = null

    /** Changes whenever the connected editor changes; views compare it to redraw paste availability. */
    var editorGeneration: Int = 0
        private set

    private lateinit var clipboardManager: ClipboardManager
    private var isEnabled: Boolean = true
    private var deviceStateReceiverRegistered = false
    private var screenOffReceived = false
    private var lastHistoryAccessible: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expiryCleanup = Runnable { history.purgeExpired(retention(), republish = true) }
    private val historyChangeListeners = linkedSetOf<() -> Unit>()
    private val snapshotListener: (ClipboardHistory.Snapshot) -> Unit = { snapshot ->
        scheduleExpiryCleanup(snapshot.nextExpiry)
        historyChangeListeners.toList().forEach { it() }
    }

    private val failureListener: (ClipboardHistory.Operation) -> Unit = { operation ->
        when (operation) {
            ClipboardHistory.Operation.SAVE -> showError(R.string.clipboard_save_failed)
            ClipboardHistory.Operation.PIN -> showError(R.string.clipboard_pin_failed)
            ClipboardHistory.Operation.DELETE -> showError(R.string.clipboard_delete_failed)
            ClipboardHistory.Operation.CLEAR -> showError(R.string.clipboard_clear_failed)
            // Background cleanup is retried by the expiry timer; a toast would only repeat.
            ClipboardHistory.Operation.CLEANUP -> Unit
        }
    }
    private val noticeListener: (ClipboardHistory.Notice) -> Unit = { notice ->
        when (notice) {
            ClipboardHistory.Notice.IMAGE_LIMIT_REACHED -> showError(R.string.clipboard_image_limit_reached)
        }
    }
    private var errorToast: Toast? = null
    private var destroyed = false
    private var captureGeneration = 0L
    private var thumbnails: ClipboardThumbnailLoader? = null

    // SharedPreferences holds listeners weakly, so this one stays a field.
    private val retentionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (SettingsManager.isClipboardRetentionKey(key)) reapplyRetention()
        if (SettingsManager.isClipboardHistoryEnabledKey(key)) isEnabled = getClipboardHistoryEnabled()
        if (!isEnabled || !SettingsManager.getClipboardImageHistoryEnabled(context)) captureGeneration++
    }

    private val accessStateListeners = linkedSetOf<(Boolean) -> Unit>()
    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_TIME_CHANGED) {
                // The expiry timer counts uptime; a wall-clock jump moves the actual expiry times.
                reapplyRetention()
                return
            }
            screenOffReceived = intent?.action == Intent.ACTION_SCREEN_OFF
            refreshAccessState()
        }
    }

    fun onCreate() {
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        history.addListener(snapshotListener)
        history.addFailureListener(failureListener)
        history.addNoticeListener(noticeListener)
        destroyed = false
        thumbnails = ClipboardThumbnailLoader(Executor { mainHandler.post(it) })
        SettingsManager.getPreferences(context).registerOnSharedPreferenceChangeListener(retentionListener)
        registerDeviceStateReceiver()
        lastHistoryAccessible = isHistoryAccessible()

        // Check if history is enabled
        isEnabled = getClipboardHistoryEnabled()

        // Queued first, so copies captured below wait for the load.
        history.start(retention())
        // An already loaded history publishes nothing on start; this schedules expiry for this manager.
        reapplyRetention()
        if (isEnabled) {
            fetchPrimaryClip()
        }
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        if (deviceStateReceiverRegistered) {
            runCatching { context.unregisterReceiver(deviceStateReceiver) }
            deviceStateReceiverRegistered = false
        }
        history.removeListener(snapshotListener)
        history.removeFailureListener(failureListener)
        history.removeNoticeListener(noticeListener)
        destroyed = true
        captureGeneration++
        thumbnails?.shutdown()
        thumbnails = null
        pasteTargetProvider = null
        SettingsManager.getPreferences(context).unregisterOnSharedPreferenceChangeListener(retentionListener)
        mainHandler.removeCallbacks(expiryCleanup)
        errorToast?.cancel()
        errorToast = null
        accessStateListeners.clear()
        historyChangeListeners.clear()
    }

    override fun onPrimaryClipChanged() {
        if (!isEnabled || !isHistoryAccessible()) return
        fetchPrimaryClip()
    }

    private fun fetchPrimaryClip() {
        if (!isHistoryAccessible()) return
        val clipData = clipboardManager.primaryClip ?: return
        val sensitive = clipData.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        if (clipData.itemCount == 0) return
        val timeStamp = clipData.description.timestamp.takeIf { it > 0 } ?: clock()

        // An image clip is never stored as text: coercing its URI would only yield the URI.
        if (ClipboardImageFormat.announcesImage(clipData.description)) {
            val uri = (0 until clipData.itemCount).firstNotNullOfOrNull { clipData.getItemAt(it).uri }
            if (uri != null) {
                if (SettingsManager.getClipboardImageHistoryEnabled(context)) captureImage(uri, timeStamp, sensitive)
                return
            }
        }
        if (clipData.description?.hasMimeType("text/*") == false) return

        clipData.getItemAt(0)?.let { clipItem ->
            val content = clipItem.coerceToText(context)
            if (TextUtils.isEmpty(content)) return
            if (!isHistoryAccessible()) return

            history.addClip(content.toString(), timeStamp, sensitive, retention(), ClipboardRichText.htmlOf(clipItem))
        }
    }

    /**
     * Starts copying the image now, while the clipboard's read grant for [uri] is valid; the
     * history stores it once the copy is validated.
     */
    private fun captureImage(uri: Uri, timeStamp: Long, sensitive: Boolean) {
        val generation = captureGeneration
        imageCapture.capture(
            uri,
            imageLimits,
            onCaptured = { staged ->
                mainHandler.post {
                    if (destroyed || generation != captureGeneration || !isEnabled ||
                        !SettingsManager.getClipboardImageHistoryEnabled(context) || !isHistoryAccessible()) {
                        staged.file.delete()
                    } else {
                        history.addImage(staged, timeStamp, sensitive, retention())
                    }
                }
            },
            onFailure = { failure ->
                if (!destroyed) when (failure) {
                    ClipboardImageCapture.Failure.TOO_LARGE ->
                        showError(R.string.clipboard_image_too_large, imageLimits.maxImageMegabytes())
                    ClipboardImageCapture.Failure.UNSUPPORTED -> showError(R.string.clipboard_image_unsupported_format)
                    ClipboardImageCapture.Failure.UNREADABLE -> showError(R.string.clipboard_image_unreadable)
                }
            }
        )
    }

    /**
     * Called whenever a history view opens: retries a failed load and removes expired entries.
     */
    fun onHistoryOpened() {
        val retention = retention()
        history.start(retention)
        history.purgeExpired(retention)
    }

    fun uiState(): UiState {
        if (!isHistoryAccessible()) return UiState(Status.LOCKED, emptyList())
        val snapshot = history.snapshot
        return when (snapshot.loadState) {
            ClipboardHistory.LoadState.LOADING -> UiState(Status.LOADING, emptyList())
            ClipboardHistory.LoadState.ERROR -> UiState(Status.ERROR, emptyList())
            ClipboardHistory.LoadState.READY -> {
                // Expiry is enforced here too, so a pending cleanup never exposes an expired clip.
                val retention = retention()
                val now = clock()
                UiState(Status.READY, snapshot.entries.filterNot { retention.isExpired(it, now) })
            }
        }
    }

    fun getHistoryEntries(): List<ClipboardHistoryEntry> = uiState().entries

    fun getHistorySize(): Int = getHistoryEntries().size

    fun getHistoryEntryContent(id: Long): ClipboardHistoryEntry? = getHistoryEntries().firstOrNull { it.id == id }

    fun toggleClipPinned(id: Long) {
        val entry = getHistoryEntryContent(id) ?: return
        if (entry.isSensitive) return
        history.togglePinned(id, retention())
    }

    /** Deletes the entry with [id]; pinned entries only when explicitly requested with [force]. */
    fun removeEntry(id: Long, force: Boolean = false) {
        val entry = getHistoryEntryContent(id) ?: return
        if (entry.isPinned && !force) return
        history.delete(id, force, retention())
    }

    /**
     * Clears unpinned history and the system clipboard. Both succeed or fail independently,
     * and each failure is reported on its own.
     */
    fun clearHistory() {
        if (uiState().status != Status.READY) return
        captureGeneration++
        history.clearNonPinned(retention())
        try {
            // Clear system clipboard (API 28+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                clipboardManager.clearPrimaryClip()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear system clipboard (${e.javaClass.simpleName})")
            showError(R.string.clipboard_system_clear_failed)
        }
    }

    /**
     * Pastes into the editor the IME is connected to now; [fallbackConnection] is used only
     * when no [pasteTargetProvider] is set.
     */
    fun pasteEntryIntoCurrentEditor(
        id: Long,
        formatting: Formatting = Formatting.DEFAULT,
        fallbackConnection: InputConnection? = null
    ): PasteResult {
        val provider = pasteTargetProvider
        val target = if (provider != null) provider.currentTarget()
            else fallbackConnection?.let { PasteTarget(it, null) }
        return paste(id, target, formatting)
    }

    /** Pastes into the given editor; without [editorInfo] text is pasted plain and images are refused. */
    fun pasteEntry(
        id: Long,
        inputConnection: InputConnection?,
        editorInfo: EditorInfo? = null,
        formatting: Formatting = Formatting.DEFAULT
    ): PasteResult = paste(id, inputConnection?.let { PasteTarget(it, editorInfo) }, formatting)

    private fun paste(id: Long, target: PasteTarget?, formatting: Formatting): PasteResult {
        // Read at the moment of the paste, so a locked, deleted or expired entry is never delivered.
        val entry = getHistoryEntryContent(id) ?: return PasteResult.UNAVAILABLE
        if (target == null) return PasteResult.NO_EDITOR
        val image = entry.image
        return if (image != null) pasteImage(image, target) else pasteText(entry, target, formatting)
    }

    private fun pasteText(entry: ClipboardHistoryEntry, target: PasteTarget, formatting: Formatting): PasteResult {
        val html = entry.html
        val plainText = when (formatting) {
            Formatting.DEFAULT -> pastesPlainText()
            Formatting.PLAIN -> true
            Formatting.STYLED -> false
        }
        val styled = if (!plainText && html != null && ClipboardRichText.acceptsFormatting(target.editorInfo)) {
            ClipboardRichText.styledText(html)
        } else {
            null
        }
        val committed = try {
            target.inputConnection.commitText(styled ?: entry.text, 1)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Clipboard paste failed (${e.javaClass.simpleName})")
            false
        }
        if (committed) return PasteResult.PASTED
        showError(R.string.clipboard_paste_failed)
        return PasteResult.FAILED
    }

    private fun pasteImage(image: ClipboardImage, target: PasteTarget): PasteResult {
        val editorInfo = target.editorInfo
        if (editorInfo == null || !acceptsImage(editorInfo, image)) {
            showError(R.string.clipboard_image_unsupported_field)
            return PasteResult.UNSUPPORTED
        }
        val file = history.imageFile(image.asset)
        val committed = try {
            if (!file.isFile) {
                false
            } else {
                val content = InputContentInfoCompat(
                    ClipboardImageProvider.uriFor(context, file),
                    ClipDescription(context.getString(R.string.clipboard_image_label), arrayOf(image.mimeType)),
                    null
                )
                // Committing while text is composed can make the editor lose focus.
                target.inputConnection.finishComposingText()
                InputConnectionCompat.commitContent(
                    target.inputConnection,
                    editorInfo,
                    content,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Clipboard image paste failed (${e.javaClass.simpleName})")
            false
        }
        if (committed) return PasteResult.PASTED
        showError(R.string.clipboard_image_paste_failed)
        return PasteResult.FAILED
    }

    /** Whether text is pasted without its formatting; one setting for every clipboard view. */
    fun pastesPlainText(): Boolean = SettingsManager.getClipboardPastePlainText(context)

    fun setPastesPlainText(plainText: Boolean) {
        if (plainText == pastesPlainText()) return
        SettingsManager.setClipboardPastePlainText(context, plainText)
        historyChangeListeners.toList().forEach { it() }
    }

    /** Whether [entry] can be pasted into the connected editor; text always can. */
    fun canPasteIntoCurrentEditor(entry: ClipboardHistoryEntry): Boolean {
        val image = entry.image ?: return true
        val editorInfo = pasteTargetProvider?.currentTarget()?.editorInfo ?: return false
        return acceptsImage(editorInfo, image)
    }

    private fun acceptsImage(editorInfo: EditorInfo, image: ClipboardImage): Boolean =
        ClipboardImageFormat.accepts(EditorInfoCompat.getContentMimeTypes(editorInfo), image.mimeType)

    /** Called by the IME when it connects to another editor or loses its editor. */
    fun onEditorChanged() {
        editorGeneration++
        historyChangeListeners.toList().forEach { it() }
    }

    /**
     * Loads a small preview of a stored image; [onLoaded] runs on the main thread, possibly
     * at once from the cache. Sensitive images are never decoded.
     */
    fun loadThumbnail(entry: ClipboardHistoryEntry, maxSidePx: Int, onLoaded: (Bitmap?) -> Unit) {
        val image = entry.image ?: return onLoaded(null)
        if (entry.isSensitive || !isHistoryAccessible()) return onLoaded(null)
        val loader = thumbnails ?: return onLoaded(null)
        loader.load(image.asset, history.imageFile(image.asset), maxSidePx) { bitmap ->
            val current = getHistoryEntryContent(entry.id)
            onLoaded(if (!destroyed && current?.isSensitive == false && current.image?.asset == image.asset) bitmap else null)
        }
    }

    fun addHistoryChangeListener(listener: () -> Unit) { historyChangeListeners += listener }

    fun removeHistoryChangeListener(listener: () -> Unit) { historyChangeListeners -= listener }

    fun isHistoryAccessible(): Boolean = !screenOffReceived && accessPolicy.isHistoryAccessible()

    fun addAccessStateListener(listener: (Boolean) -> Unit) {
        accessStateListeners += listener
    }

    fun removeAccessStateListener(listener: (Boolean) -> Unit) {
        accessStateListeners -= listener
    }

    internal fun refreshAccessState() {
        val accessible = isHistoryAccessible()
        if (lastHistoryAccessible == accessible) return
        lastHistoryAccessible = accessible
        accessStateListeners.toList().forEach { listener -> listener(accessible) }
    }

    /**
     * Paste the given text into the input connection.
     */
    fun pasteText(text: String, inputConnection: InputConnection?) {
        if (!isHistoryAccessible()) return
        inputConnection?.commitText(text, 1)
    }

    /**
     * Shows the clipboard history popup above the keyboard.
     * Returns the popup view that was created.
     */
    fun showClipboardHistoryPopup(
        inputConnection: InputConnection?,
        onDismiss: () -> Unit
    ): ClipboardHistoryPopupView? {
        if (!isEnabled) return null

        return ClipboardHistoryPopupView(context, this).apply {
            setOnItemClickListener { entry ->
                // An entry the editor refuses keeps the popup open, so the message stays in context.
                if (pasteEntryIntoCurrentEditor(entry.id, fallbackConnection = inputConnection) == PasteResult.PASTED) {
                    dismiss()
                    onDismiss()
                }
            }
            setOnPinClickListener { entry ->
                toggleClipPinned(entry.id)
            }
            setOnDeleteClickListener { entry ->
                removeEntry(entry.id, force = true)
            }
            setOnClearAllClickListener {
                clearHistory()
            }
            show()
        }
    }

    /** Purges with the current retention and republishes, which reschedules the expiry timer. */
    private fun reapplyRetention() {
        history.purgeExpired(retention(), republish = true)
    }

    /** Replaces the visible error, so failures do not queue up. */
    private fun showError(@StringRes message: Int, vararg formatArgs: Any) {
        errorToast?.cancel()
        errorToast = Toast.makeText(context, context.getString(message, *formatArgs), Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun scheduleExpiryCleanup(nextExpiry: Long) {
        mainHandler.removeCallbacks(expiryCleanup)
        if (nextExpiry == Long.MAX_VALUE) return
        mainHandler.postDelayed(expiryCleanup, (nextExpiry - clock()).coerceAtLeast(0L))
    }

    private fun retention() = ClipboardRetention.fromSettings(context)

    private fun getClipboardHistoryEnabled(): Boolean {
        return SettingsManager.getClipboardHistoryEnabled(context)
    }

    private fun registerDeviceStateReceiver() {
        if (deviceStateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            deviceStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        deviceStateReceiverRegistered = true
    }

    companion object {
        private const val TAG = "ClipboardHistoryManager"
    }
}
