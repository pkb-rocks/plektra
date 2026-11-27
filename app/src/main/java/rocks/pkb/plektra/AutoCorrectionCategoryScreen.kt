package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import rocks.pkb.plektra.R
import android.content.Intent
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Auto-correction category screen.
 */
@Composable
fun AutoCorrectionCategoryScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var autoCorrectEnabled by remember {
        mutableStateOf(SettingsManager.getAutoCorrectEnabled(context))
    }
    var experimentalSuggestionsEnabled by remember {
        mutableStateOf(SettingsManager.isExperimentalSuggestionsEnabled(context))
    }
    var suggestionsEnabled by remember {
        mutableStateOf(SettingsManager.getSuggestionsEnabled(context))
    }
    var suggestionsPersonalizationEnabled by remember {
        mutableStateOf(SettingsManager.getSuggestionsPersonalizationEnabled(context))
    }
    var suggestionsAddWordButtonEnabled by remember {
        mutableStateOf(SettingsManager.getSuggestionsAddWordButtonEnabled(context))
    }
    var starterWordsEnabled by remember {
        mutableStateOf(SettingsManager.getSuggestionStarterWordsEnabled(context))
    }
    var autoSpaceAfterSuggestion by remember {
        mutableStateOf(SettingsManager.getAutoSpaceAfterSuggestion(context))
    }
    var accentMatchingEnabled by remember {
        mutableStateOf(SettingsManager.getAccentMatchingEnabled(context))
    }
    var autoReplaceOnSpaceEnter by remember {
        mutableStateOf(SettingsManager.getAutoReplaceOnSpaceEnter(context))
    }
    var maxAutoReplaceDistance by remember {
        mutableStateOf(SettingsManager.getMaxAutoReplaceDistance(context))
    }
    var useKeyboardProximity by remember {
        mutableStateOf(SettingsManager.getUseKeyboardProximity(context))
    }
    var useEditTypeRanking by remember {
        mutableStateOf(SettingsManager.getUseEditTypeRanking(context))
    }
    DisposableEffect(context) {
        val preferences = SettingsManager.getPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            autoCorrectEnabled = SettingsManager.getAutoCorrectEnabled(context)
            experimentalSuggestionsEnabled = SettingsManager.isExperimentalSuggestionsEnabled(context)
            suggestionsEnabled = SettingsManager.getSuggestionsEnabled(context)
            suggestionsPersonalizationEnabled = SettingsManager.getSuggestionsPersonalizationEnabled(context)
            suggestionsAddWordButtonEnabled = SettingsManager.getSuggestionsAddWordButtonEnabled(context)
            starterWordsEnabled = SettingsManager.getSuggestionStarterWordsEnabled(context)
            autoSpaceAfterSuggestion = SettingsManager.getAutoSpaceAfterSuggestion(context)
            accentMatchingEnabled = SettingsManager.getAccentMatchingEnabled(context)
            autoReplaceOnSpaceEnter = SettingsManager.getAutoReplaceOnSpaceEnter(context)
            maxAutoReplaceDistance = SettingsManager.getMaxAutoReplaceDistance(context)
            useKeyboardProximity = SettingsManager.getUseKeyboardProximity(context)
            useEditTypeRanking = SettingsManager.getUseEditTypeRanking(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val currentDestination = when (val page = settingsChild(context, "autocorrection")) {
        "Settings" -> AutoCorrectionDestination.Settings
        "UserDictionary" -> AutoCorrectionDestination.UserDictionary
        "AppExactTyping" -> AutoCorrectionDestination.AppExactTyping
        else -> if (page?.startsWith("Edit:") == true) AutoCorrectionDestination.Edit(page.removePrefix("Edit:")) else AutoCorrectionDestination.Main
    }
    fun navigateTo(destination: AutoCorrectionDestination) {
        openSettingsChild(context, "autocorrection", when (destination) { AutoCorrectionDestination.Main -> "Main"; AutoCorrectionDestination.Settings -> "Settings"; AutoCorrectionDestination.UserDictionary -> "UserDictionary"; AutoCorrectionDestination.AppExactTyping -> "AppExactTyping"; is AutoCorrectionDestination.Edit -> "Edit:${destination.languageCode}" })
    }
    // A child page is its own Activity; the page root leaves through its caller, which may be a pane.
    fun navigateBack() {
        if (settingsChild(context, "autocorrection") != null) context.settingsActivity().finish() else onBack()
    }


    val destination = currentDestination
        when (destination) {
            AutoCorrectionDestination.Main -> {
                Scaffold(
                    topBar = {
            PlektraSettingsHeader(title = stringResource(R.string.settings_category_auto_correction), onBack = { navigateBack() })
        }
                ) { paddingValues ->
                    SettingsSectionScrollColumn(
                        modifier = modifier
                            .fillMaxWidth()
                            .padding(paddingValues)
                    ) {
                        // Text replacements: explicit user/default rules like "ca -> ça".
                        SettingsToggleRow(
                            title = stringResource(R.string.auto_correct_title),
                            description = stringResource(R.string.auto_correct_title_description),
                            icon = Icons.Filled.TextFields,
                            checked = autoCorrectEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_TEXT_REPLACEMENTS,
                            onCheckedChange = { enabled ->
                                autoCorrectEnabled = enabled
                                SettingsManager.setAutoCorrectEnabled(context, enabled)
                            }
                        )

                        // Text replacement language/rule sets (only if text replacements are enabled)
                        if (autoCorrectEnabled) {
                            SettingsNavigationRow(
                                title = stringResource(R.string.auto_correct_languages_title),
                                description = stringResource(R.string.auto_correct_languages_description),
                                icon = Icons.Filled.Language,
                                linkId = SettingLinkIds.AUTO_CORRECTION_LANGUAGES,
                                onClick = {
                                    navigateTo(AutoCorrectionDestination.Settings)
                                }
                            )
                        }

                        // Automatic correction: dictionary/suggestion based guessing on boundaries.
                        SettingsToggleRow(
                            title = stringResource(R.string.auto_correct_auto_replace_title),
                            description = stringResource(R.string.auto_correct_auto_replace_description),
                            icon = Icons.Filled.TextFields,
                            checked = autoReplaceOnSpaceEnter,
                            enabled = experimentalSuggestionsEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_AUTO_REPLACE,
                            onCheckedChange = { enabled ->
                                autoReplaceOnSpaceEnter = enabled
                                SettingsManager.setAutoReplaceOnSpaceEnter(context, enabled)
                            }
                        )

                        // Max auto-replace distance slider (only shown when automatic correction is enabled)
                        if (autoReplaceOnSpaceEnter) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .settingRow(SettingLinkIds.AUTO_CORRECTION_MAX_DISTANCE)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.auto_correct_max_distance_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = if (maxAutoReplaceDistance == 0) {
                                                stringResource(R.string.auto_correct_max_distance_off)
                                            } else {
                                                maxAutoReplaceDistance.toString()
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Slider(
                                        value = maxAutoReplaceDistance.toFloat(),
                                        onValueChange = { value ->
                                            val newValue = value.toInt().coerceIn(0, 3)
                                            maxAutoReplaceDistance = newValue
                                            SettingsManager.setMaxAutoReplaceDistance(context, newValue)
                                        },
                                        valueRange = 0f..3f,
                                        steps = 2,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = stringResource(R.string.auto_correct_max_distance_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        SettingsNavigationRow(
                            title = stringResource(R.string.auto_correct_manage_user_dict_title),
                            description = stringResource(R.string.auto_correct_manage_user_dict_description),
                            icon = Icons.Filled.Add,
                            linkId = SettingLinkIds.AUTO_CORRECTION_USER_DICTIONARY,
                            onClick = {
                                navigateTo(AutoCorrectionDestination.UserDictionary)
                            }
                        )

                        // Exact Typing page: choose the apps where smart typing is off
                        SettingsNavigationRow(
                            title = stringResource(R.string.app_raw_mode_title),
                            description = stringResource(R.string.app_raw_mode_row_description),
                            icon = Icons.Filled.Apps,
                            linkId = SettingLinkIds.AUTO_CORRECTION_EXACT_TYPING,
                            onClick = {
                                navigateTo(AutoCorrectionDestination.AppExactTyping)
                            }
                        )

                        SettingsSectionDivider(stringResource(R.string.auto_correct_section_suggestions))
                        // Advanced suggestions master toggle
                        Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp)
                                    .settingRow(SettingLinkIds.AUTO_CORRECTION_EXPERIMENTAL_SUGGESTIONS)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Code,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.experimental_suggestions_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = stringResource(R.string.experimental_suggestions_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FeatureStatusIcon(FeatureStatus.Experimental)
                                    Switch(
                                        checked = experimentalSuggestionsEnabled,
                                        onCheckedChange = { enabled ->
                                            experimentalSuggestionsEnabled = enabled
                                            SettingsManager.setExperimentalSuggestionsEnabled(context, enabled)
                                            if (enabled && !suggestionsEnabled) {
                                                suggestionsEnabled = true
                                                SettingsManager.setSuggestionsEnabled(context, true)
                                            }
                                        }
                                    )
                                }
                            }

                        AutoCorrectionSwitchRow(
                            icon = Icons.Filled.Lightbulb,
                            title = stringResource(R.string.auto_correct_starter_words_title),
                            description = stringResource(R.string.auto_correct_starter_words_description),
                            checked = starterWordsEnabled,
                            enabled = experimentalSuggestionsEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_STARTER_WORDS,
                            onCheckedChange = { enabled ->
                                starterWordsEnabled = enabled
                                SettingsManager.setSuggestionStarterWordsEnabled(context, enabled)
                            }
                        )

                        AutoCorrectionSwitchRow(
                            icon = Icons.Filled.History,
                            title = stringResource(R.string.auto_correct_suggestions_personalization_title),
                            description = stringResource(R.string.auto_correct_suggestions_personalization_description),
                            checked = suggestionsPersonalizationEnabled,
                            enabled = experimentalSuggestionsEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_SUGGESTIONS_PERSONALIZATION,
                            onCheckedChange = { enabled ->
                                suggestionsPersonalizationEnabled = enabled
                                SettingsManager.setSuggestionsPersonalizationEnabled(context, enabled)
                            }
                        )

                        AutoCorrectionSwitchRow(
                            icon = Icons.Filled.AddCircleOutline,
                            title = stringResource(R.string.auto_correct_add_word_button_title),
                            description = stringResource(R.string.auto_correct_add_word_button_description),
                            checked = suggestionsAddWordButtonEnabled,
                            enabled = experimentalSuggestionsEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_ADD_WORD_BUTTON,
                            onCheckedChange = { enabled ->
                                suggestionsAddWordButtonEnabled = enabled
                                SettingsManager.setSuggestionsAddWordButtonEnabled(context, enabled)
                            }
                        )

                        AutoCorrectionSwitchRow(
                            icon = Icons.Filled.TextFields,
                            title = stringResource(R.string.auto_correct_accent_matching_title),
                            checked = accentMatchingEnabled,
                            enabled = experimentalSuggestionsEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_ACCENT_MATCHING,
                            onCheckedChange = { enabled ->
                                accentMatchingEnabled = enabled
                                SettingsManager.setAccentMatchingEnabled(context, enabled)
                            }
                        )
                        AutoCorrectionSwitchRow(
                            icon = Icons.Filled.TextFields,
                            title = stringResource(R.string.auto_correct_keyboard_proximity_title),
                            description = stringResource(R.string.auto_correct_keyboard_proximity_description),
                            checked = useKeyboardProximity,
                            enabled = experimentalSuggestionsEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_KEYBOARD_PROXIMITY,
                            onCheckedChange = { enabled ->
                                useKeyboardProximity = enabled
                                SettingsManager.setUseKeyboardProximity(context, enabled)
                            }
                        )
                        AutoCorrectionSwitchRow(
                            icon = Icons.Filled.TextFields,
                            title = stringResource(R.string.auto_correct_edit_type_ranking_title),
                            description = stringResource(R.string.auto_correct_edit_type_ranking_description),
                            checked = useEditTypeRanking,
                            enabled = experimentalSuggestionsEnabled,
                            linkId = SettingLinkIds.AUTO_CORRECTION_EDIT_TYPE_RANKING,
                            onCheckedChange = { enabled ->
                                useEditTypeRanking = enabled
                                SettingsManager.setUseEditTypeRanking(context, enabled)
                            }
                        )

                        // The same stored settings as on their home pages; listed here so they are found with suggestions.
                        AutoCorrectionSwitchRow(
                            icon = Icons.Filled.SpaceBar,
                            title = stringResource(R.string.auto_space_after_suggestion_title),
                            description = stringResource(R.string.auto_space_after_suggestion_description),
                            checked = autoSpaceAfterSuggestion,
                            enabled = true,
                            linkId = null,
                            onCheckedChange = { enabled ->
                                autoSpaceAfterSuggestion = enabled
                                SettingsManager.setAutoSpaceAfterSuggestion(context, enabled)
                            }
                        )
                        AutoCorrectionLinkRow(
                            icon = Icons.Filled.ViewWeek,
                            title = stringResource(R.string.status_bar_buttons_title),
                            description = stringResource(R.string.auto_correct_keyboard_bar_link_description),
                            linkId = SettingLinkIds.AUTO_CORRECTION_SUGGESTIONS,
                            onClick = {
                                context.startActivity(android.content.Intent(context, SettingsActivity::class.java).apply {
                                    putExtra(SettingsActivity.EXTRA_DESTINATION, SettingsActivity.DESTINATION_CUSTOMIZATION)
                                    putExtra(SettingsActivity.EXTRA_CUSTOMIZATION_DESTINATION,
                                        SettingsActivity.CUSTOMIZATION_DESTINATION_STATUS_BAR_BUTTONS)
                                })
                            }
                        )
                    }
                }
            }

            AutoCorrectionDestination.Settings -> {
                AutoCorrectSettingsScreen(
                    modifier = modifier,
                    onBack = { navigateBack() },
                    onEditLanguage = { languageCode ->
                        navigateTo(AutoCorrectionDestination.Edit(languageCode))
                    }
                )
            }

            is AutoCorrectionDestination.Edit -> {
                AutoCorrectEditScreen(
                    languageCode = destination.languageCode,
                    modifier = modifier,
                    onBack = { navigateBack() }
                )
            }

            AutoCorrectionDestination.UserDictionary -> {
                UserDictionaryScreen(
                    modifier = modifier,
                    onBack = { navigateBack() }
                )
            }

            AutoCorrectionDestination.AppExactTyping -> {
                AppRawModeScreen(
                    modifier = modifier,
                    onBack = { navigateBack() }
                )
            }
        }

}

private sealed class AutoCorrectionDestination {
    object Main : AutoCorrectionDestination()
    object Settings : AutoCorrectionDestination()
    data class Edit(val languageCode: String) : AutoCorrectionDestination()
    object UserDictionary : AutoCorrectionDestination()
    object AppExactTyping : AutoCorrectionDestination()
}

@Composable
private fun AutoCorrectionSwitchRow(
    icon: ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean,
    linkId: String?,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsToggleRow(title = title, description = description, checked = checked, icon = icon,
        onCheckedChange = onCheckedChange, enabled = enabled, linkId = linkId)
}

@Composable
private fun AutoCorrectionLinkRow(
    icon: ImageVector,
    title: String,
    description: String,
    linkId: String,
    onClick: () -> Unit
) {
    SettingsNavigationRow(title = title, description = description, icon = icon, linkId = linkId, onClick = onClick)
}

private enum class UserDictSource { DEFAULT, USER }

private data class UserDictItem(val word: String, val source: UserDictSource)

@Composable
private fun UserDictionaryScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val defaultStore = remember(context) { DefaultUserDefaultsStore(context) }
    val userStore = remember { rocks.pkb.plektra.core.suggestions.UserDictionaryStore() }
    var entries by remember { mutableStateOf(emptyList<UserDictItem>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<UserDictItem?>(null) }
    var dialogWord by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            val query = searchQuery.lowercase()
            entries.filter { it.word.lowercase().contains(query) }
        }
    }
    val hasEntries = entries.isNotEmpty()

    fun refreshEntries() {
        // Ensure cache is populated from persistent storage before snapshots/removals.
        userStore.loadUserEntries(context)
        val defaults = defaultStore.loadEntries().map { UserDictItem(it.word, UserDictSource.DEFAULT) }
        val users = userStore.getSnapshot().map { it.word }.map { UserDictItem(it, UserDictSource.USER) }
        entries = (defaults + users).sortedBy { it.word.lowercase() }
    }

    LaunchedEffect(Unit) {
        refreshEntries()
    }

    fun notifyDictionaryUpdated() {
        val intent = Intent(AppBroadcastActions.USER_DICTIONARY_UPDATED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    fun addWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) {
            userStore.addWord(context, trimmed)
            refreshEntries()
            notifyDictionaryUpdated()
        }
    }

    fun updateWord(entry: UserDictItem, newWord: String) {
        val trimmed = newWord.trim()
        if (trimmed.isNotEmpty() && trimmed != entry.word) {
            when (entry.source) {
                UserDictSource.DEFAULT -> defaultStore.update(entry.word, trimmed)
                UserDictSource.USER -> userStore.updateWord(context, entry.word, trimmed)
            }
            refreshEntries()
            notifyDictionaryUpdated()
        }
    }

    Scaffold(
        topBar = {
            PlektraSettingsHeader(
                title = stringResource(R.string.user_dict_title),
                onBack = onBack,
                bottom = {
        if (searchActive) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                            modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        placeholder = {
            Text(stringResource(R.string.auto_correct_search_placeholder))
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.auto_correct_search_description)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.auto_correct_clear_search)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            ) {
                IconButton(
                    onClick = {
                        if (searchActive) {
                            searchActive = false
                            searchQuery = ""
                        } else {
                            searchActive = true
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = stringResource(
                            if (searchActive) R.string.auto_correct_clear_search
                            else R.string.auto_correct_search_description
                        )
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.user_dict_add_button)
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (filteredEntries.isEmpty()) {
                item {
                    Text(
                        text = if (hasEntries) {
                            stringResource(R.string.auto_correct_no_corrections_found)
                        } else {
                            stringResource(R.string.user_dict_empty_state)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(filteredEntries) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = entry.word,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                editingEntry = entry
                                dialogWord = entry.word
                            }) {
                                Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                            }
                            IconButton(onClick = {
                                when (entry.source) {
                                    UserDictSource.DEFAULT -> defaultStore.remove(entry.word)
                                    UserDictSource.USER -> userStore.removeWord(context, entry.word)
                                }
                                refreshEntries()
                                notifyDictionaryUpdated()
                            }) {
                                Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }

    // Add word dialog
    if (showAddDialog) {
        UserDictWordDialog(
            title = stringResource(R.string.user_dict_add_hint),
            word = dialogWord,
            onWordChange = { dialogWord = it },
            onConfirm = {
                addWord(dialogWord)
                showAddDialog = false
                dialogWord = ""
            },
            onDismiss = {
                showAddDialog = false
                dialogWord = ""
            }
        )
    }

    // Edit word dialog
    editingEntry?.let { entry ->
        UserDictWordDialog(
            title = stringResource(R.string.user_dict_edit_title),
            word = dialogWord,
            onWordChange = { dialogWord = it },
            onConfirm = {
                updateWord(entry, dialogWord)
                editingEntry = null
                dialogWord = ""
            },
            onDismiss = {
                editingEntry = null
                dialogWord = ""
            }
        )
    }
}

@Composable
private fun UserDictWordDialog(
    title: String,
    word: String,
    onWordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = word,
                keyboardOptions = LiteralEntryKeyboardOptions,
                onValueChange = onWordChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
                trailingIcon = {
                    if (word.isNotBlank()) {
                        IconButton(onClick = { onWordChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = word.isNotBlank()
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}



private data class DefaultUserWord(val word: String, val frequency: Int)

/**
 * Handles editable default user dictionary stored in app-private storage.
 * Uses JSON format aligned with assets/common/dictionaries/user_defaults.json.
 */
private class DefaultUserDefaultsStore(private val context: Context) {
    private val fileName = "user_defaults.json"
    private val assetPath = "common/dictionaries/$fileName"

    private fun ensureLocalFile(): java.io.File {
        val file = context.getFileStreamPath(fileName)
        if (!file.exists()) {
            try {
                context.assets.open(assetPath).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // Asset missing; create empty file
                file.parentFile?.mkdirs()
                file.writeText("[]")
            }
        }
        return file
    }

    fun loadEntries(): List<DefaultUserWord> {
        val file = ensureLocalFile()
        return try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            buildList {
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val word = obj.getString("w")
                    val freq = obj.optInt("f", 1)
                    add(DefaultUserWord(word, freq))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addOrBump(word: String, baseFrequency: Int = 10) {
        val file = ensureLocalFile()
        val entries = loadEntries().toMutableList()
        val existingIndex = entries.indexOfFirst { it.word.equals(word, ignoreCase = true) }
        if (existingIndex >= 0) {
            val existing = entries[existingIndex]
            entries[existingIndex] = existing.copy(frequency = existing.frequency + 1)
        } else {
            entries.add(DefaultUserWord(word, baseFrequency))
        }
        persist(entries, file)
    }

    fun remove(word: String) {
        val file = ensureLocalFile()
        val entries = loadEntries().filterNot { it.word.equals(word, ignoreCase = true) }
        persist(entries, file)
    }

    fun update(oldWord: String, newWord: String) {
        val file = ensureLocalFile()
        val entries = loadEntries().map {
            if (it.word.equals(oldWord, ignoreCase = true)) it.copy(word = newWord) else it
        }
        persist(entries, file)
    }

    private fun persist(entries: List<DefaultUserWord>, file: java.io.File) {
        try {
            val array = JSONArray()
            entries.forEach { entry ->
                val obj = JSONObject()
                obj.put("w", entry.word)
                obj.put("f", entry.frequency)
                array.put(obj)
            }
            file.writeText(array.toString())
        } catch (_: Exception) {
            // Ignore persistence errors; UI will just not reflect changes
        }
    }
}
