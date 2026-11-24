package rocks.pkb.plektra.inputmethod.otp

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import rocks.pkb.plektra.PlektraApplication
import rocks.pkb.plektra.SettingsManager

/** Shared by the incoming components and the IME, in the application's main process. */
internal class OtpRuntime(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    val inbox = OtpInbox({ source ->
        SettingsManager.getOtpEnabled(context) && SettingsManager.getOtpSourceEnabled(context, source) &&
            hasAccess(context, source)
    }, SystemClock::elapsedRealtime)
    var onChanged: (() -> Unit)? = null
    private val expire = Runnable { refresh() }
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || SettingsManager.isOtpPreference(key)) refresh()
    }

    init {
        SettingsManager.getPreferences(context).registerOnSharedPreferenceChangeListener(settingsListener)
    }

    fun receive(source: OtpSource, readCode: () -> String?) {
        if (!SettingsManager.getOtpEnabled(context) || !SettingsManager.getOtpSourceEnabled(context, source) ||
            !hasAccess(context, source)) return
        val code = readCode() ?: return
        if (inbox.offer(source, code)) refresh()
    }

    fun refresh() {
        inbox.current()
        main.removeCallbacks(expire)
        inbox.nextExpiry()?.let { main.postDelayed(expire, (it - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
        onChanged?.invoke()
    }

    companion object {
        fun get(context: Context): OtpRuntime = (context.applicationContext as PlektraApplication).otpRuntime

        fun hasAccess(context: Context, source: OtpSource): Boolean = when (source) {
            OtpSource.SMS -> context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            OtpSource.NOTIFICATION -> context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, OtpNotificationListener::class.java))
            OtpSource.HELPER -> true
        }
    }
}
