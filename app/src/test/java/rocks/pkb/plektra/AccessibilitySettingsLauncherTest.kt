package rocks.pkb.plektra

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilitySettingsLauncherTest {
    private val service = ComponentName("rocks.pkb.plektra", "rocks.pkb.plektra.inputmethod.Service")

    @Test
    fun fromAnActivityTheSettingsJoinItsTaskSoBackReturnsThere() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(AccessibilitySettingsLauncher.open(activity))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, started.action)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun aWrappedActivityContextCountsAsTheActivity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val wrapped = android.content.ContextWrapper(activity)
        assertEquals(0, AccessibilitySettingsLauncher.intent(wrapped).flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun withoutAnActivityANewTaskIsRequired() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(AccessibilitySettingsLauncher.intent(app).flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun theSecureSettingReportsTheSwitchWithoutWaitingForTheBinding() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(AccessibilitySettingsLauncher.isEnabled(app, service))
        Settings.Secure.putString(
            app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other/.A11y:${service.flattenToShortString()}"
        )
        assertTrue(AccessibilitySettingsLauncher.isEnabled(app, service))
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.other/.A11y")
        assertFalse(AccessibilitySettingsLauncher.isEnabled(app, service))
    }

    @Test
    fun aRecentlyDisabledServiceStaysDisabledEvenWhileTheManagerStillListsIt() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val resolve = android.content.pm.ResolveInfo().apply {
            serviceInfo = android.content.pm.ServiceInfo().apply {
                packageName = service.packageName
                name = service.className
            }
        }
        val info = android.accessibilityservice.AccessibilityServiceInfo()
        org.robolectric.util.ReflectionHelpers.setField(info, "mResolveInfo", resolve)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(info))
        assertEquals(1, manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).size)

        for (secureValue in listOf(null, "", "com.other/.A11y")) {
            Settings.Secure.putString(
                app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, secureValue
            )
            assertFalse(AccessibilitySettingsLauncher.isEnabled(app, service))
        }
    }

    @Test
    fun secureSettingParsingMatchesWholeComponentsOnly() {
        assertTrue(AccessibilitySettingsLauncher.enabledInSecureSettings(service.flattenToString(), service))
        assertFalse(AccessibilitySettingsLauncher.enabledInSecureSettings("rocks.pkb.plektra/.inputmethod.ServiceX", service))
        assertFalse(AccessibilitySettingsLauncher.enabledInSecureSettings("", service))
        assertFalse(AccessibilitySettingsLauncher.enabledInSecureSettings(null, service))
    }
}
