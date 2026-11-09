package rocks.pkb.plektra.data.emoji

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads emoji categories from asset text files.
 * Files live under assets/common/emoji (one emoji per line; first token is the base, the rest are variants).
 * Applies minApi filtering based on minApi.txt where each line starts with the API level followed by emojis.
 *
 * UI-agnostic: can be reused for the picker dialog and future emoji keyboard.
 */
object EmojiRepository {
    private const val EMOJI_ASSET_DIR = "common/emoji"
    const val RECENTS_CATEGORY_ID = "RECENTS"

    const val SYMBOLS_CATEGORY_ID = "UNICODE_SYMBOLS"
    const val KAOMOJI_CATEGORY_ID = "KAOMOJI"

    data class EmojiEntry(val base: String, val variants: List<String>)
    data class EmojiCategory(
        val id: String,
        val displayNameRes: Int?,
        val emojis: List<EmojiEntry>
    )

    @Volatile
    private var cachedCategories: List<EmojiCategory>? = null

    /** The categories when already loaded, without loading them. */
    fun cachedCategoriesOrNull(): List<EmojiCategory>? = cachedCategories

    suspend fun getEmojiCategories(context: Context): List<EmojiCategory> {
        return cachedCategories ?: loadEmojiCategories(context).also { cachedCategories = it }
    }

    fun clearCache() {
        cachedCategories = null
        entryIndex = null
    }

    @Volatile
    private var entryIndex: Pair<List<EmojiCategory>, Map<String, EmojiEntry>>? = null

    /** The entry [emoji] belongs to, as its base or one of its variants; null if not loaded. */
    fun findEntry(emoji: String): EmojiEntry? {
        val categories = cachedCategories ?: return null
        val index = entryIndex?.takeIf { it.first === categories }?.second ?: HashMap<String, EmojiEntry>().also { map ->
            categories.forEach { category ->
                category.emojis.forEach { entry ->
                    map.putIfAbsent(entry.base, entry)
                    entry.variants.forEach { map.putIfAbsent(it, entry) }
                }
            }
            entryIndex = categories to map
        }
        return index[emoji]
    }

    /**
     * Looks up variants for an emoji from the cached categories.
     * Returns empty list if not found or cache not loaded.
     */
    fun getVariantsForEmoji(emoji: String): List<String> {
        val categories = cachedCategories ?: return emptyList()
        for (category in categories) {
            for (entry in category.emojis) {
                if (entry.base == emoji) {
                    return entry.variants
                }
                // Also check if the emoji is a variant
                if (entry.variants.contains(emoji)) {
                    // Return other variants plus the base
                    return (listOf(entry.base) + entry.variants).filter { it != emoji }
                }
            }
        }
        return emptyList()
    }

    /**
     * Utility for future keyboard pagination/chunking without re-parsing assets.
     */
    fun asPaged(
        categories: List<EmojiCategory>,
        pageSize: Int = 50
    ): Map<String, List<List<EmojiEntry>>> {
        return categories.associate { category ->
            category.id to category.emojis.chunked(pageSize)
        }
    }

    private suspend fun loadEmojiCategories(context: Context): List<EmojiCategory> = withContext(Dispatchers.IO) {
        val assetManager = context.assets
        val files = assetManager.list(EMOJI_ASSET_DIR)
            ?.filter { it.endsWith(".txt") && it != "minApi.txt" }
            .orEmpty()

        // Define custom category order
        val categoryOrder = listOf(
            "SMILEYS_AND_EMOTION.txt",
            "PEOPLE_AND_BODY.txt",
            "ANIMALS_AND_NATURE.txt",
            "FOOD_AND_DRINK.txt",
            "TRAVEL_AND_PLACES.txt",
            "ACTIVITIES.txt",
            "OBJECTS.txt",
            "SYMBOLS.txt",
            "FLAGS.txt"
        )

        val sortedFiles = files.sortedBy { fileName ->
            val index = categoryOrder.indexOf(fileName)
            if (index >= 0) index else Int.MAX_VALUE // Unknown files go to the end
        }

        // With the bundled font, offer exactly what it draws; otherwise what the system font draws.
        val availability = if (EmojiRendering.awaitReady(context)) {
            EmojiAvailability.bundledFont { emoji -> EmojiRendering.bundledFontDraws(context, emoji) == true }
        } else {
            EmojiAvailability.fromAssets(assetManager)
        }

        sortedFiles.mapNotNull { fileName ->
            val emojis = parseEmojiFile(context, fileName, availability)
            if (emojis.isEmpty()) return@mapNotNull null
            EmojiCategory(
                id = fileName.substringBefore(".txt"),
                displayNameRes = mapCategoryRes(fileName),
                emojis = emojis
            )
        }
    }

