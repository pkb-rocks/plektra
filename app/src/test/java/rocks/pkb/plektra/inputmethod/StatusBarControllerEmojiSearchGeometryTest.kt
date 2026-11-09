package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView
import rocks.pkb.plektra.inputmethod.ui.EmojiPickerView

/**
 * The emoji search popup in a short, wide IME window: the window sits below the status bar and
 * beside a display cutout, as on a foldable's outer display in landscape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w905dp-h369dp-420dpi")
class StatusBarControllerEmojiSearchGeometryTest {
    private lateinit var activity: Activity
    private lateinit var windowManager: WindowManager
    private lateinit var controller: StatusBarController
    private lateinit var host: FrameLayout
    private val statusBarHeight = 79
    private val cutoutWidth = 95

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SettingsManager.getPreferences(activity).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        windowManager = activity.getSystemService(WindowManager::class.java)
        controller = StatusBarController(activity, StatusBarController.Mode.INPUT_VIEW)
        controller.screenSafeTopPx = { statusBarHeight }
        host = FrameLayout(activity)
    }

    @After
    fun tearDown() {
        controller.dismissEmojiPickerPopup()
        if (host.isAttachedToWindow) windowManager.removeViewImmediate(host)
    }

    @Test
    @Config(sdk = [29])
    fun legacyWindowFrameKeepsSearchBelowTheStatusBar() {
        val frameView = object : View(activity) {
            override fun getWindowVisibleDisplayFrame(frame: android.graphics.Rect) {
                frame.set(95, 79, 2376, 968)
            }
            override fun getRootWindowInsets(): android.view.WindowInsets? = null
        }
        assertEquals(statusBarHeight, legacyEmojiSearchSafeTop(frameView))
        val insetView = object : View(activity) {
            override fun getWindowVisibleDisplayFrame(frame: android.graphics.Rect) { frame.setEmpty() }
            override fun getRootWindowInsets(): android.view.WindowInsets = android.view.WindowInsets.Builder()
                .setStableInsets(android.graphics.Insets.of(0, 79, 0, 0))
                .setDisplayCutout(android.view.DisplayCutout(android.graphics.Rect(0, 90, 0, 0), emptyList()))
                .build()
        }
        assertEquals(90, legacyEmojiSearchSafeTop(insetView))
        controller.screenSafeTopPx = { legacyEmojiSearchSafeTop(frameView) }
        attachImeWindow(2281, 889)
        openSearch()
        assertUsableSearch("legacy visible frame")
    }

    /** Adds the IME root in its own window, like the input method window, of the given bounds. */
    private fun attachImeWindow(width: Int, height: Int) {
        val params = WindowManager.LayoutParams(
            width, height, cutoutWidth, statusBarHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (host.isAttachedToWindow) windowManager.updateViewLayout(host, params) else windowManager.addView(host, params)
        idle()
    }

    private fun render(symPage: Int) {
        controller.update(snapshot(symPage))
        val root = controller.getLayout()!!
        if (root.parent == null) {
            host.addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        idle()
    }

    private fun idle() {
        repeat(3) {
            host.requestLayout()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        }
    }

    private fun dp(value: Float) = (value * activity.resources.displayMetrics.density).toInt()

    private fun onScreen(view: View) = IntArray(2).also(view::getLocationOnScreen)

    /** Checks the search stack as the user sees it in the current IME window. */
    private fun assertUsableSearch(label: String) {
        val popup = searchPopup()
        assertTrue("$label: popup showing", popup.isShowing)
        val params = popup.contentView.rootView.layoutParams as WindowManager.LayoutParams
        val window = onScreen(host)
        val keyboard = keyboard()
        val keyboardSlot = keyboard.parent as View

        // Where the window manager places the panel within its parent, the IME window.
        val frame = android.graphics.Rect(window[0], window[1], window[0] + host.width, window[1] + host.height)
        val placed = android.graphics.Rect()
        Gravity.apply(params.gravity, params.width, params.height, frame, params.x, params.y, placed)

        // The popup spans the IME window, not the display (which includes the cutout).
        assertEquals("$label: popup left", frame.left, placed.left)
        assertEquals("$label: popup right", frame.right, placed.right)

        // It ends where the keyboard begins and starts below the status bar.
        assertEquals("$label: popup bottom meets keyboard top", onScreen(keyboard)[1], placed.bottom)
        assertTrue("$label: popup top ${placed.top} below status bar", placed.top >= statusBarHeight)

        // The keyboard is fully inside its slot: no rows are cut off.
        assertTrue("$label: keyboard ${keyboard.top}..${keyboard.bottom} inside ${keyboardSlot.height}",
            keyboard.top >= 0 && keyboard.bottom <= keyboardSlot.height && keyboard.height > 0)

        // Above the search field, at least one full emoji row remains visible.
        val content = popup.contentView as EmojiPickerView
        val results = pickerField(content, "recyclerView") as View
        val searchPanel = pickerField(content, "searchPanel") as View
        assertEquals("$label: popup content height", params.height, content.height)
        // The grid may still be loading; its frame is the area the results scroll in.
        val visibleRows = (results.parent as View).height - results.paddingTop - searchPanel.height
        assertTrue("$label: result area $visibleRows px holds a 48dp row " +
            "(search ${searchPanel.height})", visibleRows >= dp(48f))
    }

    private fun openSearch() {
        render(0)
        render(4)
        picker().invokeSetSearchPanelVisible(true)
        render(4)
    }

    @Test
    fun searchStaysUsableInShortWideWindowBesideCutout() {
        attachImeWindow(2376 - cutoutWidth, 968 - statusBarHeight)
        openSearch()
        assertUsableSearch("outer landscape")

        // A status update while searching re-renders the picker page.
        render(4)
        assertUsableSearch("after re-render")
    }

    @Test
    fun searchFollowsRepeatedWindowSizeChanges() {
        attachImeWindow(2376 - cutoutWidth, 968 - statusBarHeight)
        openSearch()
        assertUsableSearch("short")

        attachImeWindow(2376 - cutoutWidth, 620)
        assertUsableSearch("shorter")

        // Ample room: the keyboard keeps its full slot and the popup its configured height.
        RuntimeEnvironment.setQualifiers("w707dp-h823dp-420dpi")
        attachImeWindow(1856 - cutoutWidth, 2160 - statusBarHeight)
        render(4)
        assertUsableSearch("tall")
        assertEquals(EmojiPickerView.configuredHeightPx(activity), searchPopup().height)
        assertEquals((keyboard().parent as View).height, keyboard().height)

        RuntimeEnvironment.setQualifiers("w905dp-h369dp-420dpi")
        attachImeWindow(2376 - cutoutWidth, 968 - statusBarHeight)
        render(4)
        assertUsableSearch("short again")
    }

    @Test
    fun leavingSearchReturnsTheLentRowsToTheKeyboard() {
        attachImeWindow(2376 - cutoutWidth, 968 - statusBarHeight)
        openSearch()
        assertUsableSearch("search")

        // Closing the search returns to the inline picker.
        picker().invokeSetSearchPanelVisible(false)
        render(4)
        assertTrue(!searchPopup().isShowing)
        assertSame(picker(), (privateField("emojiKeyboardContainer") as ViewGroup).getChildAt(0))

        // Leaving the picker straight from the search gives the keyboard its whole slot back.
        picker().invokeSetSearchPanelVisible(true)
        render(4)
        assertUsableSearch("search again")
        render(0)
        assertTrue(!searchPopup().isShowing)
        val keyboard = keyboard()
        assertEquals(0, keyboard.top)
        assertEquals((keyboard.parent as View).height, keyboard.height)

        // Another SYM page and back to a fresh search.
        render(3)
        render(4)
        picker().invokeSetSearchPanelVisible(true)
        render(4)
        assertUsableSearch("after clipboard page")
    }

    private fun snapshot(symPage: Int) = StatusBarController.StatusSnapshot(
        capsLockEnabled = false,
        shiftPhysicallyPressed = false,
        shiftOneShot = false,
        ctrlLatchActive = false,
        ctrlPhysicallyPressed = false,
        ctrlOneShot = false,
        ctrlLatchFromNavMode = false,
        altLatchActive = false,
        altPhysicallyPressed = false,
        altOneShot = false,
        symPage = symPage
    )

    private fun picker() = privateField("emojiPickerView") as EmojiPickerView
    private fun keyboard() = privateField("softwareKeyboardView") as AospKeyboardView
    private fun searchPopup() = privateField("emojiPickerSearchPopup") as PopupWindow

    private fun pickerField(picker: EmojiPickerView, name: String): Any? =
        EmojiPickerView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(picker)

    private fun privateField(name: String): Any? =
        StatusBarController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)

    private fun EmojiPickerView.invokeSetSearchPanelVisible(visible: Boolean) {
        EmojiPickerView::class.java.getDeclaredMethod("setSearchPanelVisible", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, visible)
    }
}
