package rocks.pkb.plektra.documents

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentsModelTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetCollection() {
        context.getSharedPreferences("documents", 0).edit().clear().commit()
    }

    private fun sample() =
        LocalDocument.blank()
            .copy(
                title = "Example",
                paragraphs =
                    listOf(
                        DocumentParagraph(
                            listOf(
                                DocumentRun("Hello & <world> ", true),
                                DocumentRun("italic", italic = true),
                            ),
                            true,
                        ),
                        DocumentParagraph(listOf(DocumentRun("\u00e9\u00e0\nnext\t\uD83D\uDE42", true, true))),
                    ),
            )

    @Test
    fun localSaveAndReopenKeepIndependentDocumentsAndFormatting() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("documents", 0).edit().clear().commit()
        val repository = DocumentsRepository(context)
        val first = sample()
        repository.save(first)
        val second = repository.create()
        repository.save(second.copy(title = "Second"))
        val reopened = DocumentsRepository(context).all()
        assertEquals(first, reopened.first { it.id == first.id })
        assertEquals("Second", reopened.first { it.id == second.id }.title)
    }

    @Test
    fun invalidDocumentsDoNotOverwriteTheExistingCollection() {
        val repository = DocumentsRepository(RuntimeEnvironment.getApplication())
        val valid = sample()
        repository.save(valid)
        val before = repository.all()
        assertThrows(IllegalArgumentException::class.java) {
            repository.save(valid.copy(id = "../../outside"))
        }
        assertEquals(before, repository.all())
        assertThrows(IllegalArgumentException::class.java) {
            LocalDocument.readCollection(JSONArray().put(valid.json()).put(valid.json()).toString())
        }
    }

    @Test
    fun invalidStoredCollectionIsReportedAndPreserved() {
        val prefs = context.getSharedPreferences("documents", 0)
        prefs.edit().putString("collection", "[broken").commit()
        val repository = DocumentsRepository(context)
        assertThrows(Exception::class.java) { repository.save(sample()) }
        assertEquals("[broken", prefs.getString("collection", null))
    }

    @Test
    fun renameAndContentUpdatePreserveOtherDocuments() {
        val repository = DocumentsRepository(context)
        val first = sample()
        val second = sample().copy(id = LocalDocument.blank().id, title = "Second")
        repository.save(first)
        repository.save(second)
        repository.rename(first.id, "Renamed")
        val updated =
            repository.updateContent(
                first.id,
                listOf(DocumentParagraph(listOf(DocumentRun("New", italic = true)))),
            )
        assertEquals("Renamed", updated.title)
        assertEquals(second, repository.all().first { it.id == second.id })
        assertEquals(updated, DocumentsRepository(context).all().first { it.id == first.id })
    }

    @Test
    fun onlyUntitledEmptyDraftsAreDiscarded() {
        val repository = DocumentsRepository(context)
        val blank = repository.create()
        val titled = repository.create().copy(title = "Keep")
        val content = sample()
        repository.save(titled)
        repository.save(content)
        repository.discardEmpty(blank.id)
        repository.discardEmpty(titled.id)
        repository.discardEmpty(content.id)
        assertEquals(setOf(titled.id, content.id), repository.all().map { it.id }.toSet())
    }

    @Test
    fun missingOptionalStyleFieldsUseLegacyDefaults() {
        val json = sample().json()
        json.remove("wallpaper")
        json.remove("backgroundColor")
        val paragraph = json.getJSONArray("paragraphs").getJSONObject(0)
        for (key in listOf("alignment", "indent", "heading", "list")) paragraph.remove(key)
        val run = paragraph.getJSONArray("runs").getJSONObject(0)
        for (key in listOf("underline", "color", "fontSize")) run.remove(key)
        val restored = LocalDocument.parse(json)
        assertEquals("justify", restored.paragraphs.first().alignment)
        assertFalse(restored.paragraphs.first().runs.first().underline)
        assertNull(restored.wallpaper)
    }

    @Test
    fun outOfRangeAndUnknownVersionsAreRejectedWithoutOverwritingNotes() {
        val repository = DocumentsRepository(context)
        val document = sample()
        repository.save(document)
        for (invalid in
            listOf(
                document.copy(title = "x".repeat(201)),
                document.copy(
                    paragraphs = listOf(DocumentParagraph(listOf(DocumentRun("x", fontSize = 100))))
                ),
            )) {
            assertThrows(IllegalArgumentException::class.java) { repository.save(invalid) }
        }
        val future = document.json().put("version", 2)
        assertThrows(IllegalArgumentException::class.java) { LocalDocument.parse(future) }
        assertEquals(listOf(document), repository.all())
    }

    @Test
    fun editsFromDifferentRepositoryInstancesDoNotLoseDocuments() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..10).map { number ->
                    executor.submit {
                        DocumentsRepository(context)
                            .save(
                                sample().copy(id = LocalDocument.blank().id, title = "Note $number")
                            )
                    }
                }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(10, DocumentsRepository(context).all().size)
    }
}
