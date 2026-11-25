package rocks.pkb.plektra.inputmethod

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView

/**
 * The touch targets at the lower edge of the IME window in screen coordinates, for device checks
 * that tap or start swipes at exact points (`adb shell input tap|swipe`). Only logged while IME
 * overlay debug logging is on (`adb logcat -s ImeHitBounds`).
 *
 * Rectangles are view or software-key hit bounds. Outer buttons on a rounded display are
 * additionally clipped by the display contour inside their rectangle.
 */
internal object ImeHitBoundsReport {
    const val TAG = "ImeHitBounds"

    data class Target(val name: String, val bounds: Rect)

    /** Clickable views and software keys whose bottom lies at most [bandPx] above [contentBottom]. */
    fun targets(root: View, contentBottom: Int, bandPx: Int): List<Target> {
        val found = mutableListOf<Target>()
        val location = IntArray(2)
        fun visit(view: View) {
            if (!view.isShown || view.width <= 0 || view.height <= 0) return
            view.getLocationOnScreen(location)
            if (view is AospKeyboardView) {
                view.visibleAccessibilitySnapshots().forEach { key ->
                    found += Target("key:${key.resourceName}",
                        Rect(key.bounds).apply { offset(location[0], location[1]) })
                }
            } else if (view.isClickable || view.isLongClickable) {
                found += Target(nameOf(view), Rect(location[0], location[1],
                    location[0] + view.width, location[1] + view.height))
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return found.filter { it.bounds.bottom >= contentBottom - bandPx }
            .sortedWith(compareByDescending<Target> { it.bounds.bottom }.thenBy { it.bounds.left })
    }

    /**
     * One line per state: window bottom, the lowest control edge Plektra intends, the top of
     * the mandatory gesture zone and the display corners, then the targets.
     */
    fun format(windowBottom: Int, contentBottom: Int, mandatoryGestureTop: Int,
               cornerRadii: Pair<Int, Int>?, targets: List<Target>): String =
        "windowBottom=$windowBottom contentBottom=$contentBottom mandatoryGestureTop=$mandatoryGestureTop " +
            "corners=${cornerRadii?.let { "${it.first},${it.second}" } ?: "none"} targets=" +
            targets.joinToString(" ") { "${it.name}@${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" }

    private fun nameOf(view: View): String {
        if (view.id != View.NO_ID) {
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()?.let { return "id:$it" }
        }
        view.contentDescription?.takeIf { it.isNotBlank() }?.let { return "desc:$it" }
        (view as? TextView)?.text?.takeIf { it.isNotBlank() }?.let { return "text:$it" }
        return view.javaClass.simpleName
    }
}
