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
 * Notes editor geometry on a nearly square phone with a camera cutout in the top-left
 * corner of the status bar (1076×1200 px window at 360 dpi, 123 px status bar and cutout).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w478dp-h533dp-360dpi")
class NotesCompactLayoutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<NotesActivity>? = null

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
    fun openKeyboardBarKeepsHeaderStillAndCaretVisible() {
        SettingsManager.setSettingsFullscreenEnabled(context, true)
        val activity = launch()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        editor.setText((1..80).joinToString("\n") { "Line $it of a long note" })
        editor.requestFocus()
        editor.setSelection(editor.length())
        layout(activity, statusBar = 0, ime = 0)
        val header = headerOf(activity)
        val headerTopBefore = windowRect(header).top
        val headerHeightBefore = header.height
        val editorHeightClosed = editor.height

        layout(activity, statusBar = 0, ime = KEYBOARD_BAR)
        assertEquals("Header must not move when the keyboard bar opens", headerTopBefore, windowRect(header).top)
        assertEquals("Header must keep its height when the keyboard bar opens", headerHeightBefore, header.height)
        val editorRect = windowRect(editor)
        assertTrue("Editor must end above the keyboard bar: $editorRect", editorRect.bottom <= WINDOW_HEIGHT - KEYBOARD_BAR)
        assertTrue("Editor must shrink with the keyboard bar", editor.height < editorHeightClosed)
        val textLayout = editor.layout
        val line = textLayout.getLineForOffset(editor.selectionEnd)
        val caretTop = textLayout.getLineTop(line) + editor.totalPaddingTop - editor.scrollY
        val caretBottom = textLayout.getLineBottom(line) + editor.totalPaddingTop - editor.scrollY
        assertTrue("Caret line must be visible above the keyboard bar: $caretTop..$caretBottom in ${editor.height}",
            caretTop >= 0 && caretBottom <= editor.height)
        val chrome = editorRect.top
        assertTrue("Header and tools may use at most $MAX_FULLSCREEN_CHROME px above the editor, used $chrome",
            chrome <= MAX_FULLSCREEN_CHROME)

        layout(activity, statusBar = 0, ime = 0)
        assertEquals(headerTopBefore, windowRect(header).top)
        assertEquals(editorHeightClosed, editor.height)
    }

    private fun verifyFullScreenHeader(fontScale: Float) {
        setFontScale(fontScale)
        SettingsManager.setSettingsFullscreenEnabled(context, true)
        val activity = launch()
        activity.findViewById<EditText>(R.id.notes_heading).setText("Shopping list for the weekend")
        layout(activity, statusBar = 0, ime = 0)

        val header = headerOf(activity)
        assertTrue("Full-screen header must start in the top row, at ${windowRect(header).top}", windowRect(header).top < CUTOUT.bottom)
        verifyActions(activity, forbidden = listOf(CUTOUT))
        val tools = windowRect(formatBarOf(activity))
        assertTrue("Formatting tools must start below the camera: $tools", tools.top >= CUTOUT.bottom)
        assertTrue("Header and tools may use at most $MAX_FULLSCREEN_CHROME px, used ${tools.bottom}",
            tools.bottom <= MAX_FULLSCREEN_CHROME)
    }

    private fun verifyHeaderBelowStatusBar(fontScale: Float) {
        setFontScale(fontScale)
        val activity = launch()
        activity.findViewById<EditText>(R.id.notes_heading).setText("Shopping list for the weekend")
        layout(activity, statusBar = STATUS_BAR, ime = 0)

        val statusBand = Rect(0, 0, WINDOW_WIDTH, STATUS_BAR)
        verifyActions(activity, forbidden = listOf(statusBand, CUTOUT))
        val tools = windowRect(formatBarOf(activity))
        val chrome = tools.bottom - STATUS_BAR
        assertTrue("Header and tools below the status bar may use at most $MAX_CHROME px, used $chrome", chrome <= MAX_CHROME)
    }

    private fun verifyActions(activity: NotesActivity, forbidden: List<Rect>) {
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
        val minTarget = (40 * activity.resources.displayMetrics.density).toInt()
        val rects = actions.map { windowRect(it) }
        actions.zip(rects).forEach { (view, rect) ->
            val name = view.contentDescription
            assertTrue("$name must be shown", view.isShown)
            assertTrue("$name must be clickable", view.isClickable && view.isEnabled)
            assertTrue("$name must lie inside the window: $rect", rect.left >= 0 && rect.right <= WINDOW_WIDTH)
            assertTrue("$name must lie inside the header row: $rect in $headerRect",
                rect.top >= headerRect.top && rect.bottom <= headerRect.bottom)
            assertTrue("$name must keep a usable touch target: $rect", rect.width() >= minTarget && rect.height() >= minTarget)
            forbidden.forEach { area ->
                assertTrue("$name must stay clear of $area: $rect", !Rect.intersects(rect, area))
            }
        }
        rects.forEachIndexed { i, a ->
            rects.drop(i + 1).forEach { b -> assertTrue("Actions must not overlap: $a and $b", !Rect.intersects(a, b)) }
        }
        val title = windowRect(heading)
        assertTrue("Title must share the header row: $title in $headerRect", title.top >= headerRect.top && title.bottom <= headerRect.bottom)
        assertTrue("Title must stay readable: ${title.width()} px", title.width() >= 2 * minTarget)
        forbidden.forEach { area -> assertTrue("Title must stay clear of $area: $title", !Rect.intersects(title, area)) }
        rects.forEach { assertTrue("Title must not overlap an action: $title and $it", !Rect.intersects(title, it)) }
        heading.requestFocus()
        assertTrue(heading.hasFocus())
    }

    private fun launch(): NotesActivity {
        val created = Robolectric.buildActivity(NotesActivity::class.java).setup()
        controller = created
        return created.get()
    }

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

    private fun layout(activity: NotesActivity, statusBar: Int, ime: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVIGATION_BAR))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, CUTOUT.bottom, 0, 0))
            .setDisplayCutout(DisplayCutoutCompat(Rect(0, CUTOUT.bottom, 0, 0), listOf(CUTOUT)))
            .build()
        val decor = activity.window.decorView
        repeat(3) {
            ViewCompat.dispatchApplyWindowInsets(rootOf(activity), insets)
            decor.measure(View.MeasureSpec.makeMeasureSpec(WINDOW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WINDOW_HEIGHT, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT)
            decor.viewTreeObserver.dispatchOnGlobalLayout()
            decor.viewTreeObserver.dispatchOnPreDraw()
        }
    }

    private companion object {
        const val WINDOW_WIDTH = 1076
        const val WINDOW_HEIGHT = 1200
        const val STATUS_BAR = 123
        const val NAVIGATION_BAR = 0
        /** Keyboard bar inset measured on the device with Plektra's two-row bar. */
        const val KEYBOARD_BAR = 165
        val CUTOUT = Rect(0, 0, 123, 123)
        /** Header row in the camera band plus the formatting row (about 96 dp at 360 dpi). */
        const val MAX_FULLSCREEN_CHROME = 216
        /** Header and formatting rows below a visible status bar (about 92 dp at 360 dpi). */
        const val MAX_CHROME = 207
    }
}
