package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.Context
import android.database.sqlite.SQLiteDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WordUsageStoreTest {

    private lateinit var store: WordUsageStore

    private val french = Locale.forLanguageTag("fr-FR")
    private val english = Locale.forLanguageTag("en-US")
    private val german = Locale.forLanguageTag("de-DE")

    @Before
    fun setUp() {
        store = WordUsageStore(RuntimeEnvironment.getApplication())
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
        store.close()
    }

    @Test
    fun learn_insertsNewWord() {
        store.learn(french, "écris", nowMs = 100L)

        val usage = store.getUsage(french, "écris")

        assertEquals("écris", usage?.word)
        assertEquals(1, usage?.count)
        assertEquals(100L, usage?.lastUsed)
    }

    @Test
    fun learn_keepsLocalesSeparate() {
        store.learn(french, "écris", nowMs = 100L)
        store.learn(english, "écris", nowMs = 200L)

        assertEquals(1, store.getUsage(french, "écris")?.count)
        assertEquals(1, store.getUsage(english, "écris")?.count)
        assertNull(store.getUsage(german, "écris"))
    }

    @Test
    fun lookup_usesSuggestionNormalization() {
        store.learn(french, "Écris", nowMs = 100L)

        val usage = store.getUsage(french, "ecris")

        assertEquals("Écris", usage?.word)
        assertEquals(1, usage?.count)
    }

    @Test
    fun delete_usesSuggestionNormalization() {
        store.learn(french, "Écris", nowMs = 100L)

        val deleted = store.delete(french, "ecris")

        assertEquals(1, deleted)
        assertNull(store.getUsage(french, "écris"))
    }
    @Test
    fun regionalVariantsShareUsageWithinSameLanguage() {
        val france = Locale.forLanguageTag("fr-FR")
        val canada = Locale.forLanguageTag("fr-CA")

        store.learn(france, "écris", nowMs = 1000L)

        val usage = store.getUsage(canada, "écris")

        assertEquals(1, usage?.count)
        assertEquals(1000L, usage?.lastUsed)
    }
    @Test
    fun learnUpdatesCachedUsageImmediately() {
        store.learn(french, "écris", nowMs = 100L)

        val firstUsage = store.getUsage(french, "écris")
        assertEquals(1, firstUsage?.count)

        store.learn(french, "écris", nowMs = 200L)

        val updatedUsage = store.getUsage(french, "écris")

        assertEquals(2, updatedUsage?.count)
        assertEquals(200L, updatedUsage?.lastUsed)
    }

    @Test
    fun deleteRemovesCachedUsage() {
        store.learn(french, "écris", nowMs = 100L)

        // Populate the in-memory cache before deleting the persisted entry.
        assertEquals(1, store.getUsage(french, "écris")?.count)

        val deleted = store.delete(french, "écris")

        assertEquals(1, deleted)
        assertNull(store.getUsage(french, "écris"))
    }
    @Test
    fun clearAllRemovesCachedUsage() {
        store.learn(french, "écris", nowMs = 100L)

        // Populate the in-memory cache before clearing all persisted usage.
        assertEquals(1, store.getUsage(french, "écris")?.count)

        store.clearAll()

        assertNull(store.getUsage(french, "écris"))
    }

    @Test
    fun delete_removesOnlyMatchingExactUsage() {
        store.learn(french, "sûr", nowMs = 100L)
        store.learn(french, "sur", nowMs = 200L)

        store.delete(french, "sur")

        assertNull(store.getExactUsage(french, "sur"))
        assertEquals(1, store.getExactUsage(french, "sûr")?.count)
    }

    @Test
    fun exactUsage_keepsAccentedFormsSeparate() {
        store.learn(french, "sûr", nowMs = 100L)
        store.learn(french, "sûr", nowMs = 200L)
        store.learn(french, "sur", nowMs = 300L)

        // La mémoire normalisée reste commune.
        assertEquals(3, store.getUsage(french, "sûr")?.count)
        assertEquals(3, store.getUsage(french, "sur")?.count)

        // La mémoire exacte distingue désormais les deux graphies.
        assertEquals(2, store.getExactUsage(french, "sûr")?.count)
        assertEquals(1, store.getExactUsage(french, "sur")?.count)
    }

    @Test
    fun migrationFromV3ToV4_preservesNormalizedUsageAndCreatesExactUsageTable() {
        val context = RuntimeEnvironment.getApplication() as Context

        // Close the v4 database opened by setUp(), then recreate a genuine v3 database.
        store.close()
        context.deleteDatabase("word_usage.db")

        val dbPath = context.getDatabasePath("word_usage.db")
        dbPath.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            db.execSQL(
                """
            CREATE TABLE word_usage (
                locale TEXT NOT NULL,
                normalized_word TEXT NOT NULL,
                word TEXT NOT NULL,
                count INTEGER NOT NULL,
                last_used INTEGER NOT NULL,
                PRIMARY KEY (locale, normalized_word)
            )
            """.trimIndent()
            )

            db.execSQL(
                """
            INSERT INTO word_usage (
                locale,
                normalized_word,
                word,
                count,
                last_used
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
                arrayOf("fr", "sur", "sûr", 7, 1234L)
            )

            db.version = 3
        }

        // Opening WordUsageStore must trigger the real v3 -> v4 migration.
        store = WordUsageStore(context)

        val migratedUsage = store.getUsage(french, "sûr")

        assertEquals("sûr", migratedUsage?.word)
        assertEquals(7, migratedUsage?.count)
        assertEquals(1234L, migratedUsage?.lastUsed)

        // Historical normalized usage must not be fabricated as exact-form usage.
        assertNull(store.getExactUsage(french, "sûr"))
        assertNull(store.getExactUsage(french, "sur"))
    }

    @Test
    fun missingWord_becomesAvailableAfterLearning() {
        assertNull(store.getUsage(french, "monpetitloup"))
        assertNull(store.getUsage(french, "monpetitloup"))

        store.learn(french, "monpetitloup", nowMs = 100L)

        assertEquals(1, store.getUsage(french, "monpetitloup")?.count)

        store.learn(french, "monpetitloup", nowMs = 200L)

        assertEquals(2, store.getUsage(french, "monpetitloup")?.count)
    }

    @Test
    fun missingExactWord_becomesAvailableAfterLearning() {
        assertNull(store.getExactUsage(french, "monpetitloup"))
        assertNull(store.getExactUsage(french, "monpetitloup"))

        store.learn(french, "monpetitloup", nowMs = 100L)

        assertEquals(1, store.getExactUsage(french, "monpetitloup")?.count)
    }

    @Test
    fun learnedWord_becomesAbsentAfterDeletion() {
        assertNull(store.getUsage(french, "monpetitloup"))
        assertNull(store.getExactUsage(french, "monpetitloup"))

        store.learn(french, "monpetitloup", nowMs = 100L)

        assertEquals(1, store.getUsage(french, "monpetitloup")?.count)
        assertEquals(1, store.getExactUsage(french, "monpetitloup")?.count)

        assertEquals(1, store.delete(french, "monpetitloup"))

        assertNull(store.getUsage(french, "monpetitloup"))
        assertNull(store.getExactUsage(french, "monpetitloup"))
    }
}
