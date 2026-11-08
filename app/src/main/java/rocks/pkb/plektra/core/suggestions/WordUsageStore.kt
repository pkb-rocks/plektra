package rocks.pkb.plektra.core.suggestions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores per-language word usage independently from dictionary frequencies.
 *
 * DictionaryEntry.frequency represents general corpus frequency, while this
 * store represents the individual user's usage of a word.
 *
 * Words are indexed with the same normalization used by SuggestionEngine so
 * spelling variants handled as equivalent by the engine share one usage count.
 */
interface WordUsageRepository {
    fun learn(
        locale: Locale,
        word: String,
        nowMs: Long = System.currentTimeMillis()
    )

    fun getUsage(locale: Locale, word: String): WordUsageStore.Usage?

    fun getExactUsage(locale: Locale, word: String): WordUsageStore.Usage?
    fun delete(locale: Locale, word: String): Int

    fun clearAll()
}

class WordUsageStore(context: Context, private val asyncPersistence: Boolean = false) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), WordUsageRepository {

    private val modelLock = Any()
    private val writer = if (asyncPersistence) OrderedPersistenceQueue("WordUsageStore-usage") else null
    fun flushPendingWrites() { writer?.flush() }
    fun closeWhenDrained() { writer?.closeWhenDrained() }

    init { if (asyncPersistence) setWriteAheadLoggingEnabled(true) }

    private val usageCache = ConcurrentHashMap<String, Usage>()
    private val exactUsageCache = ConcurrentHashMap<String, Usage>()
    private val usageMissCache = ConcurrentHashMap.newKeySet<String>()
    private val exactUsageMissCache = ConcurrentHashMap.newKeySet<String>()
    private val maxMissCacheEntries = 4096

    private fun rememberMiss(cache: MutableSet<String>, key: String) {
        if (cache.size >= maxMissCacheEntries) {
            cache.clear()
        }
        cache.add(key)
    }
    data class Usage(
        val word: String,
        val count: Int,
        val lastUsed: Long
    )

    override fun close() {
        synchronized(modelLock) {
            flushPendingWrites()
            writer?.closeWhenDrained()
            super.close()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_WORD_USAGE (
                $COL_LOCALE TEXT NOT NULL,
                $COL_NORMALIZED_WORD TEXT NOT NULL,
                $COL_WORD TEXT NOT NULL,
                $COL_COUNT INTEGER NOT NULL,
                $COL_LAST_USED INTEGER NOT NULL,
                PRIMARY KEY ($COL_LOCALE, $COL_NORMALIZED_WORD)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
    CREATE TABLE $TABLE_EXACT_WORD_USAGE (
        $COL_LOCALE TEXT NOT NULL,
        $COL_EXACT_WORD TEXT NOT NULL,
        $COL_WORD TEXT NOT NULL,
        $COL_COUNT INTEGER NOT NULL,
        $COL_LAST_USED INTEGER NOT NULL,
        PRIMARY KEY ($COL_LOCALE, $COL_EXACT_WORD)
    )
    """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 4) {
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS $TABLE_EXACT_WORD_USAGE (
                $COL_LOCALE TEXT NOT NULL,
                $COL_EXACT_WORD TEXT NOT NULL,
                $COL_WORD TEXT NOT NULL,
                $COL_COUNT INTEGER NOT NULL,
                $COL_LAST_USED INTEGER NOT NULL,
                PRIMARY KEY ($COL_LOCALE, $COL_EXACT_WORD)
            )
            """.trimIndent()
            )
        }
    }
    /** *Publish counters immediately; queue only the durable SQL mutation.* */
    override fun learn(locale: Locale, word: String, nowMs: Long) {
        synchronized(modelLock) {
        if (!asyncPersistence) { persistLearn(locale, word, nowMs); return }
        if (word.isBlank() || normalize(word, locale).isBlank()) return
        val prior = getUsage(locale, word)
        val exact = getExactUsage(locale, word)
        usageCache[cacheKey(locale, word)] = Usage(word, (prior?.count ?: 0) + 1, nowMs)
        exactUsageCache[exactCacheKey(locale, word)] = Usage(word, (exact?.count ?: 0) + 1, nowMs)
        usageMissCache.remove(cacheKey(locale, word))
        exactUsageMissCache.remove(exactCacheKey(locale, word))
        writer!!.enqueue { persistLearn(locale, word, nowMs) }
        }
    }

