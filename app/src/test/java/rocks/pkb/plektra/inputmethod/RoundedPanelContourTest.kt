package rocks.pkb.plektra.inputmethod

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.T2eCornerCalibration
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The generated display surface and outer button against the quarter circle Android reports
 * (`RoundedCorner`): 100 px bottom corners on a 1080 × 1200 display, corner centre (100, 1100).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoundedPanelContourTest {
    private val context = RuntimeEnvironment.getApplication()

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
    fun anUnknownPanelStaysInsideTheReportedCircle() {
        val geometry = geometry()
        assertEquals(T2eCornerCalibration.NEUTRAL, geometry.calibration)
        assertInsideCircle("display surface", region(geometry.contentPath(WIDTH, HEIGHT)!!), requireFilledInside = true)
        assertInsideCircle("outer button", region(geometry.surfacePath(WIDTH, HEIGHT, BUTTON, 8f, true, false)),
            requireFilledInside = false)
    }

    @Test
    fun theKnownPanelKeepsItsMeasuredContour() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz", manufacturer = "unihertz", model = "Titan 2 Elite",
            device = "titan2", product = "titan2", board = "G72BoardV1"
        )
        val geometry = geometry()
        assertEquals(T2eCornerCalibration(), geometry.calibration)
        // The calibrated squircle reaches beyond the reported circle, as measured on this panel:
        // at y = 1168 the circle starts at x = 26.7, the contour at about x = 22.7.
        assertTrue(region(geometry.contentPath(WIDTH, HEIGHT)!!).contains(24, 1168))
    }

    @Test
    fun aSavedCalibrationAppliesOnAnyPanel() {
        val chosen = T2eCornerCalibration(size = 1.1f, offsetPx = 1f, squircle = 0.4f, shiftXPx = 0f, shiftYPx = 0f)
        chosen.save(context)
        assertEquals(chosen, geometry().calibration)

        SettingsManager.getPreferences(context).edit()
            .putString(T2eCornerCalibration.KEY, """{"size":1.1}""").commit()
        assertEquals("missing values come from the panel default",
            T2eCornerCalibration.NEUTRAL.copy(size = 1.1f), T2eCornerCalibration.read(context))
    }

    private fun geometry() = KeyboardBarGeometry(context).apply { radii = 100 to 100 }

    /** Pixel rows inside the corner: nothing left of the circle, and (optionally) all right of it. */
    private fun assertInsideCircle(label: String, region: Region, requireFilledInside: Boolean) {
        for (y in 1101 until 1199) {
            val boundary = circleX(y.toFloat())
            for (x in 0 until floor(boundary).toInt()) {
                assertFalse("$label outside the circle at ($x, $y), boundary $boundary", region.contains(x, y))
            }
        }
        if (requireFilledInside) for (y in 1101 until 1199) {
            val inside = ceil(circleX(y + 1f)).toInt() + 1
            assertTrue("$label fills the circle at ($inside, $y)", region.contains(inside, y))
        }
    }

    private fun circleX(y: Float): Float {
        val dy = (y - 1100f).coerceIn(0f, 100f)
        return 100f - sqrt(100f * 100f - dy * dy)
    }

    private fun region(path: Path) = Region().apply {
        setPath(path, Region(0, 0, WIDTH.toInt(), HEIGHT.toInt()))
    }

    private fun prefs() = SettingsManager.getPreferences(context)

    private companion object {
        const val WIDTH = 1080f
        const val HEIGHT = 1200f
        val BUTTON = RectF(0f, 1080f, 180f, 1200f)
    }
}
