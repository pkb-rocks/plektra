package rocks.pkb.plektra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.ZonedDateTime

/** Refresh at local midnight only during an active input session; never wake the device.*/
class DailyThemeRotationScheduler(private val context: Context, private val refresh: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val midnight = Runnable { refreshAndSchedule() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (active) refreshAndSchedule() }
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun start() { active = true; refreshAndSchedule() }
    fun refreshAndSchedule() {
        handler.removeCallbacks(midnight)
        if (!active) return
        refresh()
        if (DailyThemeRotation.hasDailyRotation(context)) handler.postDelayed(midnight, delayToNextDay(ZonedDateTime.now()))
    }
    fun stop() { active = false; handler.removeCallbacks(midnight) }
    fun close() { stop(); context.unregisterReceiver(receiver) }
    companion object {
        fun delayToNextDay(now: ZonedDateTime): Long = Duration.between(now,
            now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis().coerceAtLeast(1L)
    }
}
