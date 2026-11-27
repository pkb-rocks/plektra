package rocks.pkb.plektra.share

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.R

sealed interface ShareAction {
    data class CopyToClipboard(val itemId: Int) : ShareAction
    data class SaveFiles(val forceDownloads: Boolean = false) : ShareAction
    data object SaveToGallery : ShareAction
}

data class ShareProgress(val action: ShareAction, val done: Int, val total: Int)

data class ShareUiState(
    val loading: Boolean = true,
    val payload: SharePayload = SharePayload(emptyList(), 0),
    val folder: ShareSettings.SaveFolder = ShareSettings.SaveFolder.Downloads,
    val galleryPath: String = "Pictures",
    val progress: ShareProgress? = null,
    val results: Map<Int, ShareItemResult> = emptyMap(),
    /** Set when the last run stopped early; already handled items keep their result. */
    val cancelled: Boolean = false,
    /** A localized summary after which the screen closes because every item succeeded. */
    val finishedMessage: String? = null
) {
    val busy: Boolean get() = progress != null
    /** The chosen folder cannot take files right now, so Downloads is offered as a fallback. */
    val folderUnusable: Boolean get() =
        results.values.any {
            it is ShareItemResult.Failed &&
                (it.reason == ShareFailure.FolderAccessRevoked || it.reason == ShareFailure.FolderUnavailable)
        } || (folder as? ShareSettings.SaveFolder.Tree)?.usable == false
}

class ShareReceiverViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(ShareUiState())
        private set

    private var loaded = false
    private var job: Job? = null
    private val context get() = getApplication<Application>()

    fun load(intent: Intent) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) {
                // Leftovers of a process that ended mid-save, and expired clipboard copies.
                runCatching { ShareCleanupWorker.cleanUp(context, clipKnown = false) }
                ShareIntentParser.parse(context, intent)
            }
            state = state.copy(loading = false, payload = payload)
            refreshDestinations()
        }
    }

    /** Destinations may change in settings while this screen is open. */
    fun refreshDestinations() {
        viewModelScope.launch {
            val folder = withContext(Dispatchers.IO) { ShareSettings.saveFolder(context) }
            state = state.copy(
                folder = folder,
                galleryPath = ShareSettings.galleryRelativePath(context).trimEnd('/')
            )
        }
    }

    fun run(action: ShareAction) {
        if (state.busy || state.loading) return
        val targets = when (action) {
            is ShareAction.CopyToClipboard -> state.payload.items.filter { it.id == action.itemId }
            is ShareAction.SaveFiles -> if (action.forceDownloads) {
                // Retrying in Downloads must not duplicate items that already reached the folder.
                state.payload.items.filter { state.results[it.id] !is ShareItemResult.Saved }
            } else {
                state.payload.items
            }
            ShareAction.SaveToGallery -> state.payload.items.filter { it is SharedItem.Stream && it.isImage }
        }
        if (targets.isEmpty()) return
        state = state.copy(
            progress = ShareProgress(action, 0, targets.size),
            results = state.results - targets.map { it.id }.toSet(),
            cancelled = false
        )
        job = viewModelScope.launch {
            val folder = withContext(Dispatchers.IO) { ShareSettings.saveFolder(context) }
            state = state.copy(folder = folder)
            val outcomes = mutableListOf<ShareItemResult>()
            try {
                for ((index, item) in targets.withIndex()) {
                    val result = withContext(Dispatchers.IO) { perform(action, item, folder) }
                    outcomes += result
                    state = state.copy(
                        results = state.results + (item.id to result),
                        progress = ShareProgress(action, index + 1, targets.size)
                    )
                }
                val allSucceeded = outcomes.none { it is ShareItemResult.Failed }
                state = state.copy(
                    progress = null,
                    finishedMessage = if (allSucceeded) summary(action, outcomes) else null
                )
            } finally {
                if (state.busy) state = state.copy(progress = null, cancelled = true)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun perform(
        action: ShareAction,
        item: SharedItem,
        folder: ShareSettings.SaveFolder
    ): ShareItemResult = when (action) {
        is ShareAction.CopyToClipboard -> ShareOperations.copyToClipboard(context, item)
        ShareAction.SaveToGallery -> ShareOperations.saveToGallery(context, item)
        is ShareAction.SaveFiles -> when {
            action.forceDownloads || folder is ShareSettings.SaveFolder.Downloads ->
                ShareOperations.saveToDownloads(context, item)
            folder is ShareSettings.SaveFolder.Tree -> ShareOperations.saveToFolder(
                context,
                item,
                folder.uri,
                folder.label ?: context.getString(R.string.share_folder_unnamed)
            )
            else -> ShareOperations.saveToDownloads(context, item)
        }
    }

    private fun summary(action: ShareAction, outcomes: List<ShareItemResult>): String {
        val resources = context.resources
        val saved = outcomes.filterIsInstance<ShareItemResult.Saved>()
        return when (action) {
            is ShareAction.CopyToClipboard -> context.getString(R.string.share_done_clipboard)
            is ShareAction.SaveFiles -> resources.getQuantityString(
                R.plurals.share_done_files,
                saved.size,
                saved.size,
                saved.firstOrNull()?.location.orEmpty()
            )
            ShareAction.SaveToGallery -> resources.getQuantityString(
                R.plurals.share_done_gallery,
                saved.size,
                saved.size,
                saved.firstOrNull()?.location.orEmpty()
            )
        }
    }
}
