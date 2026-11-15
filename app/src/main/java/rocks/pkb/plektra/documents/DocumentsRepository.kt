package rocks.pkb.plektra.documents

import android.content.Context
import org.json.JSONArray

/** Local document collection. Callers in other processes must use its owning process. */
internal class DocumentsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("documents", Context.MODE_PRIVATE)

    fun all(): List<LocalDocument> =
        synchronized(lock) {
            LocalDocument.readCollection(prefs.getString("collection", "[]") ?: "[]")
        }

    fun save(document: LocalDocument) =
        synchronized(lock) {
            val valid = LocalDocument.parse(document.json())
            val previous = all()
            val updated =
                if (previous.any { it.id == valid.id }) {
                    previous.map { if (it.id == valid.id) valid else it }
                } else {
                    previous + valid
                }
            writeCollection(updated)
        }

    fun create(): LocalDocument = LocalDocument.blank().also(::save)

    fun discardEmpty(id: String) =
        synchronized(lock) {
            val previous = all()
            val remaining = previous.filterNot { it.id == id && it.isEmptyDraft() }
            if (remaining.size != previous.size) writeCollection(remaining)
        }

    fun rename(id: String, title: String): LocalDocument =
        synchronized(lock) { all().first { it.id == id }.copy(title = title).also(::save) }

    fun updateContent(
        id: String,
        paragraphs: List<DocumentParagraph>,
        backgroundColor: String? = null,
        wallpaper: DocumentWallpaper? = null,
        replaceWallpaper: Boolean = false,
    ): LocalDocument =
        synchronized(lock) {
            val current = all().first { it.id == id }
            current
                .copy(
                    paragraphs = paragraphs,
                    backgroundColor =
                        if (replaceWallpaper) backgroundColor
                        else backgroundColor ?: current.backgroundColor,
                    wallpaper = if (replaceWallpaper) wallpaper else current.wallpaper,
                )
                .also(::save)
        }

    private fun writeCollection(documents: List<LocalDocument>) {
        val value =
            JSONArray().also { array -> documents.forEach { array.put(it.json()) } }.toString()
        LocalDocument.readCollection(value)
        check(prefs.edit().putString("collection", value).commit()) { "Document save failed" }
    }

    companion object {
        private val lock = Any()
    }
}
