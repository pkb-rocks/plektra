package rocks.pkb.plektra

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Notes editor geometry. The default is a nearly square phone with a camera cutout in the
 * top-left corner of the status bar (1076×1200 px window at 360 dpi, 123 px status bar and
 * cutout); further cases cover narrow windows, centred cutouts, RTL and landscape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w478dp-h533dp-360dpi")
class NotesCompactLayoutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<NotesActivity>? = null

    /** Window size, insets and cutout handed to the activity, in window pixels. */
    private data class Window(
        val width: Int,
        val height: Int,
        val statusBar: Int,
        val cutout: Rect,
        val cutoutInsets: Insets,
        val ime: Int = 0
    ) {
        val cutoutSafe = Rect(cutoutInsets.left, cutoutInsets.top, cutoutInsets.right, cutoutInsets.bottom)
    }

    @Before
    fun resetState() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        context.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        setFontScale(1f)
    }

    @Test
    fun fullScreenHeaderSharesTheCameraRowWithoutTouchingTheCutout() {
        verifyFullScreenHeader(fontScale = 1f)
    }

    @Test
    fun fullScreenHeaderKeepsAllActionsUsableAtLargerFont() {
        verifyFullScreenHeader(fontScale = 1.3f)
    }

    @Test
    fun headerBelowVisibleStatusBarLeavesStatusIconsAndCameraFree() {
        verifyHeaderBelowStatusBar(fontScale = 1f)
    }

    @Test
    fun headerBelowVisibleStatusBarKeepsAllActionsUsableAtLargerFont() {
        verifyHeaderBelowStatusBar(fontScale = 1.3f)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-360dpi")
    fun centredCutoutInANarrowWindowKeepsEveryActionVisibleAndSeparate() {
        verifyCrowdedHeader(CENTRED_360, fontScale = 1f)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-360dpi")
    fun centredCutoutInANarrowWindowKeepsEveryActionUsableAtLargeFont() {
        verifyCrowdedHeader(CENTRED_360, fontScale = 1.3f)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-360dpi")
    fun cornerCutoutInA320dpWindowKeepsBackAndActionsApart() {
        verifyCrowdedHeader(CORNER_320, fontScale = 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-360dpi")
    fun cornerCutoutInA320dpWindowKeepsActionsUsableAtLargeFont() {
        verifyCrowdedHeader(CORNER_320, fontScale = 1.3f)
    }

    @Test
    @Config(qualifiers = "ldrtl-w360dp-h640dp-360dpi")
    fun centredCutoutInARightToLeftWindowKeepsEveryActionVisibleAndSeparate() {
        verifyCrowdedHeader(CENTRED_360, fontScale = 1f)
    }

    @Test
    @Config(qualifiers = "ldrtl-w320dp-h640dp-360dpi")
    fun cornerCutoutInARightToLeftWindowKeepsBackAndActionsApart() {
        verifyCrowdedHeader(CORNER_320, fontScale = 1.3f)
    }

    @Test
    fun formattingToolsAndModeSwitchesKeepFullTouchTargets() {
        SettingsManager.setSettingsFullscreenEnabled(context, true)
        val activity = launch()
        layout(activity, T2E_FULLSCREEN)
        val minTarget = dp(activity, 48)
        val tools = (activity.findViewById<View>(R.id.notes_undo_button).parent as ViewGroup)
        val toolRects = (0 until tools.childCount).map { tools.getChildAt(it) }.map { tool ->
            val rect = windowRect(tool)
            assertTrue("${tool.contentDescription} needs a 48 dp touch target: $rect",
                rect.width() >= minTarget && rect.height() >= minTarget)
            assertTrue("${tool.contentDescription} must act on a tap", tool.isClickable && tool.hasOnClickListeners())
            rect
        }
        assertNoOverlap(toolRects)
        listOf(R.id.notes_markdown_toggle, R.id.notes_both_toggle, R.id.notes_preview_toggle).forEach { id ->
            val tab = activity.findViewById<View>(id)
            val rect = windowRect(tab)
            assertTrue("${tab.contentDescription} needs a 48 dp touch target: $rect",
                rect.width() >= minTarget && rect.height() >= minTarget)
        }
        activity.findViewById<View>(R.id.notes_preview_toggle).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.notes_preview_body).let { (it.parent as View).visibility })
    }

    @Test
    fun openKeyboardBarKeepsHeaderStillAndCaretVisible() {
        SettingsManager.setSettingsFullscreenEnabled(context, true)
        val activity = launch()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        editor.setText((1..80).joinToString("\n") { "Line $it of a long note" })
        editor.requestFocus()
        editor.setSelection(editor.length())
        layout(activity, T2E_FULLSCREEN)
        val header = headerOf(activity)
        val headerTopBefore = windowRect(header).top
        val headerHeightBefore = header.height
        val editorHeightClosed = editor.height

        layout(activity, T2E_FULLSCREEN.copy(ime = KEYBOARD_BAR))
        assertEquals("Header must not move when the keyboard bar opens", headerTopBefore, windowRect(header).top)
        assertEquals("Header must keep its height when the keyboard bar opens", headerHeightBefore, header.height)
        val editorRect = windowRect(editor)
        assertTrue("Editor must end above the keyboard bar: $editorRect", editorRect.bottom <= T2E_HEIGHT - KEYBOARD_BAR)
        assertTrue("Editor must shrink with the keyboard bar", editor.height < editorHeightClosed)
        assertCaretVisible(editor)
        val chrome = editorRect.top
        assertTrue("Header and tools may use at most $MAX_FULLSCREEN_CHROME px above the editor, used $chrome",
            chrome <= MAX_FULLSCREEN_CHROME)

        layout(activity, T2E_FULLSCREEN)
        assertEquals(headerTopBefore, windowRect(header).top)
        assertEquals(editorHeightClosed, editor.height)
    }

    @Test
    @Config(qualifiers = "w533dp-h478dp-land-360dpi")
    fun landscapeEditorWithKeyboardBarStaysClearOfTheSideCutout() {
        val activity = launch()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        editor.setText((1..60).joinToString("\n") { "Line $it of a long note" })
        editor.requestFocus()
        editor.setSelection(editor.length())
        layout(activity, LANDSCAPE)
        assertTrue("The keyboard bar must be open in this case", LANDSCAPE.ime > 0)
        verifyActions(activity, LANDSCAPE, forbidden = listOf(LANDSCAPE.cutout, Rect(0, 0, LANDSCAPE.width, LANDSCAPE.statusBar)))
        val editorRect = windowRect(editor)
        assertTrue("Editor must stay clear of the camera: $editorRect", editorRect.left >= LANDSCAPE.cutout.right)
        assertTrue("Editor must end above the keyboard bar: $editorRect", editorRect.bottom <= LANDSCAPE.height - LANDSCAPE.ime)
        assertTrue("Editor must keep room for text: $editorRect", editor.height >= dp(activity, 96))
        assertCaretVisible(editor)
        assertTrue("Tools must stay clear of the camera", windowRect(formatBarOf(activity)).left >= LANDSCAPE.cutout.right)
    }

    private fun verifyFullScreenHeader(fontScale: Float) {
        setFontScale(fontScale)
        SettingsManager.setSettingsFullscreenEnabled(context, true)
        val activity = launch()
        activity.findViewById<EditText>(R.id.notes_heading).setText("Shopping list for the weekend")
        layout(activity, T2E_FULLSCREEN)

        val header = headerOf(activity)
        assertTrue("Full-screen header must start in the top row, at ${windowRect(header).top}",
            windowRect(header).top < T2E_CUTOUT.bottom)
        verifyActions(activity, T2E_FULLSCREEN, forbidden = listOf(T2E_CUTOUT), titleInHeader = true)
        val tools = windowRect(formatBarOf(activity))
        assertTrue("Formatting tools must start below the camera: $tools", tools.top >= T2E_CUTOUT.bottom)
        assertTrue("Header and tools may use at most $MAX_FULLSCREEN_CHROME px, used ${tools.bottom}",
            tools.bottom <= MAX_FULLSCREEN_CHROME)
    }

    private fun verifyHeaderBelowStatusBar(fontScale: Float) {
        setFontScale(fontScale)
        val activity = launch()
        activity.findViewById<EditText>(R.id.notes_heading).setText("Shopping list for the weekend")
        layout(activity, T2E_STATUS_BAR)

        val statusBand = Rect(0, 0, T2E_WIDTH, T2E_STATUS_BAR.statusBar)
        verifyActions(activity, T2E_STATUS_BAR, forbidden = listOf(statusBand, T2E_CUTOUT), titleInHeader = true)
        val tools = windowRect(formatBarOf(activity))
        val chrome = tools.bottom - T2E_STATUS_BAR.statusBar
        assertTrue("Header and tools below the status bar may use at most $MAX_CHROME px, used $chrome", chrome <= MAX_CHROME)
    }

    private fun verifyCrowdedHeader(window: Window, fontScale: Float) {
        setFontScale(fontScale)
        SettingsManager.setSettingsFullscreenEnabled(context, true)
        val activity = launch()
        activity.findViewById<EditText>(R.id.notes_heading).setText("Shopping list for the weekend")
        layout(activity, window)
        verifyActions(activity, window, forbidden = listOf(window.cutout))
        val tools = windowRect(formatBarOf(activity))
        assertTrue("Formatting tools must start below the camera: $tools", tools.top >= window.cutout.bottom)
        val header = windowRect(headerOf(activity))
        assertTrue("Tools must follow the header: $tools after $header", tools.top >= header.bottom)
        // Every action still does its job.
        activity.findViewById<View>(R.id.notes_preview_toggle).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.notes_body).visibility)
        activity.findViewById<View>(R.id.notes_markdown_toggle).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.notes_body).visibility)
    }

    private fun verifyActions(activity: NotesActivity, window: Window, forbidden: List<Rect>, titleInHeader: Boolean = false) {
        val header = headerOf(activity)
        val headerRect = windowRect(header)
        val heading = activity.findViewById<EditText>(R.id.notes_heading)
        val actions = listOf(
            byDescription(activity, R.string.settings_back_content_description),
            activity.findViewById<View>(R.id.notes_markdown_toggle),
            activity.findViewById<View>(R.id.notes_both_toggle),
            activity.findViewById<View>(R.id.notes_preview_toggle),
            activity.findViewById<View>(R.id.notes_new),
            byDescription(activity, R.string.notes_title),
        )
        val minTarget = dp(activity, 48)
        val rects = actions.map { windowRect(it) }
        actions.zip(rects).forEach { (view, rect) ->
            val name = view.contentDescription
            assertTrue("$name must be shown", view.isShown)
            assertTrue("$name must be clickable", view.isClickable && view.isEnabled && view.hasOnClickListeners())
            assertTrue("$name must lie inside the window: $rect", rect.left >= 0 && rect.right <= window.width)
            assertTrue("$name must lie inside the header: $rect in $headerRect",
                rect.top >= headerRect.top && rect.bottom <= headerRect.bottom)
            assertTrue("$name must keep a 48 dp touch target: $rect", rect.width() >= minTarget && rect.height() >= minTarget)
            forbidden.forEach { area ->
                assertTrue("$name must stay clear of $area: $rect", !Rect.intersects(rect, area))
            }
        }
        assertNoOverlap(rects)
        val title = windowRect(heading)
        if (titleInHeader) {
            assertTrue("Title must share the header: $title in $headerRect", title.top >= headerRect.top && title.bottom <= headerRect.bottom)
        }
        assertTrue("Title must lie inside the window: $title", title.left >= 0 && title.right <= window.width)
        assertTrue("Title must stay readable: ${title.width()} px", title.width() >= dp(activity, 96))
        forbidden.forEach { area -> assertTrue("Title must stay clear of $area: $title", !Rect.intersects(title, area)) }
        rects.forEach { assertTrue("Title must not overlap an action: $title and $it", !Rect.intersects(title, it)) }
        heading.requestFocus()
        assertTrue(heading.hasFocus())
    }

    private fun assertNoOverlap(rects: List<Rect>) {
        rects.forEachIndexed { i, a ->
            rects.drop(i + 1).forEach { b -> assertTrue("Touch targets must not overlap: $a and $b", !Rect.intersects(a, b)) }
        }
    }

    private fun assertCaretVisible(editor: EditText) {
        val textLayout = editor.layout
        val line = textLayout.getLineForOffset(editor.selectionEnd)
        val caretTop = textLayout.getLineTop(line) + editor.totalPaddingTop - editor.scrollY
        val caretBottom = textLayout.getLineBottom(line) + editor.totalPaddingTop - editor.scrollY
        assertTrue("Caret line must be visible above the keyboard bar: $caretTop..$caretBottom in ${editor.height}",
            caretTop >= 0 && caretBottom <= editor.height)
    }

    private fun launch(): NotesActivity {
        val created = Robolectric.buildActivity(NotesActivity::class.java).setup()
        controller = created
        return created.get()
    }

    private fun dp(activity: NotesActivity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun setFontScale(scale: Float) {
        val resources = RuntimeEnvironment.getApplication().resources
        val configuration = Configuration(resources.configuration).apply { fontScale = scale }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun rootOf(activity: NotesActivity): View =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun headerOf(activity: NotesActivity): View = byDescription(activity, R.string.settings_back_content_description).parent as View

    private fun formatBarOf(activity: NotesActivity): View {
        var view: View = activity.findViewById(R.id.notes_undo_button)
        while (view.parent !== rootOf(activity)) view = view.parent as View
        return view
    }

    private fun byDescription(activity: NotesActivity, description: Int): View {
        val matches = ArrayList<View>()
        rootOf(activity).findViewsWithText(matches, activity.getString(description), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return matches.single()
    }

    private fun windowRect(view: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun layout(activity: NotesActivity, window: Window) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, window.statusBar, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, window.ime))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), window.cutoutInsets)
            .setDisplayCutout(DisplayCutoutCompat(window.cutoutSafe, listOf(window.cutout)))
            .build()
        val decor = activity.window.decorView
        repeat(3) {
            ViewCompat.dispatchApplyWindowInsets(rootOf(activity), insets)
            decor.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(window.height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, window.width, window.height)
            decor.viewTreeObserver.dispatchOnGlobalLayout()
            decor.viewTreeObserver.dispatchOnPreDraw()
        }
    }

    private companion object {
        const val T2E_WIDTH = 1076
        const val T2E_HEIGHT = 1200
        /** Keyboard bar inset measured on the device with Plektra's two-row bar. */
        const val KEYBOARD_BAR = 165
        val T2E_CUTOUT = Rect(0, 0, 123, 123)
        val T2E_FULLSCREEN = Window(T2E_WIDTH, T2E_HEIGHT, statusBar = 0, cutout = T2E_CUTOUT, cutoutInsets = Insets.of(0, 123, 0, 0))
        val T2E_STATUS_BAR = T2E_FULLSCREEN.copy(statusBar = 123)
        /** 360 dp window with a 32 dp punch hole centred at the top (164..196 dp). */
        val CENTRED_360 = Window(810, 1440, statusBar = 0, cutout = Rect(369, 0, 441, 72), cutoutInsets = Insets.of(0, 72, 0, 0))
        /** 320 dp window with a 55 dp camera in the top-left corner. */
        val CORNER_320 = Window(720, 1440, statusBar = 0, cutout = Rect(0, 0, 124, 124), cutoutInsets = Insets.of(0, 124, 0, 0))
        /** T2E turned to landscape: the camera sits at the bottom left, status bar visible. */
        val LANDSCAPE = Window(1200, 1076, statusBar = 123, cutout = Rect(0, 953, 123, 1076),
            cutoutInsets = Insets.of(123, 0, 0, 0), ime = KEYBOARD_BAR)
        /** Camera-row header plus a 48 dp formatting row and the hairline (about 103 dp at 360 dpi). */
        const val MAX_FULLSCREEN_CHROME = 234
        /** Header and formatting rows below a visible status bar (about 97 dp at 360 dpi). */
        const val MAX_CHROME = 219
    }
}
