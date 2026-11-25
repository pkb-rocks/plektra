package rocks.pkb.plektra

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

/**
 * Opens Android's accessibility settings so that Back returns to the Plektra screen that opened
 * them.
 *
 * From an activity the settings page joins Plektra's task. With FLAG_ACTIVITY_NEW_TASK it would
 * land in the Settings task instead, and Back would walk through whatever that task held before.
 * Only a context without an activity needs a new task.
 */
internal object AccessibilitySettingsLauncher {
    fun intent(context: Context): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context): Boolean = try {
        context.startActivity(intent(context))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /**
     * Whether the user enabled [service]. The secure setting changes synchronously with the switch
     * in Settings, so a screen that rereads it on resume shows the new state right away; the
     * service list of [AccessibilityManager] follows only once Android has bound the service.
     */
    fun isEnabled(context: Context, service: ComponentName): Boolean = try {
        enabledInSecureSettings(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            service
        )
    } catch (_: SecurityException) {
        enabledInAccessibilityManager(context, service)
    }

    internal fun enabledInSecureSettings(enabledServices: String?, service: ComponentName): Boolean {
        if (enabledServices.isNullOrEmpty()) return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        return splitter.any { entry -> ComponentName.unflattenFromString(entry) == service }
    }

    private fun enabledInAccessibilityManager(context: Context, service: ComponentName): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return manager
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == service
            }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
