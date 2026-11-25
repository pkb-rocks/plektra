package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.clipboard.ClipboardHistoryManager

/**
 * The real keyboard chrome listener: bottom padding for visible and hidden caption bars, and the
 * display corners it hands to the rounded-corner layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w540dp-h600dp-xhdpi")
class ImeChromeInsetsTest {
    private val activity by lazy { Robolectric.buildActivity(android.app.Activity::class.java).setup().get() }

    @Before
    fun setUp() {
        DeviceSpecific.clearTestOverrides()
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        chrome(captionVisible = true)
        prefs().edit().clear().commit()
        DeviceSpecific.clearTestOverrides()
    }

    @Test
    fun touchDevicesThatHideTheCaptionKeepHalfOfTheGestureZone() {
        SettingsManager.setHideImeCaptionBar(activity, true)
        assertEquals(32, chrome(captionVisible = false).paddingBottom)
        assertEquals("a visible caption keeps the stable area", 96, chrome(captionVisible = true).paddingBottom)
    }

    @Test
    fun builtInKeyboardDevicesReachTheDisplayEdgeBelowAHiddenCaption() {
        titan2Elite()
        val chrome = chrome(captionVisible = false, radius = 60)
        assertEquals("no reserve below a hidden caption", 0, chrome.paddingBottom)
        assertEquals("the contour still follows the corners", 60 to 60, chrome.bottomCornerRadiiPx)
        assertEquals("a visible caption keeps the stable area", 96, chrome(captionVisible = true).paddingBottom)
    }

    @Test
    fun aStoredOptOutKeepsTheStableAreaOnBuiltInKeyboardDevices() {
        titan2Elite()
        SettingsManager.setHideImeCaptionBar(activity, false)
        assertEquals(96, chrome(captionVisible = false).paddingBottom)
    }

    @Test
    fun aBarHiddenByOthersKeepsTheStableAreaAfterOptOut() {
        SettingsManager.setHideImeCaptionBar(activity, false)
        assertEquals(96, chrome(captionVisible = false).paddingBottom)
    }

    @Test
    fun rectangularDisplaysGetNoArtificialCorners() {
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        assertNull(chrome(captionVisible = false).bottomCornerRadiiPx)
    }

    @Test
    fun reportedCornersWinAndAKnownPanelFillsMissingOnes() {
        val reported = chrome(captionVisible = false, radius = 60)
        assertEquals("on by default with reported corners", 60 to 60, reported.bottomCornerRadiiPx)

        titan2Elite()
        // xhdpi: 50 dp fallback = 100 px.
        assertEquals(100 to 100, chrome(captionVisible = false).bottomCornerRadiiPx)
        assertEquals(60 to 60, chrome(captionVisible = false, radius = 60).bottomCornerRadiiPx)
    }

    @Test
    fun cornersThatAppearLaterReachTheSameProcess() {
        assertNull("rectangular first configuration", chrome(captionVisible = false).bottomCornerRadiiPx)
        assertEquals(60 to 60, chrome(captionVisible = false, radius = 60).bottomCornerRadiiPx)
    }

    @Test
    fun aStoredOptOutDisablesTheContourOnRoundedDisplays() {
        titan2Elite()
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, false)
        assertNull(chrome(captionVisible = false, radius = 60).bottomCornerRadiiPx)
    }

    private fun chrome(captionVisible: Boolean, radius: Int = 0): ImeChromeLayout {
        val controller = StatusBarController(activity, clipboardHistoryManager = ClipboardHistoryManager(activity))
        val chrome = controller.getOrCreateLayout() as ImeChromeLayout
        val platform = WindowInsets.Builder()
            .setInsetsIgnoringVisibility(WindowInsets.Type.systemGestures(), android.graphics.Insets.of(0, 0, 0, 96))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), android.graphics.Insets.of(0, 0, 0, 64))
            .setInsets(WindowInsets.Type.captionBar(), android.graphics.Insets.of(0, 0, 0, if (captionVisible) 96 else 0))
            .setVisible(WindowInsets.Type.captionBar(), captionVisible)
            // The builder writes into a shared empty corner set; set both corners every time.
            .setRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT, corner(RoundedCorner.POSITION_BOTTOM_LEFT, radius, radius))
            .setRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, corner(RoundedCorner.POSITION_BOTTOM_RIGHT, radius, 1080 - radius))
            .build()
        // The display reports the same corners as the window, as on a device.
        TestDisplayCorners.setBottomRadius(android.view.Display.DEFAULT_DISPLAY, radius)
        ViewCompat.dispatchApplyWindowInsets(chrome, WindowInsetsCompat.toWindowInsetsCompat(platform, chrome))
        return chrome
    }

    private fun corner(position: Int, radius: Int, centerX: Int) =
        RoundedCorner(position, radius, centerX, 1200 - radius)

    private fun titan2Elite() = DeviceSpecific.setBuildFingerprintForTests(
        brand = "unihertz", manufacturer = "unihertz", model = "Titan 2 Elite",
        device = "titan2", product = "titan2", board = "G72BoardV1"
    )

    private fun prefs() = activity.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
}
