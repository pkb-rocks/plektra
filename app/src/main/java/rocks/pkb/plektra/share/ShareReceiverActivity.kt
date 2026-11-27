package rocks.pkb.plektra.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import rocks.pkb.plektra.LocalizedComponentActivity
import rocks.pkb.plektra.PlektraSettingsHeader
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingLinkIds
import rocks.pkb.plektra.SettingsActivity
import rocks.pkb.plektra.ui.theme.PlektraTheme

/**
 * Receives Android shares and offers only the actions that apply to the shared items: copy one
 * item to the clipboard, save every item as a file, or add pictures to the gallery.
 */
class ShareReceiverActivity : LocalizedComponentActivity() {
    private val viewModel: ShareReceiverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.load(intent)
        setContent {
            PlektraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    ShareReceiverScreen(
                        state = viewModel.state,
                        onAction = viewModel::run,
                        onCancel = viewModel::cancel,
                        onResume = viewModel::refreshDestinations,
                        onClose = ::close,
                        onFinished = { message ->
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun close() {
        viewModel.cancel()
        finish()
    }
}

internal fun openShareSetting(context: Context, settingId: String) {
    context.startActivity(Intent(context, SettingsActivity::class.java).apply {
        data = Uri.parse("plektra://setting/$settingId")
    })
}

@Composable
private fun ShareReceiverScreen(
    state: ShareUiState,
    onAction: (ShareAction) -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onClose: () -> Unit,
    onFinished: (String) -> Unit
) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        onResume()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.finishedMessage) {
        state.finishedMessage?.let(onFinished)
    }
    BackHandler(onBack = onClose)
    val items = state.payload.items
    Scaffold(
        topBar = {
            PlektraSettingsHeader(title = stringResource(R.string.share_title), onBack = onClose)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                return@LazyColumn
            }
            item {
                Text(
                    text = if (items.isEmpty()) {
                        stringResource(R.string.share_nothing_received)
                    } else {
                        pluralStringResource(R.plurals.share_item_count, items.size, items.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            if (items.isNotEmpty()) {
                // Actions come before the list so they stay reachable for long selections on short screens.
                item {
                    ShareActionPanel(
                        state = state,
                        onAction = onAction,
                        onCancel = onCancel,
                        onChangeDestinations = {
                            openShareSetting(context, SettingLinkIds.ADVANCED_SHARE_SAVE_FOLDER)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (state.payload.rejectedCount > 0) {
                item {
                    Text(
                        text = pluralStringResource(
                            R.plurals.share_items_rejected,
                            state.payload.rejectedCount,
                            state.payload.rejectedCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                SharedItemRow(
                    item = item,
                    result = state.results[item.id],
                    showCopyButton = items.size > 1,
                    enabled = !state.busy,
                    onCopy = { onAction(ShareAction.CopyToClipboard(item.id)) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (items.isEmpty()) {
                item {
                    TextButton(onClick = onClose, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.share_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedItemRow(
    item: SharedItem,
    result: ShareItemResult?,
    showCopyButton: Boolean,
    enabled: Boolean,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    val icon: ImageVector
    val title: String
    val detail: String
    when (item) {
        is SharedItem.Text -> {
            icon = Icons.Filled.TextFields
            title = item.text.trim()
            detail = pluralStringResource(R.plurals.share_text_detail, item.text.length, item.text.length)
        }
        is SharedItem.Stream -> {
            icon = if (item.isImage) Icons.Filled.Image else Icons.Filled.InsertDriveFile
            title = item.displayName ?: stringResource(R.string.share_file_fallback_name)
            detail = listOfNotNull(item.mimeType, item.size?.let { Formatter.formatShortFileSize(context, it) })
                .joinToString(" · ")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (item is SharedItem.Text) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            result?.let { ResultLine(it) }
        }
        if (showCopyButton) {
            IconButton(onClick = onCopy, enabled = enabled) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.share_action_clipboard))
            }
        }
    }
}

@Composable
private fun ResultLine(result: ShareItemResult) {
    val (text, isError) = when (result) {
        is ShareItemResult.Saved -> stringResource(R.string.share_result_saved, result.name, result.location) to false
        ShareItemResult.Copied -> stringResource(R.string.share_result_copied) to false
        is ShareItemResult.Failed -> failureMessage(result.reason) to true
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun failureMessage(reason: ShareFailure): String = stringResource(
    when (reason) {
        ShareFailure.SourceUnreadable -> R.string.share_error_source_unreadable
        ShareFailure.FolderAccessRevoked -> R.string.share_error_folder_access_revoked
        ShareFailure.FolderUnavailable -> R.string.share_error_folder_unavailable
        ShareFailure.NotAnImage -> R.string.share_error_not_an_image
        ShareFailure.TooLargeForClipboard -> R.string.share_error_too_large_for_clipboard
        ShareFailure.ClipboardRejected -> R.string.share_error_clipboard_rejected
        ShareFailure.WriteFailed -> R.string.share_error_write_failed
    }
)

@Composable
private fun ShareActionPanel(
    state: ShareUiState,
    onAction: (ShareAction) -> Unit,
    onCancel: () -> Unit,
    onChangeDestinations: () -> Unit
) {
    val items = state.payload.items
    val folderLabel = when (val folder = state.folder) {
        ShareSettings.SaveFolder.Downloads -> stringResource(R.string.share_folder_downloads)
        is ShareSettings.SaveFolder.Tree -> folder.label ?: stringResource(R.string.share_folder_unnamed)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val progress = state.progress
        if (progress != null) {
            Text(
                text = stringResource(R.string.share_progress, progress.done, progress.total),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share_cancel))
            }
            return@Column
        }
        val failed = state.results.values.count { it is ShareItemResult.Failed }
        if (failed > 0) {
            Text(
                text = pluralStringResource(R.plurals.share_failed_count, failed, failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (state.cancelled) {
            Text(
                text = stringResource(R.string.share_cancelled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (items.size == 1) {
            ActionButton(
                icon = Icons.Filled.ContentCopy,
                label = stringResource(R.string.share_action_clipboard),
                supporting = null,
                primary = true,
                onClick = { onAction(ShareAction.CopyToClipboard(items.single().id)) }
            )
        }
        val tree = state.folder as? ShareSettings.SaveFolder.Tree
        ActionButton(
            icon = Icons.Filled.Download,
            label = stringResource(if (items.size == 1) R.string.share_action_save_file else R.string.share_action_save_files),
            supporting = when {
                tree?.accessible == false -> stringResource(R.string.share_folder_access_lost)
                tree?.usable == false -> stringResource(R.string.share_error_folder_unavailable)
                else -> stringResource(R.string.share_destination, folderLabel)
            },
            supportingIsError = tree?.usable == false,
            primary = items.size > 1,
            onClick = { onAction(ShareAction.SaveFiles()) }
        )
        if (state.folderUnusable) {
            OutlinedButton(
                onClick = { onAction(ShareAction.SaveFiles(forceDownloads = true)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.share_action_save_downloads_instead))
            }
        }
        val images = state.payload.imageCount
        if (images > 0) {
            ActionButton(
                icon = Icons.Filled.PhotoLibrary,
                label = stringResource(if (images == 1) R.string.share_action_gallery_one else R.string.share_action_gallery_many),
                supporting = if (images < items.size) {
                    stringResource(R.string.share_gallery_partial, images, items.size, state.galleryPath)
                } else {
                    stringResource(R.string.share_destination, state.galleryPath)
                },
                primary = false,
                onClick = { onAction(ShareAction.SaveToGallery) }
            )
        }
        TextButton(onClick = onChangeDestinations) {
            Text(stringResource(R.string.share_change_destinations))
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    supporting: String?,
    primary: Boolean,
    onClick: () -> Unit,
    supportingIsError: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val content: @Composable () -> Unit = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        if (primary) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
        } else {
            FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
        }
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (supportingIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp)
            )
        }
    }
}