    private fun persistLearn(
        locale: Locale,
        word: String,
        nowMs: Long
    ) {
        if (word.isBlank()) return

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val normalizedWord = normalize(word, locale)
        if (normalizedWord.isBlank()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                TABLE_WORD_USAGE,
                null,
                ContentValues().apply {
                    put(COL_LOCALE, localeKey)
                    put(COL_NORMALIZED_WORD, normalizedWord)
                    put(COL_WORD, word)
                    put(COL_COUNT, 0)
                    put(COL_LAST_USED, nowMs)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )

            db.execSQL(
                """
            UPDATE $TABLE_WORD_USAGE
            SET $COL_COUNT = $COL_COUNT + 1,
                $COL_LAST_USED = ?,
                $COL_WORD = ?
            WHERE $COL_LOCALE = ?
                AND $COL_NORMALIZED_WORD = ?
            """.trimIndent(),
                arrayOf(nowMs, word, localeKey, normalizedWord)
            )

            val exactWord = exactKey(word, locale)

            db.insertWithOnConflict(
                TABLE_EXACT_WORD_USAGE,
                null,
                ContentValues().apply {
                    put(COL_LOCALE, localeKey)
                    put(COL_EXACT_WORD, exactWord)
                    put(COL_WORD, word)
                    put(COL_COUNT, 0)
                    put(COL_LAST_USED, nowMs)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )

            db.execSQL(
                """
    UPDATE $TABLE_EXACT_WORD_USAGE
    SET $COL_COUNT = $COL_COUNT + 1,
        $COL_LAST_USED = ?,
        $COL_WORD = ?
    WHERE $COL_LOCALE = ?
        AND $COL_EXACT_WORD = ?
    """.trimIndent(),
                arrayOf(nowMs, word, localeKey, exactWord)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Keep the in-memory cache synchronized with the persisted usage count.
        // *The immediate cache may already contain later queued increments.*
        if (asyncPersistence) return

        val key = cacheKey(locale, word)
        usageMissCache.remove(key)
        val persistedUsage = readableDatabase.query(
            TABLE_WORD_USAGE,
            arrayOf(COL_WORD, COL_COUNT, COL_LAST_USED),
            "$COL_LOCALE = ? AND $COL_NORMALIZED_WORD = ?",
            arrayOf(localeKey, normalizedWord),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                rememberMiss(exactUsageMissCache, key)
                null
            } else {
                Usage(
                    word = cursor.getString(cursor.getColumnIndexOrThrow(COL_WORD)),
                    count = cursor.getInt(cursor.getColumnIndexOrThrow(COL_COUNT)),
                    lastUsed = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_USED))
                )
            }
        }

        if (persistedUsage != null) {
            usageCache[key] = persistedUsage
        }
        exactUsageCache.remove(exactCacheKey(locale, word))
        usageMissCache.remove(cacheKey(locale, word))
        exactUsageMissCache.remove(exactCacheKey(locale, word))
    }

        override fun getUsage(
            locale: Locale,
            word: String
        ): Usage? = synchronized(modelLock) {
            if (word.isBlank()) return null

            val localeKey = locale.language.lowercase(Locale.ROOT)
            val normalizedWord = normalize(word, locale)
            if (normalizedWord.isBlank()) return null

            val key = cacheKey(locale, word)

            usageCache[key]?.let { cached ->
                return cached
            }

            // *Absent usage is common while ranking dictionary candidates; remember bounded misses.*
            if (usageMissCache.contains(key)) return null
            val cursor = readableDatabase.query(
                TABLE_WORD_USAGE,
                arrayOf(COL_WORD, COL_COUNT, COL_LAST_USED),
                "$COL_LOCALE = ? AND $COL_NORMALIZED_WORD = ?",
                arrayOf(localeKey, normalizedWord),
                null,
                null,
                null,
                "1"
            )

            cursor.use {
                if (!it.moveToFirst()) { rememberMiss(usageMissCache, key); return null }

                val usage = Usage(
                    word = it.getString(it.getColumnIndexOrThrow(COL_WORD)),
                    count = it.getInt(it.getColumnIndexOrThrow(COL_COUNT)),
                    lastUsed = it.getLong(it.getColumnIndexOrThrow(COL_LAST_USED))
                )

                usageCache[key] = usage
                return usage
            }
        }

