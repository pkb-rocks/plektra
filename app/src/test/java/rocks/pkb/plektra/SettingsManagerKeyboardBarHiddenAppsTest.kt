package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsManagerKeyboardBarHiddenAppsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun noAppHidesTheBarByDefault() {
        assertEquals(emptySet<String>(), SettingsManager.getKeyboardBarHiddenPackages(context))
        assertFalse(SettingsManager.isKeyboardBarHiddenInApp(context, "com.termux"))
        assertFalse(SettingsManager.isKeyboardBarHiddenInApp(context, null))
        assertFalse(SettingsManager.isKeyboardBarHiddenInApp(context, ""))
    }

    @Test
    fun addAndRemoveRoundTripIndependentlyOfExactTyping() {
        SettingsManager.setKeyboardBarHiddenInApp(context, "com.termux", true)
        assertTrue(SettingsManager.isKeyboardBarHiddenInApp(context, "com.termux"))
        assertFalse(SettingsManager.isKeyboardBarHiddenInApp(context, "com.android.chrome"))
        assertFalse(SettingsManager.isRawModeApp(context, "com.termux"))

        SettingsManager.setKeyboardBarHiddenInApp(context, "com.termux", false)
        assertFalse(SettingsManager.isKeyboardBarHiddenInApp(context, "com.termux"))
    }

    @Test
    fun webApkAlsoHidesTheBarInItsHostBrowser() {
        SettingsManager.setKeyboardBarHiddenInApp(context, "org.chromium.webapk.x", true)
        assertTrue(SettingsManager.isKeyboardBarHiddenInApp(context, "com.android.chrome"))
    }
}
