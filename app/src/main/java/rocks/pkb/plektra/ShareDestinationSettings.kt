package rocks.pkb.plektra

import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.share.ShareSettings

/** Destinations of Android's share menu target: the file folder and the optional gallery album. */
@Composable
internal fun ShareDestinationSettings() {
    val context = LocalContext.current
    // Folder metadata comes from another app's DocumentsProvider and may be slow: never on main.
    var folder by remember { mutableStateOf<ShareSettings.SaveFolder?>(null) }
    var albumEnabled by remember { mutableStateOf(ShareSettings.galleryAlbumEnabled(context)) }
    var showFolderDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val prefs = remember { SettingsManager.getPreferences(context) }
    val localPrefs = remember { ShareSettings.localPreferences(context) }

    fun reloadFolder() {
        scope.launch { folder = withContext(Dispatchers.IO) { ShareSettings.saveFolder(context) } }
    }

    DisposableEffect(prefs, localPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                ShareSettings.KEY_SAVE_FOLDER_URI -> reloadFolder()
                ShareSettings.KEY_GALLERY_ALBUM -> albumEnabled = ShareSettings.galleryAlbumEnabled(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        localPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            localPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    // A folder grant can be revoked or its folder deleted while Plektra is in the background.
    LifecycleResumeEffect(Unit) {
        reloadFolder()
        onPauseOrDispose { }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val kept = withContext(Dispatchers.IO) {
                    runCatching { ShareSettings.selectSaveFolder(context, uri) }.isSuccess
                }
                if (!kept) {
                    Toast.makeText(context, R.string.share_save_folder_choose_failed, Toast.LENGTH_LONG).show()
                }
                reloadFolder()
            }
        }
    }
    val currentFolder = folder
    val folderSummary = when (currentFolder) {
        null -> null
        ShareSettings.SaveFolder.Downloads -> stringResource(R.string.share_folder_downloads)
        is ShareSettings.SaveFolder.Tree -> when {
            !currentFolder.accessible -> stringResource(R.string.share_folder_access_lost)
            currentFolder.label == null -> stringResource(R.string.share_error_folder_unavailable)
            else -> currentFolder.label
        }
    }
    val folderInaccessible = (currentFolder as? ShareSettings.SaveFolder.Tree)?.usable == false

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .settingRow(SettingLinkIds.ADVANCED_SHARE_SAVE_FOLDER) {
                when (currentFolder) {
                    null -> Unit
                    ShareSettings.SaveFolder.Downloads ->
                        folderPicker.launch(ShareSettings.pickerStartUri(currentFolder))
                    is ShareSettings.SaveFolder.Tree -> showFolderDialog = true
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.share_save_folder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.share_save_folder_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (folderSummary != null) {
                    Text(
                        text = folderSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (folderInaccessible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .settingRow(SettingLinkIds.ADVANCED_SHARE_GALLERY_ALBUM) {
                albumEnabled = !albumEnabled
                ShareSettings.setGalleryAlbumEnabled(context, albumEnabled)
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.share_gallery_album_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.share_gallery_album_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = albumEnabled,
                onCheckedChange = { enabled ->
                    albumEnabled = enabled
                    ShareSettings.setGalleryAlbumEnabled(context, enabled)
                }
            )
        }
    }

    if (showFolderDialog && currentFolder != null && folderSummary != null) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(R.string.share_save_folder_title)) },
            text = {
                Text(
                    if (folderInaccessible) folderSummary
                    else stringResource(R.string.share_save_folder_dialog_message, folderSummary)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFolderDialog = false
                    folderPicker.launch(ShareSettings.pickerStartUri(currentFolder))
                }) { Text(stringResource(R.string.share_save_folder_choose)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFolderDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { ShareSettings.useDownloads(context) }
                        reloadFolder()
                    }
                }) { Text(stringResource(R.string.share_save_folder_use_downloads)) }
            }
        )
    }
}
