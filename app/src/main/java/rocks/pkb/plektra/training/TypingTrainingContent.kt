package rocks.pkb.plektra.training

import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.random.Random

/** One piece of practice text; chat items of the other speaker are shown as context only. */
@Serializable
data class TrainingContentItem(
    val id: String,
    val text: String,
    val speaker: String? = null,
    /** False for the other chat partner's messages, which are not typed. */
    val own: Boolean = true,
    val source: String? = null,
    val kind: String? = null
)

/** What a session asks the participant to type. */
data class TrainingPlan(
    val contentType: String,
    val items: List<TrainingContentItem>,
    /** Whitespace-separated target tokens, in typing order. */
    val targets: List<String>,
    /** Index into [items] for each target token. */
    val itemOfTarget: List<Int>,
    val contentSha256: String
)

object TypingTrainingContent {
    const val WORDS = "words"
    const val PHRASES = "phrases"
    const val CHAT = "chat"
    const val TOKENS = "tokens"

    @Serializable
    private data class Turn(val speaker: String, val text: String)

    @Serializable
    private data class Chat(val id: String, val source: String, val topic: String, val turns: List<Turn>)

    @Serializable
    private data class ChatFile(val chats: List<Chat>)

    @Serializable
    private data class Item(val id: String, val source: String, val kind: String, val text: String)

    @Serializable
    private data class ItemFile(val items: List<Item>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Every language with a dictionary has word lists; see scripts/generate-typing-training-wordlists.py. */
    fun languages(assets: AssetManager): List<String> =
        assets.list("typing-training").orEmpty().mapNotNull { it.removeSuffix("-1k.txt").takeIf { _ -> it.endsWith("-1k.txt") } }.sorted()

    /** Content types with practice material in [language]; chats and phrases exist for fewer languages. */
    fun contentTypes(assets: AssetManager, language: String): List<String> {
        val files = assets.list("typing-training/content").orEmpty().toSet()
        return listOfNotNull(
            WORDS,
            PHRASES.takeIf { "phrases-$language.json" in files },
            CHAT.takeIf { "chats-$language.json" in files },
            TOKENS
        )
    }

    fun plan(
        assets: AssetManager,
        contentType: String,
        language: String,
        wordList: String,
        speaker: String,
        seed: Long,
        maxTargets: Int
    ): TrainingPlan {
        val random = Random(seed)
        return when (contentType) {
            WORDS -> {
                val bytes = assets.open("typing-training/$language-$wordList.txt").use { it.readBytes() }
                val words = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
                val targets = List(maxTargets) { words[random.nextInt(words.size)] }
                TrainingPlan(WORDS, emptyList(), targets, List(targets.size) { -1 }, sha256(bytes))
            }
            CHAT -> {
                val bytes = assets.open("typing-training/content/chats-$language.json").use { it.readBytes() }
                val chat = json.decodeFromString(ChatFile.serializer(), bytes.toString(Charsets.UTF_8)).chats.let { it[random.nextInt(it.size)] }
                val items = chat.turns.mapIndexed { index, turn ->
                    TrainingContentItem(
                        id = "${chat.id}#$index",
                        text = turn.text,
                        speaker = turn.speaker,
                        own = turn.speaker == speaker,
                        source = chat.source,
                        kind = chat.topic
                    )
                }
                fromItems(CHAT, items, maxTargets = Int.MAX_VALUE, sha256(bytes))
            }
            else -> {
                val file = if (contentType == TOKENS) "typing-training/content/tokens.json" else "typing-training/content/phrases-$language.json"
                val bytes = assets.open(file).use { it.readBytes() }
                val items = json.decodeFromString(ItemFile.serializer(), bytes.toString(Charsets.UTF_8)).items
                    .shuffled(random)
                    .map { TrainingContentItem(id = it.id, text = it.text, source = it.source, kind = it.kind) }
                fromItems(contentType, items, maxTargets, sha256(bytes))
            }
        }
    }

    /** Takes whole items until [maxTargets] tokens are reached; items are never cut in half. */
    private fun fromItems(contentType: String, all: List<TrainingContentItem>, maxTargets: Int, sha: String): TrainingPlan {
        val items = mutableListOf<TrainingContentItem>()
        val targets = mutableListOf<String>()
        val itemOfTarget = mutableListOf<Int>()
        for (item in all) {
            if (targets.size >= maxTargets) break
            items += item
            if (!item.own) continue
            item.text.split(' ').filter { it.isNotEmpty() }.forEach {
                targets += it
                itemOfTarget += items.lastIndex
            }
        }
        return TrainingPlan(contentType, items, targets, itemOfTarget, sha)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
