package rocks.pkb.plektra

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * The editor that hosts these controls is keyed by target, draft and tab, so a pending import is
 * cancelled with its owner and the latest theme/callback always belong to the same target. That
 * identity and the picked image are saved state: after activity recreation the same editor
 * re-registers the picker (receiving a pending result) and resumes an interrupted import.
 */
@Composable
internal fun ThemeBackgroundImageControls(theme: KeyboardThemePreset, onThemeChanged: (KeyboardThemePreset) -> Unit) {
    val context = LocalContext.current
    val currentTheme by rememberUpdatedState(theme)
    val currentOnThemeChanged by rememberUpdatedState(onThemeChanged)
    var pendingImage by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val loading = pendingImage != null
    val hasPhoto = theme.hasBackgroundPhoto()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            failed = false
            pendingImage = uri.toString()
        }
    }
    LaunchedEffect(pendingImage) {
        val uri = pendingImage ?: return@LaunchedEffect
        try {
            val image = ThemeTextureStore.import(context, Uri.parse(uri))
            currentOnThemeChanged(currentTheme.withBackgroundPhoto(image))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } catch (_: OutOfMemoryError) {
            failed = true
        }
        pendingImage = null
    }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.theme_background_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text(
                stringResource(if (hasPhoto) R.string.theme_background_active_help else R.string.theme_background_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !loading, onClick = {
                    failed = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(stringResource(when {
                        loading -> R.string.theme_background_loading
                        hasPhoto -> R.string.theme_background_change
                        else -> R.string.theme_background_photo
                    }))
                }
                if (hasPhoto) {
                    TextButton(enabled = !loading, onClick = {
                        currentOnThemeChanged(currentTheme.withoutBackgroundPhoto())
                    }) { Text(stringResource(R.string.theme_background_remove)) }
                }
            }
            if (failed) Text(stringResource(R.string.theme_background_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
