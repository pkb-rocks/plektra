package rocks.pkb.plektra.core.suggestions

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WordUsageNegativeCacheTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun repeatedAbsentCandidatesUseOneDatabaseReadPerKeyAndCacheKind() {
        val store = spy(WordUsageStore(context))
        try {
            store.clearAll()
            clearInvocations(store)
            repeat(10) {
                repeat(200) { i ->
                    val candidate = "candidate" + ('a' + i / 26) + ('a' + i % 26)
                    assertNull(store.getUsage(Locale.FRENCH, candidate))
                    assertNull(store.getExactUsage(Locale.FRENCH, candidate))
                }
            }
            // 4,000 candidate lookups require 400 reads, rather than 4,000.
            verify(store, times(400)).readableDatabase
        } finally { store.clearAll(); store.close() }
    }

    @Test fun missesDoNotHideNewLearningOrRestoredDataAndRemainLanguageSpecific() {
        val store = WordUsageStore(context, asyncPersistence = true)
        try {
            store.clearAll()
            assertNull(store.getUsage(Locale.FRENCH, "plage"))
            assertNull(store.getExactUsage(Locale.FRENCH, "plage"))
            assertNull(store.getUsage(Locale.GERMAN, "plage"))
            store.learn(Locale.FRENCH, "plage", 100L)
            assertEquals(1, store.getUsage(Locale.FRENCH, "plage")!!.count)
            assertEquals(1, store.getExactUsage(Locale.FRENCH, "plage")!!.count)
            assertNull(store.getUsage(Locale.GERMAN, "plage"))
            store.flushPendingWrites()
            assertNull(store.getUsage(Locale.FRENCH, "mer"))
            WordUsageStore(context).use { it.learn(Locale.FRENCH, "mer", 200L) }
            store.invalidateCache()
            assertEquals(1, store.getUsage(Locale.FRENCH, "mer")!!.count)
            assertEquals(1, store.getExactUsage(Locale.FRENCH, "mer")!!.count)
        } finally { store.clearAll(); store.close() }
    }
}
