package rocks.pkb.plektra

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import rocks.pkb.plektra.inputmethod.otp.OtpRuntime
import rocks.pkb.plektra.inputmethod.otp.OtpSource

@Composable
internal fun OtpSettingsRows() {
    val context = LocalContext.current
    var paste by remember { mutableStateOf(SettingsManager.getClipboardPasteSuggestion(context)) }
    var master by remember { mutableStateOf(SettingsManager.getOtpEnabled(context)) }
    var sources by remember { mutableStateOf(OtpSource.entries.filter { SettingsManager.getOtpSourceEnabled(context, it) }.toSet()) }
    var smsAccess by remember { mutableStateOf(false) }
    var notificationAccess by remember { mutableStateOf(false) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsAccess = it }
    LifecycleResumeEffect(context) {
        smsAccess = OtpRuntime.hasAccess(context, OtpSource.SMS)
        notificationAccess = OtpRuntime.hasAccess(context, OtpSource.NOTIFICATION)
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val preferences = SettingsManager.getPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SettingsManager.KEY_CLIPBOARD_PASTE_SUGGESTION) {
                paste = SettingsManager.getClipboardPasteSuggestion(context)
            }
            if (key == null || SettingsManager.isOtpPreference(key)) {
                master = SettingsManager.getOtpEnabled(context)
                sources = OtpSource.entries.filter { SettingsManager.getOtpSourceEnabled(context, it) }.toSet()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun notificationSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
    KeyboardBarCheckRow(stringResource(R.string.clipboard_paste_suggestion), paste, "keyboard_bar.clipboard_paste_suggestion",
        stringResource(R.string.clipboard_paste_suggestion_summary)) { SettingsManager.setClipboardPasteSuggestion(context, it) }
    KeyboardBarCheckRow(stringResource(R.string.otp_title), master, "keyboard_bar.otp",
        stringResource(R.string.otp_summary)) { SettingsManager.setOtpEnabled(context, it) }
    if (!master) return
    // Only direct-distribution manifests declare the permission and receiver.
    if (BuildConfig.DISTRIBUTION_CHANNEL == "direct") {
        KeyboardBarCheckRow(stringResource(R.string.otp_sms), OtpSource.SMS in sources, "keyboard_bar.otp_sms",
            stringResource(if (smsAccess) R.string.otp_sms_granted else R.string.otp_sms_not_granted)) {
            SettingsManager.setOtpSourceEnabled(context, OtpSource.SMS, it)
            if (it && !smsAccess) smsPermission.launch(Manifest.permission.RECEIVE_SMS)
        }
        if (OtpSource.SMS in sources && !smsAccess) {
            TextButton(onClick = { smsPermission.launch(Manifest.permission.RECEIVE_SMS) }, Modifier.padding(start = 16.dp)) {
                Text(stringResource(R.string.otp_request_sms))
            }
        }
    }
    KeyboardBarCheckRow(stringResource(R.string.otp_notifications), OtpSource.NOTIFICATION in sources,
        "keyboard_bar.otp_notifications",
        stringResource(if (notificationAccess) R.string.otp_notifications_granted else R.string.otp_notifications_not_granted)) {
        SettingsManager.setOtpSourceEnabled(context, OtpSource.NOTIFICATION, it)
        if (it && !notificationAccess) notificationSettings()
    }
    if (OtpSource.NOTIFICATION in sources) {
        TextButton(onClick = ::notificationSettings, Modifier.padding(start = 16.dp)) {
            Text(stringResource(R.string.otp_notification_settings))
        }
    }
    KeyboardBarCheckRow(stringResource(R.string.otp_helper), OtpSource.HELPER in sources, "keyboard_bar.otp_helper",
        stringResource(R.string.otp_helper_summary, context.packageName)) {
        SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, it)
    }
    if (OtpSource.HELPER in sources) {
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(context.packageName, context.packageName))
        }, Modifier.padding(start = 16.dp)) { Text(stringResource(R.string.otp_copy_package)) }
    }
}
