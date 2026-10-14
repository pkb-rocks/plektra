package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.toolbox.FnCtrlRemap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * everything the Titan 2 Elite toolbox adds to the Titan 2 Elite tools screen.
 *
 * [ToolboxSection] is the block of rows rendered under the device setup card. Four of them open a
 * page ([ToolboxPage]) as a child settings activity, the pattern the rest of Settings uses; the
 * Fn -> Ctrl card, the status-bar switch and the reset row work in place.
 *
 * None of this runs in the IME: every privileged call is on a settings screen, on an IO
 * dispatcher, through the typed command layer (inputmethod/PrivilegedCommand.kt).
 */

/** The pages the toolbox opens as children of the Titan 2 Elite tools screen. */
enum class ToolboxPage(val child: String) {
    BLOAT("bloat"),
    DENSITY("density"),
    TWEAKS("tweaks"),
    KEY_MAPPING("keymap");

    companion object {
        /** The `settingsChild` section this screen reads. */
        const val SECTION = "t2e_toolbox"

        fun fromChild(value: String?): ToolboxPage? = entries.firstOrNull { it.child == value }
    }
}

private fun openToolboxPage(context: Context, page: ToolboxPage) =
    openSettingsChild(context, ToolboxPage.SECTION, page.child)

/** Renders the page a child activity was opened for. */
@Composable
fun ToolboxPageHost(page: ToolboxPage, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    when (page) {
        // The pairing card is at the top of the tools screen, which is where "back" lands.
        ToolboxPage.BLOAT -> BloatRemoverScreen(modifier = modifier, onBack = onBack, onOpenPairing = onBack)
        ToolboxPage.DENSITY -> DisplayDensityScreen(modifier = modifier, onBack = onBack, onOpenPairing = onBack)
        ToolboxPage.TWEAKS -> SystemTweaksScreen(modifier = modifier, onBack = onBack, onOpenPairing = onBack)
        ToolboxPage.KEY_MAPPING -> KeyMappingScreen(
            modifier = modifier,
            onBack = onBack,
            // Fn's layer and modifier role live on Pastiera's Fn layer (nav mode) screen.
            onFnLayer = { openSettingsPage(context, SettingsPage(SettingsDestination.NavMode)) }
        )
    }
}

/** Top bar and scroll container shared by the toolbox pages. */
@Composable
internal fun ToolboxPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
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
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            content = content
        )
    }
}

/** The toolbox rows for the Titan 2 Elite tools screen. */
@Composable
fun ToolboxSection() {
    val context = LocalContext.current

    SettingsSectionDivider(stringResource(R.string.toolbox_group_system))

    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_BLOAT, R.string.bloat_title, R.string.bloat_row_description) {
        openToolboxPage(context, ToolboxPage.BLOAT)
    }
    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_DENSITY, R.string.density_title, R.string.density_row_description) {
        openToolboxPage(context, ToolboxPage.DENSITY)
    }
    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_TWEAKS, R.string.tweaks_title, R.string.tweaks_row_description) {
        openToolboxPage(context, ToolboxPage.TWEAKS)
    }
    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_KEY_MAPPING, R.string.keymap_title, R.string.keymap_row_description) {
        openToolboxPage(context, ToolboxPage.KEY_MAPPING)
    }

    FnCtrlCard(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_FN_CTRL))
    ShowStatusBarRow(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_SHOW_STATUS_BAR))
    ResetToStockRow(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RESET_TO_STOCK))
}

@Composable
private fun ToolboxLinkRow(
    linkId: String,
    @StringRes title: Int,
    @StringRes description: Int,
    onClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().settingRow(linkId, onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The master "Show status bar" switch. Off collapses the on-screen strip; the keyboard stays active. */
@Composable
private fun ShowStatusBarRow(modifier: Modifier) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(SettingsManager.getShowStatusBar(context)) }
    fun toggle(value: Boolean) {
        show = value
        SettingsManager.setShowStatusBar(context, value)
    }
    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.show_status_bar_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.show_status_bar_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = show, onCheckedChange = ::toggle)
        }
    }
}

