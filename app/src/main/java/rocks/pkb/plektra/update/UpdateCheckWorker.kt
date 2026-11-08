package rocks.pkb.plektra.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.NotificationHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Periodic worker that checks for new app updates using the existing
 * GitHub-based update checker and shows a notification when a new
 * version is available.
 */
class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val context = applicationContext
        if (!shouldUseGithubUpdateChecks(context)) {
            return Result.success()
        }

        // Use a latch to bridge the async callback-based API with the
        // synchronous Worker API.
        val resultRef = AtomicReference<UpdateCheckResult?>()
        val completedRef = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        checkForUpdateNotices(
            context = context,
            releaseChannel = BuildConfig.RELEASE_CHANNEL,
            // Respect releases dismissed by the user via the dialog.
            ignoreDismissedReleases = true
        ) { result ->
            resultRef.set(result)
            completedRef.set(true)
            latch.countDown()
        }

        // Wait for the network response (or timeout).
        val awaitSuccess = latch.await(30, TimeUnit.SECONDS)
        val result = resultRef.get()
        if (!awaitSuccess || !completedRef.get() || result == null) {
            // Network error or timeout: ask WorkManager to retry later.
            return Result.retry()
        }

        if (result.hasAnnouncement && result.displayName != null && result.releaseTag != null) {
            val (lastTag, lastAt) = SettingsManager.getUpdateReminderLastNotified(context)
            val now = System.currentTimeMillis()
            if (UpdateReminderPolicy.shouldNotify(SettingsManager.getUpdateReminderInterval(context), result.releaseTag, lastTag, lastAt, now)) {
                val posted = NotificationHelper.showUpdateAvailableNotification(
                    context = context,
                    displayName = result.displayName,
                    releasePageUrl = result.releasePageUrl,
                    isNightlyUpdate = result.isNightlyUpdate,
                    isPlektraStableUpdate = result.isPlektraStableUpdate
                )
                // Without notification permission the release stays unannounced for the next check.
                if (posted) SettingsManager.setUpdateReminderLastNotified(context, result.releaseTag, now)
            }
        }

        if (!result.successful) {
            return Result.retry()
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "plektra_update_check"

        /**
         * Schedules a periodic background update check every 6 hours, so that a release is
         * announced soon after it appears; [UpdateReminderPolicy] decides whether to notify.
         */
        fun schedule(context: Context) {
            if (!shouldUseGithubUpdateChecks(context)) {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                // UPDATE replaces the former 24-hour period of existing installations.
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