    private fun parseEmojiFile(
        context: Context,
        fileName: String,
        availability: EmojiAvailability
    ): List<EmojiEntry> {
        val assetPath = "$EMOJI_ASSET_DIR/$fileName"

        return runCatching {
            context.assets.open(assetPath).use { input ->
                BufferedReader(InputStreamReader(input)).lineSequence().mapNotNull { line ->
                    val tokens = line.split(" ").filter { it.isNotBlank() }
                    if (tokens.isEmpty()) return@mapNotNull null

                    val base = tokens.first()
                    val variants = tokens.drop(1)

                    val allowedBase = availability.isAvailable(base)
                    val allowedVariants = variants.filter(availability::isAvailable)

                    if (!allowedBase) {
                        null
                    } else {
                        EmojiEntry(base = base, variants = allowedVariants)
                    }
                }.toList()
            }
        }.getOrElse { emptyList() }
    }

    fun categoryNameRes(categoryId: String): Int? = mapCategoryRes(categoryId)

    private fun mapCategoryRes(fileName: String): Int? {
        return when (fileName.substringBefore(".txt")) {
            "SMILEYS_AND_EMOTION" -> rocks.pkb.plektra.R.string.emoji_category_smileys_and_emotion
            "PEOPLE_AND_BODY" -> rocks.pkb.plektra.R.string.emoji_category_people_and_body
            "ANIMALS_AND_NATURE" -> rocks.pkb.plektra.R.string.emoji_category_animals_and_nature
            "FOOD_AND_DRINK" -> rocks.pkb.plektra.R.string.emoji_category_food_and_drink
            "TRAVEL_AND_PLACES" -> rocks.pkb.plektra.R.string.emoji_category_travel_and_places
            "ACTIVITIES" -> rocks.pkb.plektra.R.string.emoji_category_activities
            "OBJECTS" -> rocks.pkb.plektra.R.string.emoji_category_objects
            "SYMBOLS" -> rocks.pkb.plektra.R.string.emoji_category_symbols
            "FLAGS" -> rocks.pkb.plektra.R.string.emoji_category_flags
            SYMBOLS_CATEGORY_ID -> rocks.pkb.plektra.R.string.emoji_category_unicode_symbols
            KAOMOJI_CATEGORY_ID -> rocks.pkb.plektra.R.string.emoji_category_kaomoji
            else -> null
        }
    }

    /**
     * Maps category ID to a Material icon drawable resource ID for tab display.
     */
    fun getCategoryIconRes(categoryId: String): Int {
        return when (categoryId) {
            RECENTS_CATEGORY_ID -> rocks.pkb.plektra.R.drawable.ic_schedule_24
            "SMILEYS_AND_EMOTION" -> rocks.pkb.plektra.R.drawable.ic_sentiment_satisfied_24
            "PEOPLE_AND_BODY" -> rocks.pkb.plektra.R.drawable.ic_emoji_people_24
            "ANIMALS_AND_NATURE" -> rocks.pkb.plektra.R.drawable.ic_pets_24
            "FOOD_AND_DRINK" -> rocks.pkb.plektra.R.drawable.ic_restaurant_24
            "TRAVEL_AND_PLACES" -> rocks.pkb.plektra.R.drawable.ic_flight_24
            "ACTIVITIES" -> rocks.pkb.plektra.R.drawable.ic_sports_soccer_24
            "OBJECTS" -> rocks.pkb.plektra.R.drawable.ic_lightbulb_24
            "SYMBOLS" -> rocks.pkb.plektra.R.drawable.ic_emoji_symbols_24
            "FLAGS" -> rocks.pkb.plektra.R.drawable.ic_flag_24
            SYMBOLS_CATEGORY_ID -> rocks.pkb.plektra.R.drawable.ic_unicode_symbols_24
            KAOMOJI_CATEGORY_ID -> rocks.pkb.plektra.R.drawable.ic_kaomoji_24
            else -> rocks.pkb.plektra.R.drawable.ic_schedule_24
        }
    }
}
