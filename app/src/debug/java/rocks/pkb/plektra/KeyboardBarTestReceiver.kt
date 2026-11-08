package rocks.pkb.plektra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

/** Shell-only debug control for reversible menu-edge checks with the real IME. */
class KeyboardBarTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("clearSoftwareOverride", false)) {
            SoftwareKeyboardModeActions.clearTemporaryMode(context)
            resultData = "Temporary software-keyboard mode cleared"
            return
        }
        if (intent.getBooleanExtra("inspect", false)) {
            val lines = mutableListOf<String>()
            fun inspect(view: android.view.View) {
                if (view is rocks.pkb.plektra.inputmethod.ImeContentLayout ||
                    view is rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView) {
                    lines += "${view.javaClass.simpleName}: visible=${view.visibility}, alpha=${view.alpha}, size=${view.width}x${view.height}, top=${view.top}, params=${view.layoutParams?.height}"
                }
                if (view.visibility != android.view.View.VISIBLE) return
                if (view is rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView && view.isShown) {
                    lines += "SoftwareModifiers: shift=${view.shifted}, shiftLocked=${view.shiftLocked}, ctrlOneShot=${view.ctrlOneShot}, ctrlLocked=${view.ctrlLocked}, ctrlPressed=${view.ctrlPressed}"
                    val position = IntArray(2).also(view::getLocationOnScreen)
                    view.visibleAccessibilitySnapshots().forEach { key ->
                        lines += "SoftwareKey: id=${key.resourceName}, label=${key.label}, screen=${position[0] + key.bounds.centerX()},${position[1] + key.bounds.centerY()}"
                    }
                }
                if (view is rocks.pkb.plektra.inputmethod.ImeChromeLayout) {
                    lines += "chrome=${view.width}x${view.height}, integrated=${view.contourIntegratedIndicators}, indicator=${view.indicatorView?.height}, rowTop=${rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.bottomRowTop(view)}, rowRadius=${rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry.bottomRowRadius(view)}, software=${view.softwareKeyboardModeActive}"
                }
                if (view is android.widget.ImageView && view.isClickable && view.isShown) {
                    val position = IntArray(2).also(view::getLocationOnScreen)
                    lines += "MenuControl: screen=${position[0]},${position[1]}, size=${view.width}x${view.height}, clickable=${view.isClickable}"
                }
                val background = view.background
                if (background is rocks.pkb.plektra.inputmethod.statusbar.CurvedCornerButtonDrawable) {
                    val position = IntArray(2).also(view::getLocationInWindow)
                    lines += "${view.javaClass.simpleName}: x=${position[0]}, y=${position[1]}, w=${view.width}, h=${view.height}, left=${background.leftEdge}, right=${background.rightEdge}, center=${background.visibleCenter()}"
                }
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::inspect)
            resultData = lines.joinToString(" | ")
            return
        }
        val backup = context.getSharedPreferences("keyboard_bar_test_backup", Context.MODE_PRIVATE)
        if (intent.getBooleanExtra("restore", false)) {
            val left = backup.getString("left", null)
            val right = backup.getString("right", null)
            if (left == null || right == null) {
                resultCode = 1
                resultData = "No saved menu configuration"
                return
            }
            SettingsManager.setStatusBarSlotsLeft(context, decode(left))
            SettingsManager.setStatusBarSlotsRight(context, decode(right))
            backup.edit().clear().commit()
            resultData = "Menu configuration restored"
            return
        }
        val available = SettingsManager.getAvailableStatusBarButtons().toSet()
        fun slots(name: String): List<String>? = intent.getStringExtra(name)?.split(',')?.also { values ->
            require(values.isNotEmpty() && values.all { it in available }) { "Invalid $name menu buttons" }
        }
        try {
            val left = slots("left")
            val right = slots("right")
            require(left != null || right != null) { "Specify left or right menu buttons" }
            if (!backup.contains("left")) {
                backup.edit()
                    .putString("left", JSONArray(SettingsManager.getStatusBarSlotsLeft(context)).toString())
                    .putString("right", JSONArray(SettingsManager.getStatusBarSlotsRight(context)).toString())
                    .commit()
            }
            left?.let { SettingsManager.setStatusBarSlotsLeft(context, it) }
            right?.let { SettingsManager.setStatusBarSlotsRight(context, it) }
            resultData = "Test menu configuration applied"
        } catch (error: IllegalArgumentException) {
            resultCode = 1
            resultData = error.message
        }
    }

    private fun decode(value: String): List<String> = JSONArray(value).let { array ->
        List(array.length()) { array.getString(it) }
    }
}
