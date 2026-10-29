package rocks.pkb.plektra.statistics

import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TypingStatisticsStoreTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("typing_statistics_test", Context.MODE_PRIVATE)

    @Test fun countersAreLiveAndPersistedInOneBatchedWrite() {
        val prefs = preferences
        val store = TypingStatisticsStore(prefs)
        store.record(2, 0)
        store.record(0, 3)
        assertEquals(5L, store.snapshot.value.totalWords)
        assertFalse(prefs.contains(TypingStatisticsStore.MANUAL_KEY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2L, prefs.getLong(TypingStatisticsStore.MANUAL_KEY, -1))
        assertEquals(3L, prefs.getLong(TypingStatisticsStore.SUGGESTED_KEY, -1))
        assertEquals(store.snapshot.value, TypingStatisticsStore(prefs).snapshot.value)
    }

    @Test fun finishingSessionPersistsWithoutWaitingForTimer() {
        val store = TypingStatisticsStore(preferences)
        store.record(1, 2)
        store.flush()
        store.flush()
        assertEquals(TypingStatisticsSnapshot(1, 2), TypingStatisticsStore(preferences).snapshot.value)
    }

    @Test fun restoredPreferencesUpdateVisibleCounters() {
        val store = TypingStatisticsStore(preferences)
        preferences.edit().putLong(TypingStatisticsStore.MANUAL_KEY, 9)
            .putLong(TypingStatisticsStore.SUGGESTED_KEY, 2).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(11L, store.snapshot.value.totalWords)
        store.record(1, 0)
        store.flush()
        assertEquals(TypingStatisticsSnapshot(10, 2), TypingStatisticsStore(preferences).snapshot.value)
    }

    @Test fun emptyCountersStartAtZeroAndTotalIsAlwaysSumOfCategories() {
        val store = TypingStatisticsStore(preferences)
        assertEquals(0L, store.snapshot.value.totalWords)
        store.record(7, 4)
        val counts = store.snapshot.value
        assertEquals(counts.manualWords + counts.suggestedWords, counts.totalWords)
        store.flush()
    }
    @Test fun malformedAndNegativeStoredValuesCannotBreakTheStatisticsScreen() {
        preferences.edit().putString(TypingStatisticsStore.MANUAL_KEY, "invalid")
            .putLong(TypingStatisticsStore.SUGGESTED_KEY, -5).commit()
        val store = TypingStatisticsStore(preferences)
        assertEquals(TypingStatisticsSnapshot(), store.snapshot.value)
        store.record(1, 2)
        store.flush()
        assertEquals(TypingStatisticsSnapshot(1, 2), store.snapshot.value)
    }

    @Test fun oversizedStoredCountersDoNotOverflowIntoNegativeTotals() {
        preferences.edit().putLong(TypingStatisticsStore.MANUAL_KEY, Long.MAX_VALUE - 1)
            .putLong(TypingStatisticsStore.SUGGESTED_KEY, 20).commit()
        val store = TypingStatisticsStore(preferences)
        store.record(3, 1)
        store.flush()
        assertEquals(Long.MAX_VALUE, store.snapshot.value.manualWords)
        assertEquals(21L, store.snapshot.value.suggestedWords)
        assertEquals(Long.MAX_VALUE, store.snapshot.value.totalWords)
    }

}
