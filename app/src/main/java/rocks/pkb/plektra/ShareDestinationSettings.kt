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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.share.ShareSettings

/** Destinations of Android's share menu target: the file folder and the optional gallery album. */
@Composable
internal fun ShareDestinationSettings() {
    val context = LocalContext.current
    // The chosen URI comes from local preferences and drives every action. Its label and grant
    // state come from another app's DocumentsProvider, which may be slow: loaded off main, and
    // only the newest request may update the screen.
    var folderUri by remember { mutableStateOf(ShareSettings.storedFolderUri(context)) }
    var folderInfo by remember { mutableStateOf<ShareSettings.SaveFolder.Tree?>(null) }
    val folderRequests = remember { AtomicInteger() }
    var albumEnabled by remember { mutableStateOf(ShareSettings.galleryAlbumEnabled(context)) }
    var showFolderDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Choices are applied one after another in the order the user made them.
    val selections = remember { Dispatchers.IO.limitedParallelism(1) }
    val prefs = remember { SettingsManager.getPreferences(context) }
    val localPrefs = remember { ShareSettings.localPreferences(context) }

    fun reloadFolder() {
        val request = folderRequests.incrementAndGet()
        folderUri = ShareSettings.storedFolderUri(context)
        scope.launch {
            val info = withContext(Dispatchers.IO) { ShareSettings.saveFolder(context) }
            if (request == folderRequests.get()) folderInfo = info as? ShareSettings.SaveFolder.Tree
        }
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
                val kept = withContext(selections) {
                    runCatching { ShareSettings.selectSaveFolder(context, uri) }.isSuccess
                }
                if (!kept) {
                    Toast.makeText(context, R.string.share_save_folder_choose_failed, Toast.LENGTH_LONG).show()
                }
                reloadFolder()
            }
        }
    }
    val currentUri = folderUri
    val info = folderInfo?.takeIf { it.uri == currentUri }
    val folderSummary = when {
        currentUri == null -> stringResource(R.string.share_folder_downloads)
        info == null -> stringResource(R.string.share_folder_unnamed)
        !info.accessible -> stringResource(R.string.share_folder_access_lost)
        info.label == null -> stringResource(R.string.share_error_folder_unavailable)
        else -> info.label
    }
    val folderInaccessible = info?.usable == false
    val pickerStart = if (currentUri == null) ShareSettings.SaveFolder.Downloads
    else info ?: ShareSettings.SaveFolder.Tree(currentUri, accessible = true, label = null)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .settingRow(SettingLinkIds.ADVANCED_SHARE_SAVE_FOLDER) {
                if (currentUri == null) {
                    folderPicker.launch(ShareSettings.pickerStartUri(pickerStart))
                } else {
                    showFolderDialog = true
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
                Text(
                    text = folderSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (folderInaccessible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
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

    if (showFolderDialog && currentUri != null) {
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
                    folderPicker.launch(ShareSettings.pickerStartUri(pickerStart))
                }) { Text(stringResource(R.string.share_save_folder_choose)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFolderDialog = false
                    scope.launch {
                        withContext(selections) { ShareSettings.useDownloads(context) }
                        reloadFolder()
                    }
                }) { Text(stringResource(R.string.share_save_folder_use_downloads)) }
            }
        )
    }
}
