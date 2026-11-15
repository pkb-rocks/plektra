package rocks.pkb.plektra.documents

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class DocumentRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: String? = null,
    val fontSize: Int? = null,
)

internal data class DocumentParagraph(
    val runs: List<DocumentRun>,
    val justified: Boolean = false,
    val alignment: String = if (justified) "justify" else "left",
    val indent: Int = 0,
    val heading: Int = 0,
    val list: String = "none",
)

internal data class LocalDocument(
    val id: String,
    val title: String,
    val paragraphs: List<DocumentParagraph>,
    val backgroundColor: String? = null,
    val wallpaper: DocumentWallpaper? = null,
) {
    fun isEmptyDraft() =
        wallpaper == null &&
            title.isBlank() &&
            paragraphs.all { p -> p.runs.all { it.text.isBlank() } }

    fun json() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("version", 1)
            .put("backgroundColor", backgroundColor ?: JSONObject.NULL)
            .put("wallpaper", wallpaper?.json() ?: JSONObject.NULL)
            .put(
                "paragraphs",
                JSONArray().also { list ->
                    paragraphs.forEach { paragraph ->
                        list.put(
                            JSONObject()
                                .put("justified", paragraph.justified)
                                .put("alignment", paragraph.alignment)
                                .put("indent", paragraph.indent)
                                .put("heading", paragraph.heading)
                                .put("list", paragraph.list)
                                .put(
                                    "runs",
                                    JSONArray().also { runs ->
                                        paragraph.runs.forEach {
                                            runs.put(
                                                JSONObject()
                                                    .put("text", it.text)
                                                    .put("bold", it.bold)
                                                    .put("italic", it.italic)
                                                    .put("underline", it.underline)
                                                    .put("color", it.color ?: JSONObject.NULL)
                                                    .put("fontSize", it.fontSize ?: JSONObject.NULL)
                                            )
                                        }
                                    },
                                )
                        )
                    }
                },
            )

    companion object {
        fun blank() =
            LocalDocument(UUID.randomUUID().toString(), "", listOf(DocumentParagraph(emptyList())))

        fun parse(value: JSONObject): LocalDocument {
            require(value.getInt("version") == 1) { "Unsupported document version" }
            val id = value.getString("id")
            require(id.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))) {
                "Invalid document ID"
            }
            val title = value.getString("title")
            require(title.length <= 200)
            val input = value.getJSONArray("paragraphs")
            require(input.length() in 1..10000)
            var characters = 0
            var runCount = 0
            val paragraphs =
                (0 until input.length()).map { index ->
                    val paragraph = input.getJSONObject(index)
                    val rawRuns = paragraph.getJSONArray("runs")
                    runCount += rawRuns.length()
                    require(runCount <= 20000)
                    val runs =
                        (0 until rawRuns.length()).map { run ->
                            val raw = rawRuns.getJSONObject(run)
                            val text = raw.getString("text")
                            characters += text.length
                            require(characters <= 200000)
                            val color = if (raw.isNull("color")) null else raw.getString("color")
                            val size =
                                if (raw.isNull("fontSize")) null
                                else {
                                    require(raw.get("fontSize") is Int)
                                    raw.getInt("fontSize")
                                }
                            require(color == null || color.matches(Regex("#[0-9A-Fa-f]{6}")))
                            require(size == null || size in 8..72)
                            DocumentRun(
                                text,
                                raw.getBoolean("bold"),
                                raw.getBoolean("italic"),
                                raw.optBoolean("underline", false),
                                color?.uppercase(java.util.Locale.ROOT),
                                size,
                            )
                        }
                    val alignment =
                        paragraph.optString(
                            "alignment",
                            if (paragraph.getBoolean("justified")) "justify" else "left",
                        )
                    val indent = paragraph.optInt("indent", 0)
                    val heading = paragraph.optInt("heading", 0)
                    val list = paragraph.optString("list", "none")
                    require(
                        alignment in setOf("left", "center", "right", "justify") &&
                            indent in 0..8 &&
                            heading in 0..3 &&
                            list in setOf("none", "bullet", "number")
                    )
                    DocumentParagraph(
                        runs,
                        alignment == "justify",
                        alignment,
                        indent,
                        heading,
                        list,
                    )
                }
            val background =
                if (value.isNull("backgroundColor")) null else value.getString("backgroundColor")
            require(background == null || background.matches(Regex("#[0-9A-Fa-f]{6}")))
            return LocalDocument(
                id,
                title,
                paragraphs,
                background?.uppercase(java.util.Locale.ROOT),
                if (value.isNull("wallpaper")) null
                else DocumentWallpaper.parse(value.getJSONObject("wallpaper")),
            )
        }

        fun readCollection(value: String): List<LocalDocument> {
            require(value.length <= 10000000) { "Documents backup is too large" }
            val list = JSONArray(value)
            require(list.length() <= 100)
            val documents = (0 until list.length()).map { parse(list.getJSONObject(it)) }
            require(documents.map { it.id }.distinct().size == documents.size) {
                "Duplicate document ID"
            }
            return documents
        }
    }
}
