package rocks.pkb.plektra.inputmethod

import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Hides the caption bar that Android adds to the IME window (navigation handle, back and IME
 * switcher, `NavigationBarFrame`) through the regular insets API.
 *
 * From API 34 the framework shows that frame only while `WindowInsets.Type.captionBar()` is
 * visible in the IME window and then also drops it from the content insets. The change stays in
 * Plektra's own window; no system setting changes.
 *
 * Plektra owns a hide only on the window where it hid a bar that was visible at that moment, and
 * shows the bar again only there. A bar already hidden by the system or the OEM stays theirs,
 * and so does every bar on another window. While a window's caption bar state is unknown, before
 * the window is attached, nothing changes; [update] then asks the caller to try again.
 */
internal class ImeCaptionBarController(private val sdkInt: Int = Build.VERSION.SDK_INT) {
    private var ownedWindow: WindowInsetsController? = null

    val hiddenByPlektra: Boolean
        get() = ownedWindow != null

    /**
     * @param captionBarVisible whether the caption bar is visible in that window now, or null
     *   while unknown.
     * @return true when the choice waits for a known caption bar state.
     */
    fun update(controller: WindowInsetsController?, hide: Boolean, captionBarVisible: Boolean?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || controller == null
        ) return false
        // A hide on another window cannot be undone from here; it is not Plektra's to restore.
        if (ownedWindow != null && ownedWindow !== controller) ownedWindow = null
        val owned = ownedWindow === controller
        if (!hide) {
            if (owned) {
                controller.show(WindowInsets.Type.captionBar())
                ownedWindow = null
            }
            return false
        }
        return when {
            // Repeated on every window show; the framework may recreate the frame.
            owned -> {
                controller.hide(WindowInsets.Type.captionBar())
                false
            }
            captionBarVisible == null -> true
            captionBarVisible -> {
                controller.hide(WindowInsets.Type.captionBar())
                ownedWindow = controller
                false
            }
            else -> false
        }
    }

    companion object {
        /** Robolectric windows have no caption bar source; tests set the state they model. */
        internal var captionBarVisibleForTests: Boolean? = null

        /** The caption bar state of an attached decor, or null while it is not known. */
        fun captionBarVisible(decor: View?): Boolean? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                decor == null || !decor.isAttachedToWindow
            ) return null
            captionBarVisibleForTests?.let { return it }
            return decor.rootWindowInsets?.isVisible(WindowInsets.Type.captionBar())
        }
    }
}
