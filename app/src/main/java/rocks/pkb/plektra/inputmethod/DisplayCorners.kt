package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.RoundedCorner

/**
 * Which displays have rounded bottom corners, and how large they are.
 *
 * Android reports the corners from API 31. On a known panel, the Titan 2 Elite's, a calibrated
 * fallback replaces a corner Android reports without a radius, and
 * [rocks.pkb.plektra.T2eCornerCalibration.defaults] supplies the measured contour. Every other
 * device follows only Android's report: a rectangular display never receives artificial corners,
 * and a rounded one gets a plain quarter circle.
 */
internal object DisplayCorners {
    const val KNOWN_PANEL_FALLBACK_RADIUS_DP = 50f

    /**
     * Detections of a built-in display that is the known panel, one entry per device family.
     * A device build that shares the panel adds its own detection here; the contour, the corner
     * fallback and the defaults then follow without further changes. Evaluated on every query,
     * so a detection may depend on hardware that appears after the IME started.
     */
    private val knownPanelDetections: List<() -> Boolean> = listOf(
        DeviceSpecific::isTitan2EliteDevice
    )

    /** Whether this device's built-in display is the known panel. */
    fun hasKnownRoundedPanel(): Boolean = knownPanelDetections.any { it() }

    /**
     * The known panel as the display [context] shows on: only the built-in display is that
     * panel; an external or virtual display is not.
     */
    fun hasKnownRoundedPanel(context: Context): Boolean = hasKnownRoundedPanel() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            (displayOf(context)?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY)

    /**
     * Whether the rounded-corner layout fits the display [context] shows on: a known rounded
     * panel or a display that currently reports rounded bottom corners.
     *
     * Nothing is cached. The active panel can change within one process (another display, a
     * fold, a late-enumerated built-in keyboard); Android caches both queries itself.
     */
    fun hasRoundedDisplay(context: Context): Boolean =
        hasKnownRoundedPanel(context) || reportsRoundedBottomCorners(context)

    /**
     * The (left, right) bottom radii in px, or null when neither corner is rounded. A missing
     * or zero radius falls back only on a known rounded panel.
     */
    fun resolveBottomRadii(reportedLeft: Int, reportedRight: Int, knownPanel: Boolean, fallbackPx: Int): Pair<Int, Int>? {
        fun side(reported: Int) = reported.takeIf { it > 0 } ?: if (knownPanel) fallbackPx else 0
        val left = side(reportedLeft)
        val right = side(reportedRight)
        return if (left > 0 || right > 0) Pair(left, right) else null
    }

    private fun reportsRoundedBottomCorners(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val display = displayOf(context) ?: return false
        return listOf(RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            .any { (display.getRoundedCorner(it)?.radius ?: 0) > 0 }
    }

    /**
     * The display of a window, activity or display context. Only a context without a display,
     * such as the application, falls back to the default display.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun displayOf(context: Context): Display? =
        runCatching { context.display }.getOrNull()
            ?: runCatching {
                context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            }.getOrNull()
}
