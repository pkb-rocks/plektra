package rocks.pkb.plektra.training

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.LocalizedComponentActivity
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceHub
import rocks.pkb.plektra.ui.theme.PlektraTheme
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Typing exercise: type random frequent words as fast as possible without correcting
 * mistakes. Corrections are fixed to literal entry, deletions are blocked and the
 * software-keyboard touches of this field are recorded for later analysis (#130, #131).
 */
class TypingTrainingActivity : LocalizedComponentActivity() {

    private data class SessionFile(val file: File, val time: String, val profile: String, val shared: Boolean)

    private val languages by lazy { TypingTrainingContent.languages(assets) }
    private var language by mutableStateOf("en")
    private var wordList by mutableStateOf("1k")
    private var mode by mutableStateOf(MODE_TIME)
    private var contentType by mutableStateOf(TypingTrainingContent.WORDS)
    private var speaker by mutableStateOf("A")
    private var plan: TrainingPlan? = null
    private var posture by mutableStateOf("two_thumbs")
    private var ignoreCase by mutableStateOf(true)
    private var ignoreAccents by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var typedText by mutableStateOf("")
    private var remainingLabel by mutableStateOf("")
    private var keyboardMismatch by mutableStateOf<String?>(null)
    private var lastResult by mutableStateOf<String?>(null)
    private val sessions = mutableStateListOf<SessionFile>()
    private var shareStatus by mutableStateOf<String?>(null)
    private var sharing by mutableStateOf(false)
    private var showShareConsent by mutableStateOf(false)
    private val uploader by lazy {
        TypingTrainingUploader(
            baseUrl = BuildConfig.TYPING_TRAINING_UPLOAD_URL.trimEnd('/'),
            recipient = BuildConfig.TYPING_TRAINING_AGE_RECIPIENT,
            appVersion = BuildConfig.VERSION_NAME,
            uploadedLog = File(sessionDir(), "shared-ids.txt")
        )
    }
    private val flaggedTargets = mutableStateListOf<Int>()

    private var recorder: TypingTrainingRecorder? = null
    private var refreshJob: Job? = null
    private val sessionIds = java.util.concurrent.ConcurrentHashMap<String, Pair<Pair<Long, Long>, String>>()
    private var targetWords: List<String> = emptyList()
    private var wordListSha256 = ""
    private var seed = 0L
    private var startedAtEpochMs = 0L
    private var firstInputUptimeMs: Long? = null
    private var blockedEdits = 0
    private var foldingFeatures: List<FoldingFeature> = emptyList()
    private var startPosture: TypingTrainingPosture? = null
    private var startContext: TypingTrainingContext? = null
    private var stallMonitor: MainThreadStallMonitor? = null
    private var deviceObserver: TypingTrainingInputDeviceObserver? = null
    private var input: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        language = defaultLanguage()
        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@TypingTrainingActivity)
                .windowLayoutInfo(this@TypingTrainingActivity)
                .collect { info ->
                    foldingFeatures = info.displayFeatures.filterIsInstance<FoldingFeature>()
                    recorder?.timeline?.onFold(foldValues())
                }
        }
        refreshSessions()
        setContent {
            PlektraTheme {
                BackHandler(enabled = running) { finishSession(saved = false) }
                TrainingScreen()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recorder?.timeline?.onWindow(windowValues())
    }

    override fun onPause() {
        super.onPause()
        if (running) finishSession(saved = false)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TrainingScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.typing_training_title)) },
                    navigationIcon = {
                        IconButton(onClick = { if (running) finishSession(saved = false) else finish() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back_content_description)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            if (running) {
                ExerciseContent(Modifier.padding(padding).imePadding())
            } else {
                SetupContent(Modifier.padding(padding))
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SetupContent(modifier: Modifier) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.typing_training_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LanguageChooser()
            val contentLabels = mapOf(
                TypingTrainingContent.WORDS to stringResource(R.string.typing_training_content_words),
                TypingTrainingContent.PHRASES to stringResource(R.string.typing_training_content_phrases),
                TypingTrainingContent.CHAT to stringResource(R.string.typing_training_content_chat),
                TypingTrainingContent.TOKENS to stringResource(R.string.typing_training_content_tokens)
            )
            val contentTypes = remember(language) { TypingTrainingContent.contentTypes(assets, language) }
            if (contentType !in contentTypes) contentType = TypingTrainingContent.WORDS
            ChoiceRow(
                stringResource(R.string.typing_training_content),
                contentTypes.map { it to contentLabels.getValue(it) },
                contentType
            ) { contentType = it }
            if (contentType == TypingTrainingContent.CHAT) {
                ChoiceRow(
                    stringResource(R.string.typing_training_chat_speaker),
                    listOf(
                        "A" to stringResource(R.string.typing_training_chat_speaker_a),
                        "B" to stringResource(R.string.typing_training_chat_speaker_b)
                    ),
                    speaker
                ) { speaker = it }
            }
            if (contentType == TypingTrainingContent.WORDS) ChoiceRow(
                stringResource(R.string.typing_training_word_list),
                listOf("1k" to stringResource(R.string.typing_training_words_1k), "10k" to stringResource(R.string.typing_training_words_10k)),
                wordList
            ) { wordList = it }
            ChoiceRow(
                stringResource(R.string.typing_training_length),
                listOf(MODE_TIME to stringResource(R.string.typing_training_length_60s), MODE_WORDS to stringResource(R.string.typing_training_length_50_words)),
                mode
            ) { mode = it }
            ChoiceRow(
                stringResource(R.string.typing_training_posture),
                listOf(
                    "two_thumbs" to stringResource(R.string.typing_training_posture_two_thumbs),
                    "one_thumb" to stringResource(R.string.typing_training_posture_one_thumb),
                    "index_finger" to stringResource(R.string.typing_training_posture_index_finger)
                ),
                posture
            ) { posture = it }
            ChoiceRow(
                stringResource(R.string.typing_training_case),
                listOf(
                    "ignore" to stringResource(R.string.typing_training_case_ignore),
                    "respect" to stringResource(R.string.typing_training_case_respect)
                ),
                if (ignoreCase) "ignore" else "respect"
            ) { ignoreCase = it == "ignore" }
            ChoiceRow(
                stringResource(R.string.typing_training_accents),
                listOf(
                    "respect" to stringResource(R.string.typing_training_accents_respect),
                    "ignore" to stringResource(R.string.typing_training_accents_ignore)
                ),
                if (ignoreAccents) "ignore" else "respect"
            ) { ignoreAccents = it == "ignore" }
            Text(
                stringResource(
                    when (language) {
                        "de" -> R.string.typing_training_layout_hint_de
                        "en" -> R.string.typing_training_layout_hint_en
                        else -> R.string.typing_training_layout_hint_other
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { startSession() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.typing_training_start))
            }
            lastResult?.let { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(result, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            CalibrationSection()
            HorizontalDivider()
            Text(stringResource(R.string.typing_training_sessions), style = MaterialTheme.typography.titleMedium)
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.typing_training_no_sessions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ShareSection()
            sessions.forEach { session ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(session.time, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            session.profile,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (session.shared) {
                        Icon(
                            Icons.Filled.CloudDone,
                            contentDescription = stringResource(R.string.typing_training_shared),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { exportSession(session.file) }) {
                        Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.typing_training_export))
                    }
                    IconButton(onClick = {
                        session.file.delete()
                        refreshSessions()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.typing_training_delete))
                    }
                }
            }
        }
    }

    @Composable
    private fun CalibrationSection() {
        OutlinedButton(
            onClick = { startActivity(android.content.Intent(this, KeyCorrectionActivity::class.java)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.key_correction_open_calibration)) }
    }

    @Composable
    private fun ShareSection() {
        if (sessions.isEmpty()) return
        val configured = BuildConfig.TYPING_TRAINING_UPLOAD_URL.isNotBlank()
        val pending = sessions.count { !it.shared }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = {
                    if (SettingsManager.getPreferences(this@TypingTrainingActivity).getBoolean(SHARE_CONSENT_KEY, false)) {
                        shareAll()
                    } else {
                        showShareConsent = true
                    }
                },
                enabled = configured && !sharing && pending > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.typing_training_share_all, pending))
            }
            val status = when {
                !configured -> stringResource(R.string.typing_training_share_not_configured)
                else -> shareStatus
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showShareConsent) {
            AlertDialog(
                onDismissRequest = { showShareConsent = false },
                title = { Text(stringResource(R.string.typing_training_share_consent_title)) },
                text = { Text(stringResource(R.string.typing_training_share_consent_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        showShareConsent = false
                        SettingsManager.getPreferences(this@TypingTrainingActivity).edit()
                            .putBoolean(SHARE_CONSENT_KEY, true).apply()
                        shareAll()
                    }) { Text(stringResource(R.string.typing_training_share_consent_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showShareConsent = false }) {
                        Text(stringResource(R.string.typing_training_share_consent_cancel))
                    }
                }
            )
        }
    }

    private fun shareAll() {
        val files = sessions.filter { !it.shared }.map { it.file }
        sharing = true
        shareStatus = getString(R.string.typing_training_share_progress, 0, files.size)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                uploader.uploadAll(files) { done, total ->
                    lifecycleScope.launch {
                        shareStatus = getString(R.string.typing_training_share_progress, done, total)
                    }
                }
            }
            sharing = false
            shareStatus = if (result.failed == 0) {
                getString(R.string.typing_training_share_done, result.uploaded + result.alreadyKnown)
            } else {
                getString(R.string.typing_training_share_failed, result.failed)
            }
            refreshSessions()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LanguageChooser() {
        var expanded by remember { mutableStateOf(false) }
        val title = stringResource(R.string.typing_training_language)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = languageName(language),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .semantics { contentDescription = title }
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    languages.sortedBy(::languageName).forEach { code ->
                        DropdownMenuItem(
                            text = { Text(languageName(code)) },
                            onClick = {
                                language = code
                                expanded = false
                            },
                            leadingIcon = { if (code == language) Icon(Icons.Filled.Check, contentDescription = null) },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
    }

    private fun languageName(code: String): String {
        val appLocale = resources.configuration.locales[0]
        return Locale.forLanguageTag(code).getDisplayLanguage(appLocale).replaceFirstChar { it.titlecase(appLocale) }
    }

    /** The app language when practice material exists for it, else English. */
    private fun defaultLanguage(): String {
        val app = resources.configuration.locales[0].language.let { if (it == "nb" || it == "nn") "no" else it }
        return app.takeIf { it in languages } ?: "en"
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (id, label) ->
                    FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) })
                }
            }
        }
    }

    @Composable
    private fun ExerciseContent(modifier: Modifier) {
        LaunchedEffect(Unit) {
            while (running) {
                updateProgress()
                delay(250)
            }
        }
        val currentIndex = TypingTrainingAlignment.align(normalized(typedText), targetWords, ignoreCase, ignoreAccents).currentTarget
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    remainingLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { finishSession(saved = true) }) {
                    Text(stringResource(R.string.typing_training_stop))
                }
            }
            keyboardMismatch?.let { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val start = (currentIndex - 2).coerceAtLeast(0)
            val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            val current = MaterialTheme.colorScheme.primary
            val error = MaterialTheme.colorScheme.error
            fun styleFor(index: Int): SpanStyle = when {
                index < currentIndex -> SpanStyle(color = muted)
                index == currentIndex -> SpanStyle(color = current, fontWeight = FontWeight.Bold)
                else -> SpanStyle()
            }.let { if (index in flaggedTargets) it.copy(textDecoration = TextDecoration.Underline, color = error) else it }
            fun display(text: String) = if (ignoreCase) text.lowercase() else text
            val chatPlan = plan?.takeIf { it.contentType == TypingTrainingContent.CHAT }
            // Character offset of the current target word, to keep it in view.
            var currentOffset = -1
            val transcript = buildAnnotatedString {
                if (chatPlan == null) {
                    targetWords.drop(start).take(30).forEachIndexed { offset, target ->
                        if (start + offset == currentIndex) currentOffset = length
                        withStyle(styleFor(start + offset)) { append(display(target)) }
                        append(' ')
                    }
                } else {
                    // Chat transcript: partner messages as context, own messages word by word.
                    var targetIndex = 0
                    chatPlan.items.forEach { item ->
                        withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Bold)) { append("${item.speaker}: ") }
                        item.text.split(' ').filter { it.isNotEmpty() }.forEach { token ->
                            if (item.own) {
                                if (targetIndex == currentIndex) currentOffset = length
                                withStyle(styleFor(targetIndex)) { append(display(token)) }
                                targetIndex++
                            } else {
                                withStyle(SpanStyle(color = muted)) { append(token) }
                            }
                            append(' ')
                        }
                        append('\n')
                    }
                }
                if (currentOffset < 0) currentOffset = length
            }
            val scroll = rememberScrollState()
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            var viewportHeight by remember { mutableIntStateOf(0) }
            // The keyboard shrinks the viewport; scroll so the current word stays in the upper third.
            LaunchedEffect(currentOffset, layout, viewportHeight) {
                val textLayout = layout ?: return@LaunchedEffect
                if (viewportHeight == 0) return@LaunchedEffect
                val line = textLayout.getLineForOffset(currentOffset.coerceIn(0, textLayout.layoutInput.text.length))
                scroll.animateScrollTo((textLayout.getLineTop(line) - viewportHeight / 3f).toInt().coerceAtLeast(0))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewportHeight = it.height }
                    .verticalScroll(scroll)
            ) {
                Text(transcript, fontSize = 26.sp, lineHeight = 34.sp, onTextLayout = { layout = it })
            }
            AndroidView(
                factory = { context -> createInputField(context) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.padding(2.dp))
        }
    }

    private fun createInputField(context: Context): EditText = EditText(context).apply {
        textSize = 20f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        privateImeOptions = "${InputContextState.LITERAL_ENTRY_IME_OPTION},${TouchTraceHub.TRAINING_IME_OPTION}"
        maxLines = 3
        filters = arrayOf(AppendOnlyFilter())
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!running) return
                if (firstInputUptimeMs == null && !s.isNullOrEmpty()) firstInputUptimeMs = SystemClock.uptimeMillis()
                typedText = s?.toString().orEmpty()
                updateProgress()
            }
        })
        input = this
        post {
            requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startSession() {
        seed = System.currentTimeMillis()
        val sessionPlan = runCatching {
            TypingTrainingContent.plan(
                assets = assets,
                contentType = contentType,
                language = language,
                wordList = wordList,
                speaker = speaker,
                seed = seed,
                maxTargets = if (mode == MODE_WORDS) WORD_GOAL else 400
            )
        }.getOrNull()?.takeIf { it.targets.isNotEmpty() } ?: run {
            android.widget.Toast.makeText(this, R.string.typing_training_content_unavailable, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        plan = sessionPlan
        wordListSha256 = sessionPlan.contentSha256
        targetWords = sessionPlan.targets
        startedAtEpochMs = System.currentTimeMillis()
        firstInputUptimeMs = null
        blockedEdits = 0
        flaggedTargets.clear()
        typedText = ""
        keyboardMismatch = null
        lastResult = null
        startPosture = currentPosture()
        startContext = TypingTrainingContext.capture(this)
        val sessionRecorder = TypingTrainingRecorder { onBackspaceAttempt(input?.text?.toString().orEmpty()) }
        recorder = sessionRecorder
        TouchTraceHub.sink = sessionRecorder
        stallMonitor = MainThreadStallMonitor(onStall = sessionRecorder::onDiagnostic).also { it.start() }
        sessionRecorder.timeline.onFold(foldValues())
        sessionRecorder.timeline.onWindow(windowValues())
        deviceObserver = TypingTrainingInputDeviceObserver(this, sessionRecorder.timeline).also { it.start() }
        running = true
        updateProgress()
    }

    private fun updateProgress() {
        if (!running) return
        val currentIndex = TypingTrainingAlignment.align(normalized(typedText), targetWords, ignoreCase, ignoreAccents).currentTarget
        val elapsedMs = firstInputUptimeMs?.let { SystemClock.uptimeMillis() - it } ?: 0L
        val done = currentIndex >= targetWords.size || (mode == MODE_TIME && elapsedMs >= TIME_LIMIT_MS)
        if (done) {
            finishSession(saved = true)
            return
        }
        remainingLabel = if (mode == MODE_WORDS) {
            "$currentIndex / ${targetWords.size}"
        } else {
            "${((TIME_LIMIT_MS - elapsedMs) / 1000L).coerceAtLeast(0)} s"
        }
        keyboardMismatch = recorder?.geometries?.lastOrNull()?.let(::layoutMismatch)
    }

    private fun layoutMismatch(geometry: KeyboardGeometrySnapshot): String? {
        val matches = when (language) {
            "de" -> geometry.layoutName.contains("qwertz", ignoreCase = true) && geometry.layoutStyle == "EXTENDED_ISO"
            "en" -> geometry.layoutName.contains("qwerty", ignoreCase = true)
            // Other languages: the layout has to offer the base letters of the text; accents may
            // sit on long press.
            else -> {
                fun base(text: String) = TypingTrainingAlignment.fold(text, ignoreCase = true, ignoreAccents = true)
                val keys = geometry.keys.filter { it.type == "CHAR" }.mapTo(HashSet()) { base(it.output) }
                val letters = base(targetWords.take(100).joinToString("")).filter(Char::isLetter)
                letters.isEmpty() || letters.count { it.toString() in keys } >= letters.length * 0.9
            }
        }
        if (matches) return null
        return getString(R.string.typing_training_layout_mismatch, geometry.layoutName, geometry.layoutStyle)
    }

    private fun finishSession(saved: Boolean) {
        val sessionRecorder = recorder ?: return
        recorder = null
        running = false
        stallMonitor?.stop()
        stallMonitor = null
        deviceObserver?.stop()
        deviceObserver = null
        if (TouchTraceHub.sink === sessionRecorder) TouchTraceHub.sink = null
        val typed = input?.text?.toString().orEmpty()
        input?.let { field ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        }
        input = null
        if (!saved || typed.isBlank()) return
        val typedWords = normalized(typed).trim().split(' ')
        val alignment = TypingTrainingAlignment.align(normalized("$typed "), targetWords, ignoreCase, ignoreAccents)
        val session = TypingTrainingSession(
            startedAtEpochMs = startedAtEpochMs,
            firstInputUptimeMs = firstInputUptimeMs,
            endedUptimeMs = SystemClock.uptimeMillis(),
            language = language,
            wordList = if (contentType == TypingTrainingContent.WORDS) "$language-$wordList" else "$contentType-$language",
            wordListSha256 = wordListSha256,
            seed = seed,
            sessionMode = mode,
            targetWords = targetWords.take(alignment.currentTarget.coerceAtLeast(1)),
            typedText = typed,
            blockedEdits = blockedEdits,
            ignoreCase = ignoreCase,
            ignoreAccents = ignoreAccents,
            backspaceMarks = sessionRecorder.backspaceMarks.toList(),
            device = TypingTrainingDevice(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkInt = Build.VERSION.SDK_INT,
                appVersion = BuildConfig.VERSION_NAME,
                densityDpi = resources.displayMetrics.densityDpi,
                xdpi = resources.displayMetrics.xdpi,
                ydpi = resources.displayMetrics.ydpi
            ),
            posture = withKeyboardProfile(startPosture ?: currentPosture(), sessionRecorder, first = true),
            postureAtEnd = withKeyboardProfile(currentPosture(), sessionRecorder, first = false),
            geometries = sessionRecorder.geometries.toList(),
            touches = sessionRecorder.touches.toList(),
            commits = sessionRecorder.commits.toList(),
            hardwareKeys = sessionRecorder.hardwareKeys.toList(),
            textEdits = sessionRecorder.textEdits.toList(),
            contentType = contentType,
            speaker = speaker.takeIf { contentType == TypingTrainingContent.CHAT },
            contentItems = plan?.items.orEmpty(),
            plannedTargets = targetWords,
            diagnostics = sessionRecorder.diagnostics.toList(),
            context = startContext,
            contextAtEnd = TypingTrainingContext.capture(this),
            contextChanges = sessionRecorder.timeline.changes.toList(),
            inputDeviceEpisodes = sessionRecorder.timeline.inputDeviceEpisodes.toList(),
            keyDecisions = sessionRecorder.keyDecisions.toList(),
            timerEvents = sessionRecorder.timerEvents.toList(),
            captureStats = sessionRecorder.captureStats()
        )
        val elapsedMinutes = ((session.endedUptimeMs - (session.firstInputUptimeMs ?: session.endedUptimeMs)) / 60_000.0)
            .coerceAtLeast(1.0 / 60)
        val completedTokens = normalized("$typed ").split(' ').dropLast(1)
        val exactWords = alignment.tokenTargets.withIndex().count { (token, target) ->
            target >= 0 && alignment.tokenTargets.count { it == target } == 1 &&
                TypingTrainingAlignment.fold(completedTokens[token], ignoreCase, ignoreAccents) ==
                TypingTrainingAlignment.fold(targetWords[target], ignoreCase, ignoreAccents)
        }
        lastResult = getString(
            R.string.typing_training_result,
            (typed.length / 5.0 / elapsedMinutes).toInt(),
            exactWords,
            alignment.currentTarget
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                File(sessionDir(), "session-${session.startedAtEpochMs}-${session.posture.profileKey}.json")
                    .writeText(json.encodeToString(TypingTrainingSession.serializer(), session))
            }
            refreshSessions()
        }
    }

    private fun foldValues(): Map<String, String> = mapOf(
        "foldState" to foldState(),
        "foldingFeatures" to foldingFeatures.joinToString(";") { "${it.state} ${it.orientation} ${it.bounds}" }
    )

    private fun windowValues(): Map<String, String> {
        val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
        val configuration = resources.configuration
        return mapOf(
            "orientation" to if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait",
            "windowWidthPx" to bounds.width().toString(),
            "windowHeightPx" to bounds.height().toString(),
            "screenWidthDp" to configuration.screenWidthDp.toString(),
            "screenHeightDp" to configuration.screenHeightDp.toString(),
            "densityDpi" to configuration.densityDpi.toString(),
            "hardKeyboardHidden" to configuration.hardKeyboardHidden.toString()
        )
    }

    private fun foldState(): String = when {
        foldingFeatures.isEmpty() -> "no_fold"
        foldingFeatures.any { it.state == FoldingFeature.State.HALF_OPENED } -> "half_opened"
        else -> "unfolded"
    }

    private fun currentPosture(): TypingTrainingPosture {
        val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
        val configuration = resources.configuration
        val orientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val foldState = foldState()
        return TypingTrainingPosture(
            profileKey = "$foldState-$orientation",
            foldState = foldState,
            foldingFeatures = foldingFeatures.map { "${it.state} ${it.orientation} ${it.bounds}" },
            orientation = orientation,
            windowWidthPx = bounds.width(),
            windowHeightPx = bounds.height(),
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            typingPosture = posture
        )
    }

    /**
     * Completes the profile key with the keyboard actually used: the on-screen layout, or the
     * hardware keyboard identified by Plektra's device profile.
     */
    private fun withKeyboardProfile(
        posture: TypingTrainingPosture,
        recorder: TypingTrainingRecorder,
        first: Boolean
    ): TypingTrainingPosture {
        val geometry = if (first) recorder.geometries.firstOrNull() else recorder.geometries.lastOrNull()
        val suffix = when {
            geometry != null -> "${geometry.layoutName}-${geometry.layoutStyle}"
            recorder.hardwareKeys.isNotEmpty() -> "hw-" + (startContext?.plektraDevice?.get("stableId")?.ifBlank { null }
                ?: Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "_"))
            else -> return posture
        }
        return posture.copy(profileKey = "${posture.profileKey}-$suffix")
    }

    /** Reads and hashes recordings off the main thread; a newer refresh replaces a running one. */
    private fun refreshSessions() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val loaded = withContext(Dispatchers.IO) {
                val files = sessionDir().listFiles { file -> file.extension == "json" }?.sortedByDescending { it.name }.orEmpty()
                val shared = uploader.uploadedIds()
                files.mapNotNull { file ->
                    val parts = file.nameWithoutExtension.removePrefix("session-").split('-', limit = 2)
                    val time = parts.firstOrNull()?.toLongOrNull()?.let { format.format(Date(it)) } ?: file.name
                    // A session deleted while the list loads is left out.
                    val id = runCatching { sessionId(file) }.getOrNull() ?: return@mapNotNull null
                    SessionFile(file = file, time = time, profile = parts.getOrNull(1).orEmpty(), shared = id in shared)
                }
            }
            sessions.clear()
            sessions += loaded
        }
    }

    /** Session ids by path, reused while size and modification time are unchanged. */
    private fun sessionId(file: File): String {
        val key = file.length() to file.lastModified()
        sessionIds[file.path]?.takeIf { it.first == key }?.let { return it.second }
        return TypingTrainingUploader.sessionId(file.readBytes()).also { sessionIds[file.path] = key to it }
    }

    private fun exportSession(file: File) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("MediaStore insert failed")
                    contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            android.widget.Toast.makeText(
                this@TypingTrainingActivity,
                if (ok) getString(R.string.typing_training_exported, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER")
                else getString(R.string.typing_training_export_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onBackspaceAttempt(text: String) {
        if (!running) return
        val afterSpace = text.isEmpty() || text.last().isWhitespace()
        val alignment = TypingTrainingAlignment.align(normalized(text), targetWords, ignoreCase, ignoreAccents)
        val tokens = normalized(text).split(' ')
        val (targetIndex, charInWord) = if (afterSpace) {
            val lastToken = tokens.dropLast(1).indexOfLast { it.isNotEmpty() }
            (alignment.tokenTargets.getOrNull(lastToken) ?: return) to tokens[lastToken].length
        } else {
            alignment.currentTarget to tokens.last().length
        }
        if (targetIndex < 0) return
        recorder?.onBackspaceMark(BackspaceMark(SystemClock.uptimeMillis(), text.length, targetIndex, charInWord, afterSpace))
        if (targetIndex !in flaggedTargets) flaggedTargets += targetIndex
    }

    /** Enter sends a chat message; for alignment it separates words like a space. */
    private fun normalized(text: String): String = text.replace('\n', ' ')

    private fun sessionDir(): File = File(filesDir, "typing-training").apply { mkdirs() }

    /**
     * Keeps mistakes in the recording: appends and the IME's own replacements at the end of the
     * input (long press, variations) pass; edits elsewhere in the text are blocked.
     */
    private inner class AppendOnlyFilter : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val replaced = dest.subSequence(dstart, dend)
            val replacement = source.subSequence(start, end)
            val outcome = when {
                dend == dest.length && replacement.startsWith(replaced) -> "append"
                // Backspace never reaches the editor in training (the IME turns it into a mark), so
                // edits at the end are the IME's own replacements: long press, variations, multi-tap.
                dend == dest.length -> "ime_edit"
                // Edits elsewhere (moved cursor, selection) would silently rewrite recorded mistakes.
                else -> "blocked"
            }
            if (running && (replaced.isNotEmpty() || replacement.isNotEmpty())) {
                recorder?.onTextEdit(TextEdit(SystemClock.uptimeMillis(), dstart, dend, replaced.toString(), replacement.toString(), outcome))
            }
            if (outcome != "blocked") return null
            blockedEdits++
            return replaced
        }
    }

    private companion object {
        const val MODE_TIME = "time-60s"
        const val MODE_WORDS = "words-50"
        const val WORD_GOAL = 50
        const val TIME_LIMIT_MS = 60_000L
        const val EXPORT_FOLDER = "Plektra typing training"
        // v2: the consent text now lists the full technical context.
        const val SHARE_CONSENT_KEY = "typing_training_share_consent_v2"
        val json = Json { encodeDefaults = true }
    }
}
