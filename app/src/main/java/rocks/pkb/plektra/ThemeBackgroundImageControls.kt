package rocks.pkb.plektra

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ThemeBackgroundImageControls(theme: KeyboardThemePreset, onThemeChanged: (KeyboardThemePreset) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTheme by rememberUpdatedState(theme)
    var requestedPhoto by remember { mutableStateOf<ThemePhotoImportRequest?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val requested = requestedPhoto
        requestedPhoto = null
        if (uri != null && requested != null) scope.launch {
            loading = true
            failed = false
            try {
                val image = ThemeTextureStore.import(context, uri)
                failed = !requested.complete(currentTheme, image)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } catch (_: OutOfMemoryError) {
                failed = true
            } finally { loading = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(enabled = !loading, modifier = Modifier.fillMaxWidth(), onClick = {
            requestedPhoto = ThemePhotoImportRequest(currentTheme, onThemeChanged)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            Text(stringResource(when {
                loading -> R.string.theme_background_loading
                theme.textures.containsKey("background") -> R.string.theme_background_change
                else -> R.string.theme_background_photo
            }))
        }
        if (theme.textures.containsKey("background")) {
            Text(stringResource(R.string.theme_background_help), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !loading, onClick = {
                onThemeChanged(currentTheme.copy(textures = currentTheme.textures - "background"))
            }) { Text(stringResource(R.string.theme_background_remove)) }
        }
        Text(stringResource(R.string.theme_background_zip), style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(R.string.theme_background_failed), color = MaterialTheme.colorScheme.error)
    }
}
