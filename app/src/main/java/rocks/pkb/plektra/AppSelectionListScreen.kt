package rocks.pkb.plektra

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import rocks.pkb.plektra.inputmethod.WebApkHost

private data class SelectedApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val hostBrowser: String?
)

private fun resolveSelectedApp(context: Context, packageName: String): SelectedApp {
    val packageManager = context.packageManager
    val label = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
    val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
    return SelectedApp(packageName, label, icon, WebApkHost.hostBrowser(context, packageName))
}

@Composable
fun AppRawModeScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    AppSelectionListScreen(
        modifier = modifier,
        onBack = onBack,
        titleRes = R.string.app_raw_mode_title,
        descriptionRes = R.string.app_raw_mode_description,
        webApkNoteRes = R.string.app_raw_mode_webapk_note,
        readPackages = SettingsManager::getRawModePackages,
        setSelected = SettingsManager::setRawModeApp
    )
}

@Composable
fun KeyboardBarHiddenAppsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    AppSelectionListScreen(
        modifier = modifier,
        onBack = onBack,
        titleRes = R.string.keyboard_bar_hidden_apps_title,
        descriptionRes = R.string.keyboard_bar_hidden_apps_description,
        webApkNoteRes = R.string.keyboard_bar_hidden_apps_webapk_note,
        readPackages = SettingsManager::getKeyboardBarHiddenPackages,
        setSelected = SettingsManager::setKeyboardBarHiddenInApp
    )
}

/** A per-app package list: the apps picked here get one setting switched on. */
@Composable
private fun AppSelectionListScreen(
    modifier: Modifier,
    onBack: () -> Unit,
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    @StringRes webApkNoteRes: Int,
    readPackages: (Context) -> Set<String>,
    setSelected: (Context, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var packages by remember { mutableStateOf(readPackages(context)) }
    var showPicker by remember { mutableStateOf(false) }
    val apps = remember(packages) {
        packages.map { resolveSelectedApp(context, it) }.sortedBy { it.label.lowercase() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back_content_description)
                    )
                }
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f)
                )
                IconButton(onClick = { showPicker = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.app_enter_behaviour_add_app)
                    )
                }
            }
        }

        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        apps.forEach { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(modifier = Modifier.size(40.dp)) {
                    if (app.icon != null) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                }
                            },
                            update = { view ->
                                view.setImageDrawable(app.icon.constantState?.newDrawable() ?: app.icon)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    app.hostBrowser?.let { host ->
                        Text(
                            text = stringResource(
                                webApkNoteRes,
                                resolveSelectedApp(context, host).label
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = {
                    setSelected(context, app.packageName, false)
                    packages = readPackages(context)
                }) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.app_enter_behaviour_remove_app)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showPicker) {
        AppPickerDialog(
            onAppSelected = { app ->
                setSelected(context, app.packageName, true)
                packages = readPackages(context)
            },
            onDismiss = { showPicker = false }
        )
    }
}
