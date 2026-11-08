package rocks.pkb.plektra.backup

import android.content.Context
import android.net.Uri
import rocks.pkb.plektra.dictionaries.DictionaryRepositoryManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesAndDictionaryBackupTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        listOf("notes", "showcase_notes", "plektra_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, DictionaryPackageBackup.DIRECTORY).deleteRecursively()
    }

    @Test fun notesAndProfileRoundtripExcludesSyntheticCaptureAndReportsMissingPack() = runBlocking {
        val notes = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
        val collection = JSONArray().put(JSONObject().put("id", "first").put("title", "Heading")
            .put("body", "# Markdown\nText").put("cursor", 3).put("headingFocused", false))
            .put(JSONObject().put("id", "second").put("title", "Second").put("body", "Other")
                .put("cursor", 2).put("headingFocused", true)).toString()
        notes.edit().putString("collection", collection).putString("active_note", "second").commit()
        context.getSharedPreferences("showcase_notes", Context.MODE_PRIVATE).edit().putString("body", "Private capture").commit()
        val prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_input_styles", "fr:qwerty")
            .putString("input_style_suggestion_locales", "{\"fr:qwerty\":[\"es\"]}")
            .putBoolean("suggestions_bold", true).commit()
        val archive = File(context.cacheDir, "notes-dictionary-roundtrip.zip")
        val result = BackupManager.createBackup(context, Uri.fromFile(archive))
        assertTrue(result is BackupResult.Success)
        assertFalse((result as BackupResult.Success).metadata.components.any { it.contains("showcase_notes") })
        notes.edit().clear().commit()
        prefs.edit().clear().commit()
        val restored = RestoreManager.restore(context, Uri.fromFile(archive)) as RestoreResult.Success
        assertEquals(collection, notes.getString("collection", null))
        assertEquals("second", notes.getString("active_note", null))
        assertEquals("fr:qwerty", prefs.getString("custom_input_styles", null))
        assertTrue(prefs.getBoolean("suggestions_bold", false))
        assertTrue(restored.fileSummary.missingDictionaries.containsAll(listOf("fr", "es")))
        assertEquals("Private capture", context.getSharedPreferences("showcase_notes", Context.MODE_PRIVATE).getString("body", null))
    }

    @Test fun noteImagesTravelWithBackupAndForeignFilesAreRejected() {
        val store = rocks.pkb.plektra.NotesAttachmentStore.forContext(context)
        val name = store.newName("image/png")
        store.import(rocks.pkb.plektra.NotesTestImages.PNG.inputStream(), name)
        val backup = File(context.cacheDir, "note-image-backup").apply { deleteRecursively(); mkdirs() }
        val paths = FileBackupHelper.snapshotInternalFiles(context, backup)
        assertTrue(paths.contains("files/${rocks.pkb.plektra.NotesAttachmentStore.DIRECTORY}/$name"))
        store.directory.deleteRecursively()
        assertEquals(1, FileBackupHelper.validateRestoreFiles(backup))
        FileBackupHelper.restoreFiles(context, backup)
        assertTrue(store.file(name)!!.readBytes().contentEquals(rocks.pkb.plektra.NotesTestImages.PNG))

        File(backup, "${rocks.pkb.plektra.NotesAttachmentStore.DIRECTORY}/${store.newName("image/jpeg")}")
            .writeBytes(rocks.pkb.plektra.NotesTestImages.PNG)
        assertThrows(IllegalArgumentException::class.java) { FileBackupHelper.validateRestoreFiles(backup) }
        store.directory.deleteRecursively()
    }

    @Test fun ownImportedDictionaryTravelsWithBackup() {
        val source = File(context.filesDir, "${DictionaryPackageBackup.DIRECTORY}/zz_base.dict")
        source.parentFile!!.mkdirs()
        source.writeText("""{"normalizedIndex":{"test":[{"word":"Test","frequency":100,"source":0}]},"prefixCache":{}}""")
        val backup = File(context.cacheDir, "private-dictionary-backup").apply { deleteRecursively(); mkdirs() }
        val paths = FileBackupHelper.snapshotInternalFiles(context, backup)
        assertTrue(paths.contains("files/${DictionaryPackageBackup.DIRECTORY}/zz_base.dict"))
        val hash = DictionaryPackageBackup.hash(source)
        source.delete()
        val restored = FileBackupHelper.restoreFiles(context, backup)
        assertTrue(restored.restoredFiles.contains("${DictionaryPackageBackup.DIRECTORY}/zz_base.dict"))
        assertEquals(hash, DictionaryPackageBackup.hash(source))
    }

    @Test fun repositoryPackIsReferenceAndBundledPackNeedsNoDownload() {
        val catalog = DictionaryRepositoryManager.offlineCatalog(context).items
        val pack = catalog.first { it.filename == "en_base.dict" }
        val root = File(context.cacheDir, "reference-backup").apply { deleteRecursively(); mkdirs() }
        File(root, DictionaryPackageBackup.MANIFEST).writeText(JSONObject().put("schemaVersion", 1)
            .put("packs", JSONArray().put(JSONObject().put("id", pack.id).put("filename", pack.filename).put("sha256", pack.sha256))).toString())
        val installed = File(context.filesDir, "${DictionaryPackageBackup.DIRECTORY}/${pack.filename}")
        installed.parentFile!!.mkdirs()
        context.assets.open("common/dictionaries_serialized/${pack.filename}").use { input ->
            installed.outputStream().use { input.copyTo(it) }
        }
        val paths = DictionaryPackageBackup.snapshot(context, root)
        assertTrue(paths.contains(DictionaryPackageBackup.MANIFEST))
        assertFalse(File(root, "${DictionaryPackageBackup.DIRECTORY}/${pack.filename}").exists())
        assertEquals(pack.id, JSONObject(File(root, DictionaryPackageBackup.MANIFEST).readText())
            .getJSONArray("packs").getJSONObject(0).getString("id"))
        installed.delete()
        DictionaryPackageBackup.validate(root)
        assertTrue(DictionaryPackageBackup.missing(context, root).isEmpty())
        val modified = File(context.filesDir, "${DictionaryPackageBackup.DIRECTORY}/${pack.filename}")
        modified.parentFile!!.mkdirs()
        modified.writeText("Different private version")
        assertEquals(listOf("en"), DictionaryPackageBackup.missing(context, root))
    }

    @Test fun malformedNotesAreRejectedBeforeRestore() {
        val note = JSONObject().put("id", "n").put("title", "Title").put("body", 15).put("cursor", 0)
        val entries = mapOf("collection" to PreferenceValue(PreferenceValueType.STRING, JSONArray().put(note).toString()),
            "active_note" to PreferenceValue(PreferenceValueType.STRING, "n"))
        val target = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
        target.edit().putString("body", "Keep me").commit()
        assertThrows(IllegalArgumentException::class.java) {
            PreferencesBackupHelper.restorePreferences(context, mapOf("notes" to entries))
        }
        assertEquals("Keep me", target.getString("body", null))
    }
}
