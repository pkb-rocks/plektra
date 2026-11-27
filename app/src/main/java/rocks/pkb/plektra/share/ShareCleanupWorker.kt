package rocks.pkb.plektra.share

import android.content.ClipboardManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Removes what a share left behind when neither the keyboard nor the share screen runs again:
 * targets of a save that a killed process abandoned, staging copies and expired clipboard copies.
 */
class ShareCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result =
        if (runOnce(applicationContext)) Result.retry() else Result.success()

    enum class Run(val workName: String, val delayMillis: Long, val policy: ExistingWorkPolicy) {
        /**
         * 15 minutes after the latest save that may be interrupted. REPLACE also enqueues a
         * fresh run when one is already running, which KEEP would drop, and never chains runs.
         */
        Recovery("share-recovery", TimeUnit.MINUTES.toMillis(15), ExistingWorkPolicy.REPLACE),

        /** Shortly after the newest clipboard copy expires; older copies expired before. */
        ClipboardExpiry(
            "share-clipboard-expiry",
            ShareClipboardCache.MAX_AGE_MILLIS + TimeUnit.MINUTES.toMillis(5),
            ExistingWorkPolicy.REPLACE
        )
    }

    companion object {
        /**
         * Cleans up once and tells whether another run is needed: records stay when a provider
         * is unavailable, a delete failed or a save is still running.
         */
        internal fun runOnce(context: Context): Boolean {
            runCatching { cleanUp(context) }
            return ShareTargetJournal.hasRecords(context)
        }

        /** Replaceable in tests, which have no WorkManager. */
        internal var scheduler: (Context, Run) -> Unit = { context, run ->
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    run.workName,
                    run.policy,
                    OneTimeWorkRequestBuilder<ShareCleanupWorker>()
                        .setInitialDelay(run.delayMillis, TimeUnit.MILLISECONDS)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                        .build()
                )
            }
        }

        /** Called before a save writes anything, so a crash during the write is cleaned up too. */
        fun scheduleRecovery(context: Context) = scheduler(context, Run.Recovery)

        /** Called before a clipboard copy is written. */
        fun scheduleClipboardExpiry(context: Context) = scheduler(context, Run.ClipboardExpiry)

        /**
         * Removes leftovers of earlier processes. A clipboard that cannot be read, which is the
         * normal case for a background job, only lets expired copies go.
         */
        fun cleanUp(context: Context) {
            ShareTargetJournal.recover(context)
            ShareStaging.recover(context)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            ShareClipboardCache.prune(context) { runCatching { clipboard?.primaryClip }.getOrNull() }
        }
    }
}
