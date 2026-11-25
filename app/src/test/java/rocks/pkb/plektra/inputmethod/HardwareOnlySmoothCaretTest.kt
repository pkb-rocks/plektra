package rocks.pkb.plektra.inputmethod

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Matrix
import android.os.Looper
import android.os.UserManager
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import rocks.pkb.plektra.SettingLinkRegistry
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.PreferenceValue
import rocks.pkb.plektra.backup.PreferenceValueType
import rocks.pkb.plektra.backup.PreferencesBackupHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HardwareOnlySmoothCaretTest {
    private fun findField(instance: Any, name: String): Field {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun field(instance: Any, name: String): Any? = findField(instance, name).get(instance)

    private fun set(instance: Any, name: String, value: Any) {
        findField(instance, name).set(instance, value)
    }

    @Test
    fun newKeySurvivesBackupAndLockedSnapshotAndSearch() {
        val context = RuntimeEnvironment.getApplication()
        DirectBootStorage.resetForTesting()
        val userManager = context.getSystemService(UserManager::class.java)
        Shadows.shadowOf(userManager).setUserUnlocked(true)
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().clear().commit()
        assertFalse(SettingsManager.getHardwareOnlyMode(context))
        SettingsManager.setHardwareOnlyMode(context, true)
        val export = PreferencesBackupHelper::class.java.getDeclaredMethod(
            "buildPreferencesJson", String::class.java, SharedPreferences::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val json = export.invoke(PreferencesBackupHelper, "plektra_prefs", prefs, false) as JSONObject
        assertTrue(json.getJSONObject("entries").getJSONObject("hardware_only_mode").getBoolean("value"))
        SettingsManager.setHardwareOnlyMode(context, false)
        val entries = mapOf("hardware_only_mode" to PreferenceValue(PreferenceValueType.BOOLEAN, true))
        PreferencesBackupHelper.restorePreferences(context, mapOf("plektra_prefs" to entries), emptySet(), false)
        assertTrue(SettingsManager.getHardwareOnlyMode(context))
        assertTrue(DirectBootKeyboardConfig.refreshNow(context))
        assertEquals(true, DirectBootKeyboardConfig(context).read()["hardware_only_mode"])
        DirectBootStorage.resetForTesting()
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        val locked = DirectBootStorageContext(context)
        assertTrue(SettingsManager.getHardwareOnlyMode(locked))
        assertTrue(SettingsManager.arePlektraSurfacesBlocked(locked, "com.example.editor"))
        Shadows.shadowOf(userManager).setUserUnlocked(true)
        DirectBootStorage.resetForTesting()
        for (language in listOf("en", "de")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = context.createConfigurationContext(configuration)
            val entry = SettingLinkRegistry.byId("status_bar.hardware_only")
            assertNotNull(entry)
            val title = localized.getString(requireNotNull(entry).titleRes)
            assertTrue(SettingLinkRegistry.search(localized, title).any { it.id == entry.id })
        }
    }

    @Test
    fun smoothCaretDetachesThroughGlobalVetoAndRestoresNativeCursor() {
        val harness = PhysicalKeyboardInputMethodServiceDeviceBehaviorTest()
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        harness.setUp()
        try {
            val service = field(harness, "service") as PhysicalKeyboardInputMethodService
            val original = field(harness, "inputConnection") as InputConnection
            val consumes = mutableListOf<Boolean>()
            val connection = Proxy.newProxyInstance(
                InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                if (method.name == "setImeConsumesInput") {
                    consumes.add(args!![0] as Boolean)
                    true
                } else {
                    method.invoke(original, *(args ?: emptyArray()))
                }
            } as InputConnection
            set(service, "mInputConnection", connection)
            set(service, "mStartedInputConnection", connection)
            SettingsManager.setExperimentalSmoothCaret(context, true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, field(service, "smoothCaretEditorHidden"))
            val caret = CursorAnchorInfo.Builder().setMatrix(Matrix())
                .setInsertionMarkerLocation(100f, 100f, 115f, 120f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
                .build()
            service.onUpdateCursorAnchorInfo(caret)
            val overlay = requireNotNull(field(service, "smoothCaretOverlayController"))
            val view = field(overlay, "caretView") as View
            assertEquals(true, field(overlay, "attached"))
            assertEquals(1f, view.alpha, 0f)
            SettingsManager.setHardwareOnlyMode(context, true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, field(service, "plektraSurfacesBlocked"))
            assertEquals(false, field(service, "smoothCaretEditorHidden"))
            assertEquals(false, field(overlay, "attached"))
            assertTrue(consumes.contains(false))
            service.onUpdateCursorAnchorInfo(caret)
            assertEquals(false, field(overlay, "attached"))
            SettingsManager.setHardwareOnlyMode(context, false)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, field(service, "smoothCaretEditorHidden"))
            service.onUpdateCursorAnchorInfo(caret)
            assertEquals(true, field(overlay, "attached"))
            assertEquals(1f, view.alpha, 0f)
        } finally {
            harness.tearDown()
            ShadowSettings.setCanDrawOverlays(false)
        }
    }
}
