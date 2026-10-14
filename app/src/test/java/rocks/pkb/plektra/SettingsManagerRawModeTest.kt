package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsManagerRawModeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun noAppIsRawByDefault() {
        assertEquals(emptySet<String>(), SettingsManager.getRawModePackages(context))
        assertFalse(SettingsManager.isRawModeApp(context, "com.termux"))
        assertFalse(SettingsManager.isRawModeApp(context, null))
        assertFalse(SettingsManager.isRawModeApp(context, ""))
    }

    @Test
    fun addAndRemoveRoundTrip() {
        SettingsManager.setRawModeApp(context, "com.termux", true)
        assertTrue(SettingsManager.isRawModeApp(context, "com.termux"))
        assertFalse(SettingsManager.isRawModeApp(context, "com.android.chrome"))

        SettingsManager.setRawModeApp(context, "com.termux", false)
        assertFalse(SettingsManager.isRawModeApp(context, "com.termux"))
    }

    @Test
    fun webApkRawModeAlsoCoversItsHostBrowser() {
        SettingsManager.setRawModeApp(context, "org.chromium.webapk.x", true)
        assertTrue(SettingsManager.isRawModeApp(context, "org.chromium.webapk.x"))
        assertTrue(SettingsManager.isRawModeApp(context, "com.android.chrome"))
    }
}
