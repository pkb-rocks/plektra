package rocks.pkb.plektra

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.pkb.plektra.data.emoji.EmojiRepository
import rocks.pkb.plektra.data.emoji.EmojiSearchRepository
import rocks.pkb.plektra.data.symbols.PickerExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dialog for selecting an emoji.
 * Loads emoji categories from assets (common/emoji), followed by the text symbols and kaomoji
 * the keyboard's emoji picker offers (when turned on there).
 */
@Composable
fun EmojiPickerDialog(
    selectedLetter: String? = null,
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf(EmojiPickerState()) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        uiState = EmojiPickerState(isLoading = true, error = null)
        runCatching {
            val categories = EmojiRepository.getEmojiCategories(context)
            val searchIndex = EmojiSearchRepository.getSearchIndex(context)
            val extras = withContext(Dispatchers.IO) { PickerExtras.load(context) }
            Triple(categories + extras.categories, searchIndex, extras)
        }
            .onSuccess { (categories, searchIndex, extras) ->
                selectedCategoryId = categories.firstOrNull()?.id
                uiState = EmojiPickerState(
                    isLoading = false,
                    categories = categories,
                    searchIndex = searchIndex,
                    extras = extras,
                    error = null
                )
            }
            .onFailure { throwable ->
                uiState = EmojiPickerState(
                    isLoading = false,
                    categories = emptyList(),
                    error = throwable.message ?: context.getString(R.string.emoji_picker_error)
                )
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedLetter != null) {
                            stringResource(R.string.emoji_picker_title_for_letter, selectedLetter)
                        } else {
                            stringResource(R.string.emoji_picker_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.emoji_picker_close), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    label = { Text(stringResource(R.string.emoji_picker_search_label)) },
                    placeholder = { Text(stringResource(R.string.emoji_picker_search_placeholder)) },
                    singleLine = true
                )

                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    uiState.error != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = uiState.error ?: stringResource(id = R.string.emoji_picker_error))
                        }
                    }

                    uiState.categories.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = stringResource(id = R.string.emoji_picker_error))
                        }
                    }

                    else -> {
                        val categories = uiState.categories
                        val trimmedQuery = searchQuery.trim()
                        val isSearching = trimmedQuery.isNotEmpty()
                        val searchResults = if (isSearching) {
                            uiState.searchIndex?.let { index ->
                                EmojiSearchRepository.search(index, trimmedQuery).map { result ->
                                    // The emoji searched for as it was given (👋🏽), its other variants on hold
                                    result.matched?.let { matched ->
                                        EmojiRepository.EmojiEntry(matched, listOf(result.entry.base) + result.entry.variants - matched)
                                    } ?: result.entry
                                }
                            }.orEmpty() + uiState.extras.search(context, trimmedQuery).map { it.entry }
                        } else {
                            emptyList()
                        }

                        if (!isSearching) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                categories.forEach { category ->
                                    val label = category.displayNameRes?.let { stringResource(id = it) } ?: category.id
                                    FilterChip(
                                        selected = selectedCategoryId == category.id,
                                        onClick = { selectedCategoryId = category.id },
                                        label = {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        },
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        if (isSearching && searchResults.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.emoji_picker_no_results))
                            }
                            return@Column
                        }

                        val entriesToShow = if (isSearching) {
                            searchResults
                        } else {
                            val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId } ?: categories.first()
                            selectedCategory.emojis
                        }
                        // Kaomoji are wide: shrunk to fit, and fewer to a row on their own tab
                        val kaomojiShown = !isSearching && selectedCategoryId == EmojiRepository.KAOMOJI_CATEGORY_ID
                        val kaomoji = remember(categories) {
                            categories.firstOrNull { it.id == EmojiRepository.KAOMOJI_CATEGORY_ID }
                                ?.emojis?.mapTo(HashSet()) { it.base }.orEmpty()
                        }

                        val contentKey = if (isSearching) {
                            "search:$trimmedQuery:${entriesToShow.size}"
                        } else {
                            "category:${selectedCategoryId ?: "none"}"
                        }

                        key(contentKey) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clipToBounds()
                            ) {
                                AndroidView(
                                    factory = { context ->
                                        val recyclerView = RecyclerView(context)
                                        val screenWidth = context.resources.displayMetrics.widthPixels
                                        val emojiSize = (48 * context.resources.displayMetrics.density).toInt()
                                        val spacing = (2 * context.resources.displayMetrics.density).toInt()
                                        val padding = (4 * context.resources.displayMetrics.density).toInt()

                                        val columns = (screenWidth / (emojiSize + spacing)).coerceAtLeast(4)
                                            .let { if (kaomojiShown) (it / 3).coerceAtLeast(2) else it }

                                        recyclerView.apply {
                                            layoutManager = GridLayoutManager(context, columns)
                                            adapter = EmojiEntryRecyclerViewAdapter(entriesToShow, textLike = { it in kaomoji }) { emoji ->
                                                onEmojiSelected(emoji)
                                                onDismiss()
                                            }
                                            setPadding(padding, padding, padding, padding)
                                            clipToPadding = false
                                            setHasFixedSize(true)
                                            setItemViewCacheSize(20)
                                        }
                                        recyclerView
                                    },
                                    update = { recyclerView ->
                                        recyclerView.adapter = EmojiEntryRecyclerViewAdapter(entriesToShow, textLike = { it in kaomoji }) { emoji ->
                                            onEmojiSelected(emoji)
                                            onDismiss()
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class EmojiPickerState(
    val isLoading: Boolean = true,
    val categories: List<EmojiRepository.EmojiCategory> = emptyList(),
    val searchIndex: EmojiSearchRepository.EmojiSearchIndex? = null,
    val extras: PickerExtras = PickerExtras.NONE,
    val error: String? = null
)





