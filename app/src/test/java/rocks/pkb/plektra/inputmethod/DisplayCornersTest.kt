package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.T2eCornerCalibration

/** Uses Robolectric's real display provider; only the corner data of a display is set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplayCornersTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        DeviceSpecific.clearTestOverrides()
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        DeviceSpecific.clearTestOverrides()
    }

    @Test
    fun reportedRadiiWinAndOnlyKnownPanelsFillMissingCorners() {
        assertEquals(60 to 60, DisplayCorners.resolveBottomRadii(60, 60, knownPanel = true, fallbackPx = 100))
        assertEquals(100 to 60, DisplayCorners.resolveBottomRadii(0, 60, knownPanel = true, fallbackPx = 100))
        assertEquals(100 to 100, DisplayCorners.resolveBottomRadii(0, 0, knownPanel = true, fallbackPx = 100))
        assertEquals("no artificial corner elsewhere", 0 to 60,
            DisplayCorners.resolveBottomRadii(0, 60, knownPanel = false, fallbackPx = 100))
        assertNull("rectangular", DisplayCorners.resolveBottomRadii(0, 0, knownPanel = false, fallbackPx = 100))
    }

    @Test
    fun rectangularUnknownDisplaysKeepTheFlatLayout() {
        assertFalse(DisplayCorners.hasKnownRoundedPanel())
        assertFalse(DisplayCorners.hasRoundedDisplay(context))
        assertFalse(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
    }

    @Test
    fun aManualKeyboardProfileDoesNotMakeADisplayRound() {
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "titan2elite_qwerty")
        assertFalse(DisplayCorners.hasKnownRoundedPanel())
        assertFalse(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
    }

    @Test
    fun theKnownPanelIsOnlyTheBuiltInDisplay() {
        useKnownPanelDevice()
        assertTrue(DisplayCorners.hasRoundedDisplay(context))
        assertTrue(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
        assertEquals(T2eCornerCalibration(), T2eCornerCalibration.defaults(context))

        val externalId = ShadowDisplayManager.addDisplay("w600dp-h400dp")
        val external = context.createDisplayContext(
            context.getSystemService(DisplayManager::class.java).getDisplay(externalId)
        )
        assertFalse(DisplayCorners.hasKnownRoundedPanel(external))
        assertFalse("a rectangular external display stays flat", DisplayCorners.hasRoundedDisplay(external))
        assertFalse(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(external))
        assertEquals(T2eCornerCalibration.NEUTRAL, T2eCornerCalibration.defaults(external))

        TestDisplayCorners.setBottomRadius(externalId, 40)
        assertTrue("a rounded external display follows its own report", DisplayCorners.hasRoundedDisplay(external))
        assertEquals("but without the panel's contour", T2eCornerCalibration.NEUTRAL, T2eCornerCalibration.defaults(external))
    }

    @Test
    fun aDisplayThatBecomesRoundedIsFollowedWithinTheSameProcess() {
        assertFalse("first configuration is rectangular", SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
        TestDisplayCorners.setBottomRadius(Display.DEFAULT_DISPLAY, 60)
        assertTrue(DisplayCorners.hasRoundedDisplay(context))
        assertTrue(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
        TestDisplayCorners.setBottomRadius(Display.DEFAULT_DISPLAY, 0)
        assertFalse("and back", SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
    }

    @Test
    fun theDisplayOfTheContextDecides() {
        val secondId = ShadowDisplayManager.addDisplay("w400dp-h600dp")
        TestDisplayCorners.setBottomRadius(secondId, 48)
        val second = context.getSystemService(DisplayManager::class.java).getDisplay(secondId)
        val onSecond = context.createDisplayContext(second)
        assertTrue(DisplayCorners.hasRoundedDisplay(onSecond))
        assertTrue(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(onSecond))
        assertFalse("the rectangular default display is unaffected", DisplayCorners.hasRoundedDisplay(context))

        TestDisplayCorners.setBottomRadius(Display.DEFAULT_DISPLAY, 60)
        TestDisplayCorners.setBottomRadius(secondId, 0)
        assertTrue(DisplayCorners.hasRoundedDisplay(context))
        assertFalse("a rectangular second display stays flat", DisplayCorners.hasRoundedDisplay(onSecond))
    }

    @Test
    fun reportedCornersEnableTheLayoutUntilTheUserChoosesOtherwise() {
        TestDisplayCorners.setBottomRadius(Display.DEFAULT_DISPLAY, 60)
        assertTrue(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
        assertFalse("the default is not persisted",
            prefs().contains(SettingsManager.KEY_TITAN2_ELITE_ROUNDED_CORNER_INSETS))
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, false)
        assertFalse(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))

        TestDisplayCorners.setBottomRadius(Display.DEFAULT_DISPLAY, 0)
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, true)
        assertTrue("a stored choice wins on any display", SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
    }

    @Test
    fun theOneTimeTitanMigrationLeavesOtherRoundedDevicesAlone() {
        TestDisplayCorners.setBottomRadius(Display.DEFAULT_DISPLAY, 60)
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(context, false)
        SettingsManager.enforceTitan2EliteRoundedCornersOnce(context)
        assertFalse(SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context))
    }

    private fun useKnownPanelDevice() = DeviceSpecific.setBuildFingerprintForTests(
        brand = "unihertz", manufacturer = "unihertz", model = "Titan 2 Elite",
        device = "titan2", product = "titan2", board = "G72BoardV1"
    )

    private fun prefs() = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
}
