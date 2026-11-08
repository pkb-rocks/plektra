package rocks.pkb.plektra.core.suggestions

import android.content.Context

/** One writer/cache per app data directory, shared by settings and successive IME instances. */
internal object PersonalWordLearning {
    private val stores = mutableMapOf<String, WordUsageStore>()

    @Synchronized fun store(context: Context): WordUsageStore {
        val app = context.applicationContext
        val key = app.getDatabasePath("word_usage.db").absolutePath
        return stores.getOrPut(key) { WordUsageStore(app, asyncPersistence = true) }
    }

    @Synchronized internal fun closeForTests(context: Context) {
        stores.remove(context.applicationContext.getDatabasePath("word_usage.db").absolutePath)?.close()
    }
}
