package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import rocks.pkb.plektra.inputmethod.speech.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechSettingsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var futoAvailable by remember { mutableStateOf(ExternalSpeechProvider.isAvailable(context)) }
    var futoEnabled by remember { mutableStateOf(ExternalSpeechProvider.enabledVoiceInput(context) != null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        futoAvailable = ExternalSpeechProvider.isAvailable(context)
        futoEnabled = ExternalSpeechProvider.enabledVoiceInput(context) != null
    }
    var altCtrlSpeechShortcut by remember { mutableStateOf(SettingsManager.getAltCtrlSpeechShortcutEnabled(context)) }
    DisposableEffect(context) {
        val prefs = SettingsManager.getPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "alt_ctrl_speech_shortcut") altCtrlSpeechShortcut = SettingsManager.getAltCtrlSpeechShortcutEnabled(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val client = remember { SpeechApiClient() }
    var provider by remember { mutableStateOf(SpeechSettings.provider(context)) }
    var silenceMs by remember { mutableStateOf(SpeechSettings.silenceMs(context)) }
    var maxDurationMs by remember { mutableStateOf(SpeechSettings.maxDurationMs(context)) }
    var durationPicker by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(emptyList<SpeechModel>()) }
    var catalogRefresh by remember { mutableIntStateOf(0) }
    var selectedModel by remember(provider) { mutableStateOf(SpeechSettings.model(context, provider)) }
    var loading by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf(false) }
    var silencePicker by remember { mutableStateOf(false) }
    var previewPicker by remember { mutableStateOf(false) }
    var continuousPreview by remember { mutableStateOf(SpeechSettings.continuousPreview(context)) }

    fun providerName(value: SpeechProvider) = when (value) {
        SpeechProvider.SYSTEM -> "Android"
        SpeechProvider.FUTO -> "FUTO"
        SpeechProvider.OPENROUTER -> "OpenRouter"
        SpeechProvider.OPENAI -> "OpenAI"
    }
    fun pauseName(value: Long) = when (value) {
        0L -> context.getString(R.string.speech_pause_manual)
        1500L -> context.getString(R.string.speech_pause_short)
        5000L -> context.getString(R.string.speech_pause_long)
        else -> context.getString(R.string.speech_pause_default)
    }

    fun durationName(value: Long) = when (value) {
        0L -> context.getString(R.string.speech_duration_unlimited)
        60_000L -> context.getString(R.string.speech_duration_one_minute)
        else -> context.getString(R.string.speech_duration_minutes, value / 60_000)
    }

    fun refreshModels() {
        catalog = emptyList(); loading = true; catalogError = null
        catalogRefresh++
    }
    LaunchedEffect(provider, catalogRefresh) {
        if (provider == SpeechProvider.OPENROUTER) {
            catalog = emptyList(); loading = true; catalogError = null
            try {
                val models = client.models()
                val available = SpeechModels.selectZdrModel(models, selectedModel)
                catalog = models
                selectedModel = available
                SpeechSettings.setModel(context, provider, available)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { catalogError = speechErrorMessage(context, e) }
            finally { loading = false }
        } else {
            catalog = emptyList(); loading = false; catalogError = null
        }
    }

    Scaffold(topBar = {
        PlektraSettingsHeader(title = stringResource(R.string.speech_settings_title), onBack = onBack)
    }) { padding ->
        SettingsSectionScrollColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(vertical = 8.dp)
            ) {
                SettingsSupportingText(stringResource(R.string.speech_language_description))
                SettingsToggleRow(
                    title = stringResource(R.string.alt_ctrl_speech_shortcut_title),
                    description = stringResource(R.string.alt_ctrl_speech_shortcut_description),
                    icon = Icons.Default.Keyboard,
                    checked = altCtrlSpeechShortcut,
                    alwaysInlineDescription = true,
                    linkId = SettingLinkIds.TEXT_INPUT_ALT_CTRL_SPEECH_SHORTCUT,
                    onCheckedChange = {
                        altCtrlSpeechShortcut = it
                        SettingsManager.setAltCtrlSpeechShortcutEnabled(context, it)
                    }
                )
                SpeechCard(Modifier.settingRow("speech.provider")) {
                    SettingsSectionDivider(stringResource(R.string.speech_provider_title))
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = SettingsSpace.edge, vertical = 8.dp)) {
                    val columns = if (maxWidth >= 600.dp) 4 else 2
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeechProvider.entries.chunked(columns).forEach { providers ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                providers.forEach { value ->
                                    SpeechProviderCard(value, provider == value, providerName(value), Modifier.weight(1f)) {
                                        provider = value
                                        SpeechSettings.setProvider(context, value)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SpeechCard {
            if (provider.isCloud) {
                SpeechSettingRow(stringResource(R.string.speech_model_title), selectedModel.name, Icons.Default.Memory,
                    Modifier.settingRow("speech.model"), onClick = {
                        if (provider == SpeechProvider.OPENROUTER) refreshModels()
                        modelPicker = true
                    })
                if (provider == SpeechProvider.OPENROUTER) {
                    catalogError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                }
                if (modelPicker) {
                    val defaults = if (provider == SpeechProvider.OPENAI) SpeechModels.openAiDefaults else SpeechModels.openRouterDefaults
                    val models = (if (provider == SpeechProvider.OPENAI) defaults + selectedModel else catalog).distinctBy { it.id }
                        .sortedWith(compareBy<SpeechModel> { defaults.indexOfFirst { default -> default.id == it.id }.let { index -> if (index < 0) Int.MAX_VALUE else index } }.thenBy { it.name })
                    AlertDialog(onDismissRequest = { modelPicker = false }, title = { Text(stringResource(R.string.speech_model_title)) },
                        text = {
                            if (provider == SpeechProvider.OPENROUTER && loading) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                    Text(stringResource(R.string.speech_models_loading))
                                }
                            } else if (provider == SpeechProvider.OPENROUTER && catalogError != null) {
                                Column {
                                    Text(catalogError!!)
                                    TextButton(onClick = { refreshModels() }) { Text(stringResource(R.string.speech_models_refresh)) }
                                }
                            } else LazyColumn {
                                items(models, key = { it.id }) { model ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selectedModel.id == model.id, role = Role.RadioButton) {
                                        selectedModel = model; SpeechSettings.setModel(context, provider, model); modelPicker = false
                                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selectedModel.id == model.id, onClick = null)
                                        Column(Modifier.padding(start = SettingsSpace.controlGap)) {
                                            Text(model.name)
                                            Text(stringResource(when (model.protocol) {
                                                SpeechProtocol.AUDIO_CHAT -> R.string.speech_model_audio
                                                SpeechProtocol.LIVE_TRANSCRIPTION -> R.string.speech_model_live
                                                SpeechProtocol.TRANSCRIPTION -> R.string.speech_model_transcription
                                            }),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text(stringResource(android.R.string.cancel)) } })
                }
            }
                SpeechSettingRow(stringResource(R.string.speech_pause_title), pauseName(silenceMs), Icons.Default.Mic,
                    Modifier.settingRow("speech.pause"), enabled = provider != SpeechProvider.FUTO,
                    onClick = { silencePicker = true })
                SpeechSettingRow(stringResource(R.string.speech_duration_title), durationName(maxDurationMs), Icons.Default.Timer,
                    Modifier.settingRow("speech.duration"), enabled = provider != SpeechProvider.FUTO,
                    onClick = { durationPicker = true })
            SpeechSettingRow(stringResource(R.string.speech_preview_title),
                stringResource(if (continuousPreview) R.string.speech_preview_continuous else R.string.speech_preview_three_words),
                Icons.Default.ViewHeadline, Modifier.settingRow("speech.preview"),
                enabled = provider == SpeechProvider.SYSTEM ||
                    (provider == SpeechProvider.OPENAI && selectedModel.protocol == SpeechProtocol.LIVE_TRANSCRIPTION),
                onClick = { previewPicker = true })
            }
            when (provider) {
                SpeechProvider.SYSTEM -> SpeechCard {
                    SpeechNote(stringResource(R.string.speech_android_availability))
                    SpeechNote(stringResource(R.string.speech_system_pause_description))
                }
                SpeechProvider.FUTO -> SpeechCard {
                    SpeechNote(stringResource(R.string.speech_futo_description))
                    if (!futoAvailable) SpeechNote(stringResource(R.string.speech_external_missing))
                    if (futoAvailable && !futoEnabled) TextButton(onClick = {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.speech_external_enable)) }
                    TextButton(onClick = {
                        val settings = ExternalSpeechProvider.settingsIntent(context)
                        if (settings != null) {
                            try { context.startActivity(settings) }
                            catch (_: android.content.ActivityNotFoundException) { uriHandler.openUri(ExternalSpeechProvider.FUTO_DOWNLOAD_URL) }
                        } else uriHandler.openUri(ExternalSpeechProvider.FUTO_DOWNLOAD_URL)
                    }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(if (futoAvailable) R.string.speech_external_settings else R.string.speech_external_install))
                    }
                }
                else -> SpeechCard {
                    SettingsSectionDivider(stringResource(R.string.speech_api_key_title))
                    key(provider) { SpeechCredentialEditor(provider, client) }
                    if (provider == SpeechProvider.OPENROUTER) Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.speech_zdr_compact), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { uriHandler.openUri("https://openrouter.ai/docs/guides/features/zdr") }) {
                            Icon(Icons.Default.OpenInNew, stringResource(R.string.speech_zdr_docs))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
          }
        }
    }
    if (durationPicker) AlertDialog(onDismissRequest = { durationPicker = false },
        title = { Text(stringResource(R.string.speech_duration_title)) },
        text = { Column(Modifier.selectableGroup()) {
            SpeechSettings.durationOptionsMs.forEach { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = maxDurationMs == value, role = Role.RadioButton) {
                    maxDurationMs = value
                    SpeechSettings.setMaxDurationMs(context, value)
                    durationPicker = false
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(maxDurationMs == value, onClick = null)
                    Text(durationName(value), Modifier.padding(start = SettingsSpace.controlGap))
                }
            }
            SpeechNote(stringResource(R.string.speech_duration_description))
        } }, confirmButton = { TextButton(onClick = { durationPicker = false }) { Text(stringResource(android.R.string.cancel)) } })

    if (previewPicker) AlertDialog(onDismissRequest = { previewPicker = false },
        title = { Text(stringResource(R.string.speech_preview_title)) },
        text = { Column {
            listOf(false, true).forEach { continuous ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = continuousPreview == continuous, role = Role.RadioButton) {
                    continuousPreview = continuous
                    SpeechSettings.setContinuousPreview(context, continuous)
                    previewPicker = false
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(continuousPreview == continuous, onClick = null)
                    Text(stringResource(if (continuous) R.string.speech_preview_continuous else R.string.speech_preview_three_words),
                        Modifier.padding(start = SettingsSpace.controlGap))
                }
            }
            SpeechNote(stringResource(R.string.speech_preview_description))
        } }, confirmButton = { TextButton(onClick = { previewPicker = false }) { Text(stringResource(android.R.string.cancel)) } })

    if (silencePicker) AlertDialog(onDismissRequest = { silencePicker = false }, title = { Text(stringResource(R.string.speech_pause_title)) },
        text = { Column { listOf(1500L, 3000L, 5000L, 0L).forEach { value ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = silenceMs == value, role = Role.RadioButton) { silenceMs = value; SpeechSettings.setSilenceMs(context, value); silencePicker = false },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(silenceMs == value, onClick = null); Text(pauseName(value), Modifier.padding(start = SettingsSpace.controlGap))
            }
        } } }, confirmButton = { TextButton(onClick = { silencePicker = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun SpeechNote(text: String) {
    SettingsSupportingText(text)
}

@Composable
private fun SpeechSettingRow(title: String, subtitle: String?, icon: ImageVector,
    modifier: Modifier = Modifier, expanded: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    SettingsNavigationRow(title = title, value = subtitle, icon = icon, enabled = enabled,
        modifier = modifier, onClick = onClick)
}

/** Groups related rows; settings pages use plain full-width rows instead of cards. */
@Composable
private fun SpeechCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}

@Composable
private fun SpeechProviderCard(provider: SpeechProvider, selected: Boolean, name: String,
    modifier: Modifier, onSelect: () -> Unit) {
    Surface(modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected, onClick = null, Modifier.size(24.dp))
                Text(name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(when (provider) {
                SpeechProvider.SYSTEM -> R.string.speech_provider_android_short
                SpeechProvider.FUTO -> R.string.speech_provider_futo_short
                SpeechProvider.OPENROUTER -> R.string.speech_provider_router_short
                SpeechProvider.OPENAI -> R.string.speech_provider_openai_short
            }), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeechCredentialEditor(provider: SpeechProvider, client: SpeechApiClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(SpeechSettings.hasKey(context, provider)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var keyVisible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().settingRow("speech.credentials").padding(horizontal = 16.dp)) {
        OutlinedTextField(value = newKey, onValueChange = { newKey = it; message = null }, enabled = !busy,
            label = { Text(stringResource(R.string.speech_provider_key, if (provider == SpeechProvider.OPENROUTER) "OpenRouter" else "OpenAI")) },
            placeholder = { Text(stringResource(R.string.speech_key_placeholder)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (keyVisible) R.string.speech_key_hide else R.string.speech_key_show))
            } }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(if (saved) R.string.speech_key_saved else R.string.speech_key_missing)) })
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !busy && newKey.isNotBlank(), onClick = {
                try {
                    SpeechSettings.saveKey(context, provider, newKey)
                    newKey = ""; saved = true; message = context.getString(R.string.speech_key_saved)
                } catch (_: Exception) { message = context.getString(R.string.speech_key_save_error) }
            }) { Text(stringResource(R.string.speech_key_save)) }
            OutlinedButton(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !busy && (newKey.isNotBlank() || saved), onClick = {
                val candidate = newKey.trim()
                busy = true; message = null
                scope.launch {
                    try {
                        val key = candidate.ifBlank { SpeechSettings.readKey(context, provider) ?: "" }
                        client.validateKey(provider, key)
                        message = context.getString(R.string.speech_key_valid)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = speechErrorMessage(context, e) }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (busy) R.string.speech_key_checking else R.string.speech_key_check)) }
        }
        if (saved) TextButton(enabled = !busy, onClick = {
            SpeechSettings.removeKey(context, provider)
            saved = false; message = null
        }) { Text(stringResource(R.string.speech_key_remove)) }
        message?.let { Text(it, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun speechErrorMessage(context: android.content.Context, error: Exception): String = when (error) {
    is SpeechHttpException -> when (error.status) {
        401, 403 -> context.getString(R.string.speech_error_auth)
        402 -> context.getString(R.string.speech_error_credits)
        429 -> context.getString(R.string.speech_error_rate_limit)
        else -> context.getString(R.string.speech_error_http, error.status)
    }
    is NoZdrModelException -> context.getString(R.string.speech_zdr_unavailable)
    is NoSpeechException -> context.getString(R.string.speech_recognition_error_no_match)
    is SpeechRealtimeException -> context.getString(R.string.speech_error_live)
    is java.io.IOException -> context.getString(R.string.speech_recognition_error_network)
    else -> context.getString(R.string.speech_recognition_error_generic)
}
