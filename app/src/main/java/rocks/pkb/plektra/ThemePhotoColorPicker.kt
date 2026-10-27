package rocks.pkb.plektra

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun ThemePhotoColorButton(onColorSelected: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            failed = false
            try {
                val photo = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        ThemePhotoColors.decode(ThemePhotoColors.readBounded(it))
                    } ?: error("Photo unavailable")
                }
                bitmap = photo
                chosen = null
                show = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    Column {
        TextButton(enabled = !loading, onClick = {
            if (bitmap != null) show = true
            else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text(stringResource(if (loading) R.string.theme_photo_loading else R.string.theme_photo_pick)) }
        if (failed) Text(stringResource(R.string.theme_photo_failed), color = MaterialTheme.colorScheme.error)
    }
    if (show) {
        val photo = bitmap ?: return
        val image = remember(photo) { photo.asImageBitmap() }
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        var marker by remember(photo, viewport) { mutableStateOf<Offset?>(null) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.theme_photo_title)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.theme_photo_hint))
                    Canvas(Modifier.fillMaxWidth().height(180.dp).onSizeChanged { viewport = it }
                        .pointerInput(photo, viewport) {
                            detectTapGestures { tap ->
                                ThemePhotoColors.imagePoint(IntSize(photo.width, photo.height), viewport, tap)?.let { point ->
                                    ThemePhotoColors.sample(photo, point)?.let { color -> chosen = color; marker = tap }
                                }
                            }
                        }) {
                        val scale = min(size.width / photo.width, size.height / photo.height)
                        val rendered = IntSize(max(1, (photo.width * scale).roundToInt()), max(1, (photo.height * scale).roundToInt()))
                        drawImage(image, dstOffset = IntOffset((size.width.toInt() - rendered.width) / 2,
                            (size.height.toInt() - rendered.height) / 2), dstSize = rendered)
                        marker?.let {
                            drawCircle(Color.Black, 9.dp.toPx(), it)
                            drawCircle(Color.White, 7.dp.toPx(), it)
                            drawCircle(Color(chosen ?: 0), 5.dp.toPx(), it)
                        }
                    }
                    chosen?.let { color ->
                        Surface(color = Color(color), modifier = Modifier.fillMaxWidth().height(24.dp), shape = MaterialTheme.shapes.small) {}
                        Text(String.format("#%06X", color and 0xFFFFFF))
                    }
                    TextButton(onClick = { show = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(stringResource(R.string.theme_photo_change))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = chosen != null, onClick = { chosen?.let(onColorSelected); show = false }) {
                    Text(stringResource(R.string.theme_photo_use))
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}
