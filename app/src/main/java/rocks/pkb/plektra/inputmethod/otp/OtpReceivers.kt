package rocks.pkb.plektra.inputmethod.otp

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

internal class OtpSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        OtpRuntime.get(context).receive(OtpSource.SMS) {
            val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.joinToString("") { it?.messageBody.orEmpty() }.orEmpty()
            OtpExtractor.extract(body)
        }
    }
}

internal class OtpNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        OtpRuntime.get(this).receive(OtpSource.NOTIFICATION) {
            val extras = sbn.notification.extras ?: return@receive null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
            // Prefer the expanded body; do not process old MessagingStyle history.
            val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.takeIf { it.isNotBlank() } ?: extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
            OtpExtractor.extract("$title\n$body")
        }
    }

    override fun onListenerDisconnected() {
        OtpRuntime.get(this).refresh()
    }
}

internal class OtpHelperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || intent.`package` != context.packageName) return
        OtpRuntime.get(context).receive(OtpSource.HELPER) {
            // setPackage routes but does not authenticate. Android 14 only shares sender identity
            // when the sender opts in (or the platform can disclose it). Never trust extras as identity.
            if (Build.VERSION.SDK_INT >= 34) {
                val sender = sentFromPackage
                if (!sender.isNullOrEmpty() && sender != HELPER_PACKAGE) return@receive null
            }
            // Ignore unexpected parcels from the exported compatibility receiver; never log payloads.
            runCatching { intent.getStringExtra("code") }.getOrNull()
        }
    }

    companion object {
        const val ACTION = "io.github.jd1378.otphelper.actions.code_detected"
        private const val HELPER_PACKAGE = "io.github.jd1378.otphelper"
    }
}
