package rocks.pkb.plektra.share

import android.content.ClipboardManager
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Enforces the deadline of clipboard copies and removes abandoned save targets even when
 * neither the keyboard nor the share screen runs again.
 */
class ShareCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { cleanUp(applicationContext, clipKnown = false) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "share-cleanup"

        /** Shortly after the newest copy expires; older copies have expired by then too. */
        fun schedule(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ShareCleanupWorker>()
                        .setInitialDelay(ShareClipboardCache.MAX_AGE_MILLIS + TimeUnit.MINUTES.toMillis(5), TimeUnit.MILLISECONDS)
                        .build()
                )
            }
        }

        /**
         * Removes leftovers of earlier processes. Without [clipKnown] an unreadable clipboard only
         * lets expired copies go: a background read returns nothing unless Plektra is the keyboard.
         */
        fun cleanUp(context: Context, clipKnown: Boolean) {
            ShareTargetJournal.recover(context)
            ShareStaging.recover(context)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            ShareClipboardCache.prune(context, clipKnown) {
                runCatching { clipboard?.primaryClip }.getOrNull()
            }
        }
    }
}
