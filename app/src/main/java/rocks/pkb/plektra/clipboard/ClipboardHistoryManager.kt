package rocks.pkb.plektra.clipboard

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager

/**
 * Connects the Android clipboard and the history UI to [ClipboardHistory].
 * Reads are pure functions of the latest published snapshot, the device lock state and the
 * current time; changes are requests by stable entry id that the history validates again
 * when it executes them.
 */
class ClipboardHistoryManager internal constructor(
    private val context: Context,
    private val accessPolicy: ClipboardHistoryAccessPolicy,
    private val history: ClipboardHistory,
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

    private lateinit var clipboardManager: ClipboardManager
    private var isEnabled: Boolean = true // TODO: Add setting
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
    private var errorToast: Toast? = null

    // SharedPreferences holds listeners weakly, so this one stays a field.
    private val retentionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (SettingsManager.isClipboardRetentionKey(key)) reapplyRetention()
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
        if (clipData.itemCount == 0 || clipData.description?.hasMimeType("text/*") == false) {
            return
        }

        clipData.getItemAt(0)?.let { clipItem ->
            val timeStamp = clipData.description.timestamp.takeIf { it > 0 } ?: clock()
            val content = clipItem.coerceToText(context)
            if (TextUtils.isEmpty(content)) return
            if (!isHistoryAccessible()) return

            history.addClip(content.toString(), timeStamp, sensitive, retention())
        }
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

    fun pasteEntry(id: Long, inputConnection: android.view.inputmethod.InputConnection?) {
        val entry = getHistoryEntryContent(id) ?: return
        pasteText(entry.text, inputConnection)
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
    fun pasteText(text: String, inputConnection: android.view.inputmethod.InputConnection?) {
        if (!isHistoryAccessible()) return
        inputConnection?.commitText(text, 1)
    }

    /**
     * Shows the clipboard history popup above the keyboard.
     * Returns the popup view that was created.
     */
    fun showClipboardHistoryPopup(
        inputConnection: android.view.inputmethod.InputConnection?,
        onDismiss: () -> Unit
    ): ClipboardHistoryPopupView? {
        if (!isEnabled) return null

        return ClipboardHistoryPopupView(context, this).apply {
            setOnItemClickListener { entry ->
                pasteEntry(entry.id, inputConnection)
                dismiss()
                onDismiss()
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
    private fun showError(@StringRes message: Int) {
        errorToast?.cancel()
        errorToast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
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
