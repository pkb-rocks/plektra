package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.SettingsManager

/** The caption bar choice follows the IME window, the setting and the device default, not the profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeCaptionBarServiceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var serviceController: ServiceController<PhysicalKeyboardInputMethodService>
    private lateinit var service: PhysicalKeyboardInputMethodService

    @Before
    fun setUp() {
        DeviceSpecific.clearTestOverrides()
        context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ImeCaptionBarController.captionBarVisibleForTests = true
        serviceController = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = serviceController.get()
    }

    @After
    fun tearDown() {
        serviceController.destroy()
        ImeCaptionBarController.captionBarVisibleForTests = null
        DeviceSpecific.clearTestOverrides()
    }

    @Test
    fun theChoiceWaitsUntilTheWindowIsAttached() {
        SettingsManager.setHideImeCaptionBar(service, true)
        service.onWindowShown()
        assertFalse("the bar state is unknown before the window is attached", hiddenByPlektra())
        attachWindow()
        assertTrue(hiddenByPlektra())
    }

    @Test
    fun aBarHiddenBeforeIsNeitherClaimedNorShownOnOptOut() {
        ImeCaptionBarController.captionBarVisibleForTests = false
        attachWindow()
        service.onWindowShown()
        assertFalse(hiddenByPlektra())
        SettingsManager.setHideImeCaptionBar(service, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(hiddenByPlektra())
    }

    @Test
    fun aTouchDeviceKeepsTheBarUntilTheUserHidesIt() {
        attachWindow()
        service.onWindowShown()
        assertFalse(hiddenByPlektra())

        SettingsManager.setHideImeCaptionBar(service, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(hiddenByPlektra())

        SettingsManager.setHideImeCaptionBar(service, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(hiddenByPlektra())
    }

    @Test
    fun aBuiltInKeyboardDeviceHidesTheBarByDefaultAndRestoresItOnOptOut() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz", manufacturer = "unihertz", model = "Titan 2 Elite",
            device = "titan2", product = "titan2", board = "G72BoardV1"
        )
        attachWindow()
        service.onWindowShown()
        assertTrue(hiddenByPlektra())

        SettingsManager.setHideImeCaptionBar(service, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(hiddenByPlektra())
    }

    @Test
    fun profileChangesKeepTheChoice() {
        attachWindow()
        SettingsManager.setPhysicalKeyboardProfileOverride(service, "key2")
        shadowOf(Looper.getMainLooper()).idle()
        service.onWindowShown()
        assertFalse("a profile choice does not make a touch device a keyboard device", hiddenByPlektra())

        SettingsManager.setHideImeCaptionBar(service, true)
        SettingsManager.setPhysicalKeyboardProfileOverride(service, "clicks_power")
        shadowOf(Looper.getMainLooper()).idle()
        service.onWindowShown()
        assertTrue("a stored choice applies to every profile", hiddenByPlektra())
    }

    @Test
    fun aSwitcherRequestBeforeTheWindowShowsSurvivesShowsUntilWithdrawn() {
        service.onCustomImeSwitcherButtonRequestedVisible(true)
        attachWindow()
        service.onWindowShown()
        service.onWindowHidden()
        service.onWindowShown()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Android does not repeat the request per show", service.imeSwitcherRequested)
        service.onCustomImeSwitcherButtonRequestedVisible(false)
        assertFalse(service.imeSwitcherRequested)
    }

    @Test
    fun aRequestBeforeOnCreateIsKeptWithoutTouchingUnbuiltControllers() {
        val early = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
        early.get().onCustomImeSwitcherButtonRequestedVisible(true)
        early.create()
        assertTrue(early.get().imeSwitcherRequested)
        early.destroy()
    }

    @Test
    fun theSnapshotShowsTheSwitcherByOnePolicyForRequestAndPreference() {
        attachWindow()
        service.onWindowShown()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("menu only by default", renderedSwitcher())

        SettingsManager.setImeSwitcherInBar(service, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the preference reaches the surfaces at once", renderedSwitcher())

        SettingsManager.setImeSwitcherInBar(service, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(renderedSwitcher())

        service.onCustomImeSwitcherButtonRequestedVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Android's request wins over the menu preference", renderedSwitcher())
    }

    private fun renderedSwitcher(): Boolean {
        val field = PhysicalKeyboardInputMethodService::class.java.getDeclaredField("lastRenderedStatusSnapshot")
        field.isAccessible = true
        return (field.get(service) as? StatusBarController.StatusSnapshot)?.imeSwitcherShown == true
    }

    @Test
    fun withoutAnAndroidRequestThereIsNoSwitcher() {
        attachWindow()
        service.onWindowShown()
        assertFalse(service.imeSwitcherRequested)
    }

    /** Attaches the IME window's decor as showing the input method does. */
    private fun attachWindow() {
        val imeWindow = service.window
        ReflectionHelpers.callInstanceMethod<Any>(
            imeWindow, "setToken", ReflectionHelpers.ClassParameter.from(IBinder::class.java, Binder())
        )
        imeWindow.show()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun hiddenByPlektra(): Boolean {
        val field = PhysicalKeyboardInputMethodService::class.java.getDeclaredField("imeCaptionBarController")
        field.isAccessible = true
        return (field.get(service) as ImeCaptionBarController).hiddenByPlektra
    }
}