private enum class FnAction { APPLY, REVERT }

/**
 * Fn -> Ctrl at the system level (experimental). The write goes through "Modify system settings"
 * when granted, otherwise the paired broker; if neither is available the grant screen opens, and
 * whichever action was asked for is the one that resumes when the user comes back.
 */
@Composable
private fun FnCtrlCard(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var alreadySet by remember { mutableStateOf(false) }
    var statusRes by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf<FnAction?>(null) }
    var grantReturned by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        alreadySet = withContext(Dispatchers.IO) { FnCtrlRemap.isSet(context) }
    }

    val grantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        grantReturned++
    }

    fun startFn(action: FnAction, askPermission: () -> Unit) {
        if (busy) return
        // A tap (finding 1.3g). On the way back from the grant screen the write goes through the
        // Settings API, so a null trigger there costs nothing.
        val trigger = BrokerTrigger.fromTap(context, if (action == FnAction.APPLY) "fn_ctrl_apply" else "fn_ctrl_revert")
        busy = true
        statusRes = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (action == FnAction.APPLY) FnCtrlRemap.apply(context, trigger) else FnCtrlRemap.revert(context, trigger)
            }
            PrivilegedSetup.queueFinishSetup(context, trigger)
            when (result) {
                FnCtrlRemap.Result.SUCCESS -> {
                    alreadySet = action == FnAction.APPLY
                    statusRes = if (action == FnAction.APPLY) R.string.fn_ctrl_status_success else R.string.fn_ctrl_reset_success
                }
                FnCtrlRemap.Result.NEEDS_PERMISSION -> {
                    statusRes = R.string.fn_ctrl_status_needs_permission
                    resume = action
                    askPermission()
                }
                FnCtrlRemap.Result.FAILED -> {
                    statusRes = if (action == FnAction.APPLY) R.string.fn_ctrl_status_failed else R.string.fn_ctrl_reset_failed
                }
            }
            busy = false
        }
    }

    fun launchGrantScreen() {
        runCatching {
            grantLauncher.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            )
        }
    }

    // Back from the grant screen: if it was granted, finish what the user asked for.
    LaunchedEffect(grantReturned) {
        val action = resume
        if (grantReturned > 0 && action != null && Settings.System.canWrite(context)) {
            resume = null
            startFn(action) {}
        }
    }

    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fn_ctrl_map_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.fn_ctrl_map_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = stringResource(R.string.fn_ctrl_map_warning),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (alreadySet) {
                    Text(
                        text = stringResource(R.string.fn_ctrl_map_already_set),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                statusRes?.let { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (alreadySet && !busy) {
                    TextButton(
                        onClick = { startFn(FnAction.REVERT, ::launchGrantScreen) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.fn_ctrl_reset_action),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(onClick = { startFn(FnAction.APPLY, ::launchGrantScreen) }) {
                    Text(
                        text = stringResource(R.string.fn_ctrl_map_action),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** "Reset device settings to stock": undoes what survives an uninstall, so it comes before one. */
@Composable
private fun ResetToStockRow(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var inProgress by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .settingRow(null) { if (!inProgress) showDialog = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SettingsBackupRestore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.reset_to_stock_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Text(
                    text = stringResource(R.string.reset_to_stock_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (inProgress) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.reset_to_stock_confirm_title)) },
            text = { Text(stringResource(R.string.reset_to_stock_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    inProgress = true
                    val trigger = BrokerTrigger.fromTap(context, "toolbox_reset_to_stock")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { SystemChangeManager.resetToStock(context, trigger) }
                        PrivilegedSetup.queueFinishSetup(context, trigger)
                        inProgress = false
                        val message = when {
                            result.allSucceeded -> R.string.reset_to_stock_result_success
                            result.needsPermission -> R.string.reset_to_stock_result_needs_permission
                            else -> R.string.reset_to_stock_result_partial
                        }
                        Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.reset_to_stock_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.reset_to_stock_confirm_cancel))
                }
            }
        )
    }
}
