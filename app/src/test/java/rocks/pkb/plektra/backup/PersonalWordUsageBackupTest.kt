package rocks.pkb.plektra.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.suggestions.PersonalWordLearning
import rocks.pkb.plektra.core.suggestions.WordUsageStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class PersonalWordUsageBackupTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private lateinit var store:WordUsageStore
    @Before fun setup() {
        PersonalWordLearning.closeForTests(context)
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSuggestionsEnabled(context,true)
        store=PersonalWordLearning.store(context);store.clearAll()
    }
    @After fun cleanup() {PersonalWordLearning.closeForTests(context)}
    private fun learn(count:Int,word:String="planet") {repeat(count) {store.learn(Locale.ENGLISH,word,100L+it)}}
    private suspend fun backup():File {
        val file=File.createTempFile("ranking", ".zip",context.cacheDir)
        val result=BackupManager.createBackup(context,Uri.fromFile(file))
        assertTrue(result.toString(),result is BackupResult.Success)
        return file
    }
    private fun edit(zip:File,change:(File)->Unit):File {
        val root=File(context.cacheDir,"edit-${System.nanoTime()}").apply {mkdirs()}
        FileInputStream(zip).use {ZipHelper.unzip(it,root)}
        change(root)
        val target=File.createTempFile("ranking-edited", ".zip",context.cacheDir)
        FileOutputStream(target).use {ZipHelper.zip(root,it)}
        return target
    }

    @Test fun fullZipDrainsAcceptedWritesAndRestoresCountsThroughTheLiveStoreAndBothCaches()=runBlocking {
        learn(2)
        val zip=backup()
        ZipFile(zip).use {assertNotNull(it.getEntry(PersonalWordUsageBackup.PATH))}
        store.clearAll()
        assertNull(store.getUsage(Locale.ENGLISH,"planet"))
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
        learn(3,"planer")
        val result=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(result.toString(),result is RestoreResult.Success)
        assertEquals(2,store.getUsage(Locale.ENGLISH,"planet")!!.count)
        assertEquals(2,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
        assertNull(store.getExactUsage(Locale.ENGLISH,"planer"))
    }
    @Test fun olderZipWithoutPersonalCountsPreservesCurrentLearning()=runBlocking {
        learn(3)
        val root=File(context.cacheDir,"legacy-${System.nanoTime()}").apply {mkdirs()}
        PreferencesBackupHelper.dumpSharedPreferences(context,File(root,"prefs").apply {mkdirs()})
        val zip=File.createTempFile("legacy-ranking", ".zip",context.cacheDir)
        FileOutputStream(zip).use {ZipHelper.zip(root,it)}
        val result=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(result.toString(),result is RestoreResult.Success)
        assertEquals(3,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
    }
    @Test fun explicitEmptySnapshotClearsCurrentLearning()=runBlocking {
        val zip=backup();learn(4)
        val result=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(result.toString(),result is RestoreResult.Success)
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
    }
    @Test fun negativeCountersAreRejectedBeforePreferencesOrLearningChange()=runBlocking {
        learn(2)
        val zip=edit(backup()) {root ->
            val file=File(root,PersonalWordUsageBackup.EXACT_PATH)
            val json=JSONObject(file.readText())
            json.getJSONArray("rows").getJSONObject(0).put("count",-1)
            file.writeText(json.toString())
        }
        learn(5)
        SettingsManager.getPreferences(context).edit().putString("keyboard_layout","azerty").commit()
        val result=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(result is RestoreResult.Failure)
        assertEquals("azerty",SettingsManager.getPreferences(context).getString("keyboard_layout",null))
        assertEquals(7,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
    }
    @Test fun laterRestoreFailureRollsBackPersonalCountsAndPreferencesTogether()=runBlocking {
        SettingsManager.getPreferences(context).edit().putString("user_dictionary_entries","[\"backup\"]").commit()
        learn(2);val zip=backup();learn(5)
        SettingsManager.getPreferences(context).edit().putString("user_dictionary_entries","[\"current\"]").commit()
        val failing=object:ContextWrapper(context) {
            override fun sendBroadcast(intent:Intent) {throw IllegalStateException("synthetic post-restore failure")}
        }
        val result=RestoreManager.restore(failing,Uri.fromFile(zip))
        assertTrue(result.toString(),result is RestoreResult.Failure)
        assertEquals(7,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
        assertEquals("[\"current\"]",SettingsManager.getPreferences(context).getString("user_dictionary_entries",null))
    }

    @Test fun forgettingOneAccentedVariantDoesNotMakeTheOtherVariantUnrestorable()=runBlocking {
        store.learn(Locale.FRENCH,"sûr",100L)
        store.learn(Locale.FRENCH,"sur",200L)
        store.delete(Locale.FRENCH,"sur")
        assertNull(store.getUsage(Locale.FRENCH,"sur"))
        val zip=backup();store.clearAll()
        val result=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(result.toString(),result is RestoreResult.Success)
        assertEquals(1,store.getExactUsage(Locale.FRENCH,"sûr")!!.count)
        assertNull(store.getExactUsage(Locale.FRENCH,"sur"))
    }
    @Test fun failedPendingWriterAbortsExportAndReleasesLearningSuspension()=runBlocking {
        val owner=Any()
        val file=File(context.cacheDir,"failed-export-${System.nanoTime()}.zip")
        LearningOperationGate.registerPendingWriter(owner) {throw java.io.IOException("synthetic write failure")}
        try {
            val result=BackupManager.createBackup(context,Uri.fromFile(file))
            assertTrue(result is BackupResult.Failure)
            assertFalse(file.exists())
            assertFalse(LearningOperationGate.isLearningSuspended)
        } finally {LearningOperationGate.unregisterPendingWriter(owner)}
    }

    @Test fun earlierPrototypeRowsWithNumericKeysRemainPortable()=runBlocking {
        val zip=edit(backup()) {root ->
            val row=JSONObject().put("locale","en").put("word","r2d2").put("count",4).put("last_used",100L)
            fun table(path:String,key:String) {
                val data=JSONObject(row.toString()).put(key,"r2d2")
                File(root,path).writeText(JSONObject().put("schemaVersion",1).put("rows",org.json.JSONArray().put(data)).toString())
            }
            table(PersonalWordUsageBackup.PATH,"normalized_word")
            table(PersonalWordUsageBackup.EXACT_PATH,"exact_word")
        }
        val result=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(result.toString(),result is RestoreResult.Success)
        assertEquals(4,store.getExactUsage(Locale.ENGLISH,"r2d2")!!.count)
    }

    @Test fun unrelatedPrototypeLearningDatasetsAreReportedAsSkipped()=runBlocking {
        val zip=edit(backup()) {root ->
            File(root,"learning/phrase_usage.json").writeText("{\"schemaVersion\":1,\"rows\":[]}")
        }
        val result=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(result.toString(),result is RestoreResult.Success)
        assertTrue((result as RestoreResult.Success).fileSummary.skippedFiles.contains("learning/phrase_usage.json"))
    }
}
