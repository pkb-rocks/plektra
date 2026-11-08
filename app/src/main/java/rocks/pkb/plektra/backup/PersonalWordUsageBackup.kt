package rocks.pkb.plektra.backup

import android.content.Context
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import rocks.pkb.plektra.core.suggestions.PersonalWordLearning
import rocks.pkb.plektra.core.suggestions.WordNormalization
import rocks.pkb.plektra.core.suggestions.WordUsageStore

/** Portable table data; compatible with the two word-usage files from earlier prototypes. */
internal object PersonalWordUsageBackup {
    const val PATH = "learning/word_usage.json"
    const val EXACT_PATH = "learning/exact_word_usage.json"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_ROWS = 100000

    fun write(context: Context, root: File) {
        val snapshot = PersonalWordLearning.store(context).snapshotUsage()
        fun writeTable(path:String,keyColumn:String,values:List<WordUsageStore.BackupRow>) {
            val rows=JSONArray().apply {values.forEach {row ->put(JSONObject()
                .put("locale",row.language).put(keyColumn,row.key).put("word",row.word)
                .put("count",row.count).put("last_used",row.lastUsed))}}
            val payload=JSONObject().put("schemaVersion",1).put("rows",rows).toString()
            require(payload.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) {"Personal word usage exceeds backup limit"}
            File(root,path).apply {parentFile!!.mkdirs();writeText(payload,Charsets.UTF_8)}
        }
        writeTable(PATH,"normalized_word",snapshot.normalized)
        writeTable(EXACT_PATH,"exact_word",snapshot.exact)
    }

    fun read(root: File): WordUsageStore.Snapshot? = try { readValidated(root) }
        catch (_: org.json.JSONException) { throw IllegalArgumentException("Invalid personal word usage backup") }

    private fun readValidated(root: File): WordUsageStore.Snapshot? {
        val normalizedFile=File(root,PATH);val exactFile=File(root,EXACT_PATH)
        if(!normalizedFile.exists() && !exactFile.exists()) return null
        require(normalizedFile.exists() && exactFile.exists()) {"Incomplete personal word usage backup"}
        fun parse(file:File,keyColumn:String,exact:Boolean):List<WordUsageStore.BackupRow> {
            require(file.isFile && file.length() in 2..MAX_BYTES) {"Invalid personal word usage backup"}
            val json=JSONObject(file.readText(Charsets.UTF_8))
            require(json.keys().asSequence().toSet()==setOf("schemaVersion","rows") && json.get("schemaVersion")==1) {
                "Unsupported personal word usage backup"
            }
            val rows=json.getJSONArray("rows")
            require(rows.length()<=MAX_ROWS) {"Personal word usage exceeds backup limit"}
            val seen=HashSet<Pair<String,String>>()
            return (0 until rows.length()).map {index ->
                val row=rows.getJSONObject(index)
                require(row.keys().asSequence().toSet()==setOf("locale",keyColumn,"word","count","last_used") &&
                    listOf("locale",keyColumn,"word").all {row.get(it) is String}) {"Invalid personal word usage row"}
                val language=row.getString("locale");val key=row.getString(keyColumn);val word=row.getString("word")
                val count=row.get("count");val time=row.get("last_used")
                require((count is Int || count is Long) && (time is Int || time is Long)) {"Invalid personal word usage counters"}
                val countLong=(count as Number).toLong();val lastUsed=(time as Number).toLong()
                require(language.matches(Regex("[a-z]{2,8}")) && word.isNotBlank() && word.length<=256 && key.isNotBlank() && key.length<=512 &&
                    countLong in 1..Int.MAX_VALUE.toLong() && lastUsed>=0) {"Invalid personal word usage row"}
                val locale=Locale.forLanguageTag(language)
                val expected=if(exact) word.lowercase(locale) else WordNormalization.normalizeForSuggestion(word,locale)
                // Preserve digit-bearing keys produced by earlier prototypes without changing runtime matching.
                val legacy=Normalizer.normalize(WordNormalization.foldCompatibilityLetters(
                    WordNormalization.normalizeApostrophes(word).lowercase(locale)),Normalizer.Form.NFD)
                    .replace(Regex("\\p{Mn}"),"").replace(Regex("[^\\p{L}\\p{N}']"),"")
                require((key==expected || (!exact && key==legacy)) && seen.add(language to key)) {"Inconsistent personal word usage key"}
                WordUsageStore.BackupRow(language,key,word,countLong.toInt(),lastUsed)
            }
        }
        // Legacy forgetting can leave an exact form after the shared normalized row is removed.
        return WordUsageStore.Snapshot(parse(normalizedFile,"normalized_word",false),parse(exactFile,"exact_word",true))
    }
}