    override fun getExactUsage(
        locale: Locale,
        word: String
    ): Usage? = synchronized(modelLock) {
        if (word.isBlank()) return null

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val exactWord = exactKey(word, locale)
        val key = exactCacheKey(locale, word)

        exactUsageCache[key]?.let { cached ->
            return cached
        }

        if (exactUsageMissCache.contains(key)) {
            return null
        }

        readableDatabase.query(
            TABLE_EXACT_WORD_USAGE,
            arrayOf(COL_WORD, COL_COUNT, COL_LAST_USED),
            "$COL_LOCALE = ? AND $COL_EXACT_WORD = ?",
            arrayOf(localeKey, exactWord),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) { rememberMiss(exactUsageMissCache, key); return null }

            val usage = Usage(
                word = cursor.getString(cursor.getColumnIndexOrThrow(COL_WORD)),
                count = cursor.getInt(cursor.getColumnIndexOrThrow(COL_COUNT)),
                lastUsed = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_USED))
            )

            exactUsageCache[key] = usage
            return usage
        }
    }

    override fun delete(locale: Locale, word: String): Int {
        synchronized(modelLock) {
        flushPendingWrites()
        if (word.isBlank()) return 0

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val normalizedWord = normalize(word, locale)
        if (normalizedWord.isBlank()) return 0

        val exactWord = exactKey(word, locale)
        val db = writableDatabase

        val deleted: Int

        db.beginTransaction()
        try {
            deleted = db.delete(
                TABLE_WORD_USAGE,
                "$COL_LOCALE = ? AND $COL_NORMALIZED_WORD = ?",
                arrayOf(localeKey, normalizedWord)
            )

            db.delete(
                TABLE_EXACT_WORD_USAGE,
                "$COL_LOCALE = ? AND $COL_EXACT_WORD = ?",
                arrayOf(localeKey, exactWord)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        exactUsageCache.remove(exactCacheKey(locale, word))
        usageMissCache.remove(cacheKey(locale, word))
        exactUsageMissCache.remove(exactCacheKey(locale, word))

        if (deleted > 0) {
            usageCache.remove(cacheKey(locale, word))
        }

        return deleted
        }
    }

    override fun clearAll() {
        synchronized(modelLock) {
        flushPendingWrites()
        val db = writableDatabase

        db.beginTransaction()
        try {
            db.delete(TABLE_WORD_USAGE, null, null)
            db.delete(TABLE_EXACT_WORD_USAGE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        usageCache.clear()
        exactUsageCache.clear()
        usageMissCache.clear()
        exactUsageMissCache.clear()
        }
    }

    fun invalidateCache() {
        synchronized(modelLock) {
        flushPendingWrites()
        usageCache.clear()
        exactUsageCache.clear()
        usageMissCache.clear()
        exactUsageMissCache.clear()
        }
    }

    private fun cacheKey(locale: Locale, word: String): String {
        val language = locale.language.lowercase(Locale.ROOT)
        val normalizedWord = normalize(word, locale)
        return "$language:$normalizedWord"
    }
    private fun exactCacheKey(locale: Locale, word: String): String {
        val language = locale.language.lowercase(Locale.ROOT)
        val exactWord = exactKey(word, locale)
        return "$language:$exactWord"
    }
    private fun exactKey(word: String, locale: Locale): String {
        return word.lowercase(locale)
    }

    private fun normalize(word: String, locale: Locale): String {
        return WordNormalization.normalizeForSuggestion(word, locale)
    }


    internal data class BackupRow(val language: String, val key: String, val word: String, val count: Int, val lastUsed: Long)
    internal data class Snapshot(val normalized: List<BackupRow>, val exact: List<BackupRow>)

    internal fun snapshotUsage(): Snapshot = synchronized(modelLock) {
        flushPendingWrites()
        fun rows(table: String, keyColumn: String): List<BackupRow> {
            val result = ArrayList<BackupRow>()
            readableDatabase.query(table, arrayOf(COL_LOCALE,keyColumn,COL_WORD,COL_COUNT,COL_LAST_USED),
                null,null,null,null,"$COL_LOCALE, $keyColumn", "100001").use { cursor ->
                while(cursor.moveToNext()) {
                    require(result.size < 100000) { "Personal word usage exceeds backup limit" }
                    result.add(BackupRow(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getInt(3),cursor.getLong(4)))
                }
            }
            return result
        }
        Snapshot(rows(TABLE_WORD_USAGE,COL_NORMALIZED_WORD),rows(TABLE_EXACT_WORD_USAGE,COL_EXACT_WORD))
    }

    /** Replace rows transactionally through the live connection; never replace an open SQLite file. */
    internal fun replaceUsage(snapshot: Snapshot) = synchronized(modelLock) {
        flushPendingWrites()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_WORD_USAGE,null,null)
            db.delete(TABLE_EXACT_WORD_USAGE,null,null)
            fun insert(table: String, keyColumn: String, rows: List<BackupRow>) {
                rows.forEach { row ->
                    require(row.count > 0 && row.lastUsed >= 0 && row.word.isNotBlank()) { "Invalid personal word usage" }
                    db.insertOrThrow(table,null,ContentValues().apply {
                        put(COL_LOCALE,row.language);put(keyColumn,row.key);put(COL_WORD,row.word)
                        put(COL_COUNT,row.count);put(COL_LAST_USED,row.lastUsed)
                    })
                }
            }
            insert(TABLE_WORD_USAGE,COL_NORMALIZED_WORD,snapshot.normalized)
            insert(TABLE_EXACT_WORD_USAGE,COL_EXACT_WORD,snapshot.exact)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        usageCache.clear();exactUsageCache.clear();usageMissCache.clear();exactUsageMissCache.clear()
    }

    companion object {
        private const val DATABASE_NAME = "word_usage.db"
        private const val DATABASE_VERSION = 4

        private const val TABLE_WORD_USAGE = "word_usage"
        private const val TABLE_EXACT_WORD_USAGE = "exact_word_usage"

        private const val COL_LOCALE = "locale"
        private const val COL_NORMALIZED_WORD = "normalized_word"
        private const val COL_EXACT_WORD = "exact_word"
        private const val COL_WORD = "word"
        private const val COL_COUNT = "count"
        private const val COL_LAST_USED = "last_used"
    }
}