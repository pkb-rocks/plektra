package rocks.pkb.plektra.clipboard

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat
import rocks.pkb.plektra.SettingsManager

/**
 * Manages clipboard history tracking and provides popup display.
 * Listens to system clipboard changes and stores them in a database.
 */
class ClipboardHistoryManager internal constructor(
    private val context: Context,
    private val accessPolicy: ClipboardHistoryAccessPolicy
) : ClipboardManager.OnPrimaryClipChangedListener {

    constructor(context: Context) : this(context, SystemClipboardHistoryAccessPolicy(context))

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardDao: ClipboardDao? = null

    // Retry creation on the next use; the DAO loads stored history on its worker.
    private val dao: ClipboardDao?
        get() = clipboardDao ?: ClipboardDao.getInstance(context).also { clipboardDao = it }
    private var isEnabled: Boolean = true // TODO: Add setting
    private var deviceStateReceiverRegistered = false
    private var screenOffReceived = false
    private var lastHistoryAccessible: Boolean? = null
    private val historyChangeListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var externalHistoryListener: ClipboardDao.Listener? = null
    private val daoListener = object : ClipboardDao.Listener {
        override fun onClipInserted(position: Int) {
            externalHistoryListener?.onClipInserted(position)
            notifyHistoryChanged()
        }
        override fun onClipsRemoved(position: Int, count: Int) {
            externalHistoryListener?.onClipsRemoved(position, count)
            notifyHistoryChanged()
        }
        override fun onClipMoved(oldPosition: Int, newPosition: Int) {
            externalHistoryListener?.onClipMoved(oldPosition, newPosition)
            notifyHistoryChanged()
        }
        override fun onHistoryLoaded() {
            externalHistoryListener?.onHistoryLoaded()
            notifyHistoryChanged()
        }
    }
    private fun notifyHistoryChanged() = historyChangeListeners.forEach { it() }

    private val accessStateListeners = linkedSetOf<(Boolean) -> Unit>()
    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOffReceived = intent?.action == Intent.ACTION_SCREEN_OFF
            refreshAccessState()
        }
    }

    fun onCreate() {
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(context)
        dao?.listener = daoListener
        registerDeviceStateReceiver()
        lastHistoryAccessible = isHistoryAccessible()

        // Check if history is enabled
        isEnabled = getClipboardHistoryEnabled()

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
        accessStateListeners.clear()
        historyChangeListeners.clear()
        externalHistoryListener = null
        if (clipboardDao?.listener === daoListener) clipboardDao?.listener = null
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
            val timeStamp = clipData.description.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
            val content = clipItem.coerceToText(context)
            if (TextUtils.isEmpty(content)) return
            if (!isHistoryAccessible()) return

            val retentionMinutes = getClipboardRetentionTime()
            dao?.addClip(timeStamp, false, content.toString(), retentionMinutes, sensitive,
                SettingsManager.getSensitiveClipboardRetentionTime(context))
        }
    }

    fun toggleClipPinned(id: Long) {
        if (getHistoryEntryContent(id) == null) return
        dao?.togglePinned(id)
    }

    fun clearHistory() {
        if (!isHistoryAccessible()) return
        dao?.clearNonPinned()
        try {
            // Clear system clipboard (API 28+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                clipboardManager.clearPrimaryClip()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear system clipboard", e)
        }
    }

    fun canRemove(index: Int) = isHistoryAccessible() && dao?.isPinned(index) == false

    fun removeEntry(index: Int, force: Boolean = false) {
        val entry = getHistoryEntry(index) ?: return
        removeEntryById(entry.id, force)
    }

    fun removeEntryById(id: Long, force: Boolean = false) {
        val entry = getHistoryEntryContent(id) ?: return

        // For UX: allow deleting pinned entries when explicitly requested (force=true)
        if (entry.isPinned && !force) return

        dao?.deleteClip(entry.id)
    }

    fun sortHistoryEntries() {
        if (!isHistoryAccessible()) return
        dao?.sort()
    }

    fun prepareClipboardHistory() {
        // Clear old clips before showing history
        val retentionMinutes = getClipboardRetentionTime()
        dao?.clearOldClips(true, retentionMinutes, SettingsManager.getSensitiveClipboardRetentionTime(context))
    }

    fun getHistorySize(): Int {
        prepareClipboardHistory()
        return if (isHistoryAccessible()) dao?.count() ?: 0 else 0
    }

    fun getHistoryEntry(position: Int): ClipboardHistoryEntry? {
        prepareClipboardHistory()
        return if (isHistoryAccessible()) dao?.getAt(position) else null
    }

    fun getHistoryEntryContent(id: Long): ClipboardHistoryEntry? {
        prepareClipboardHistory()
        return if (isHistoryAccessible()) dao?.get(id) else null
    }

    fun pasteEntry(id: Long, inputConnection: android.view.inputmethod.InputConnection?) {
        val entry = getHistoryEntryContent(id) ?: return
        pasteText(entry.text, inputConnection)
    }

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        externalHistoryListener = listener
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

        prepareClipboardHistory()

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
                removeEntryById(entry.id, force = true)
            }
            setOnClearAllClickListener {
                clearHistory()
            }
            show()
        }
    }

    private fun getClipboardHistoryEnabled(): Boolean {
        return SettingsManager.getClipboardHistoryEnabled(context)
    }

    private fun getClipboardRetentionTime(): Long {
        return SettingsManager.getClipboardRetentionTime(context)
    }

    private fun registerDeviceStateReceiver() {
        if (deviceStateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
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
