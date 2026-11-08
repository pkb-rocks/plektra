package rocks.pkb.plektra.training

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.NotificationHelper
import java.io.File
import java.util.concurrent.TimeUnit

/** When to remind nightly users to practise and share typing-training sessions. */
object TypingTrainingReminderPolicy {
    val INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(7)
    private val CHECK_SLACK_MS: Long = TimeUnit.HOURS.toMillis(6)

    fun isAvailable(): Boolean =
        BuildConfig.RELEASE_CHANNEL == "nightly" && BuildConfig.TYPING_TRAINING_UPLOAD_URL.isNotBlank()

    /**
     * At most weekly, and only when there is something to do: unshared sessions, or no
     * practice in the last week. The slack only applies to the reminder interval, so a check
     * shortly before it is due does not postpone it by a whole check period.
     */
    fun shouldRemind(lastReminderAtMs: Long, lastSessionAtMs: Long?, unsharedSessions: Int, nowMs: Long): Boolean {
        if (nowMs - lastReminderAtMs < INTERVAL_MS - CHECK_SLACK_MS) return false
        return unsharedSessions > 0 || lastSessionAtMs == null || nowMs - lastSessionAtMs >= INTERVAL_MS
    }
}

class TypingTrainingReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!TypingTrainingReminderPolicy.isAvailable() || !SettingsManager.getTypingTrainingReminderEnabled(context)) {
            return Result.success()
        }
        val dir = File(context.filesDir, "typing-training")
        val sessions = dir.listFiles { file -> file.extension == "json" && file.name.startsWith("session-") }.orEmpty()
        val shared = File(dir, "shared-ids.txt").takeIf { it.exists() }?.readLines()?.toSet().orEmpty()
        val unshared = sessions.count { file ->
            runCatching { TypingTrainingUploader.sessionId(file.readBytes()) !in shared }.getOrDefault(false)
        }
        val lastSessionAt = sessions.mapNotNull { it.name.removePrefix("session-").substringBefore('-').toLongOrNull() }.maxOrNull()
        val now = System.currentTimeMillis()
        if (TypingTrainingReminderPolicy.shouldRemind(SettingsManager.getTypingTrainingReminderLastAt(context), lastSessionAt, unshared, now)) {
            if (showNotification(context, unshared)) SettingsManager.setTypingTrainingReminderLastAt(context, now)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "plektra_typing_training_reminder"
        private const val CHANNEL_ID = "plektra_typing_training_channel"
        const val NOTIFICATION_ID = 3201

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!TypingTrainingReminderPolicy.isAvailable() || !SettingsManager.getTypingTrainingReminderEnabled(context)) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<TypingTrainingReminderWorker>(12, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun showNotification(context: Context, unshared: Int): Boolean {
            if (!NotificationHelper.hasNotificationPermission(context)) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.typing_training_reminder_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = context.getString(R.string.typing_training_reminder_channel_description) }
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, TypingTrainingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, TypingTrainingReminderReceiver::class.java).setAction(TypingTrainingReminderReceiver.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = if (unshared > 0) {
                context.resources.getQuantityString(R.plurals.typing_training_reminder_text_unshared, unshared, unshared)
            } else {
                context.getString(R.string.typing_training_reminder_text_practise)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.typing_training_reminder_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.typing_training_reminder_stop), stop)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            return true
        }
    }
}

/** Handles »Don't remind me again« from the reminder notification. */
class TypingTrainingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        SettingsManager.setTypingTrainingReminderEnabled(context, false)
        context.getSystemService(NotificationManager::class.java).cancel(TypingTrainingReminderWorker.NOTIFICATION_ID)
        TypingTrainingReminderWorker.schedule(context)
    }

    companion object {
        const val ACTION_STOP = "rocks.pkb.plektra.action.STOP_TYPING_TRAINING_REMINDER"
    }
}
