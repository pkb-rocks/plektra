package rocks.pkb.plektra

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode

/**
 * Synthetic renders of the notes editor in the geometry of a nearly square phone with a
 * camera cutout in the top-left corner (1076×1200 px, 360 dpi). The status bar, cutout
 * and keyboard bar are drawn as placeholders. Renders all screens only when
 * PLEKTRA_NOTES_RENDER names an output directory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w478dp-h533dp-360dpi")
class NotesCompactRenderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private enum class Screen { LIST, EDIT, PREVIEW }

    @Before
    fun resetState() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        context.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Runs without an output directory: one render must draw the header and the note. */
    @Test
    fun editorRendersInCameraGeometry() {
        val bitmap = render(Screen.EDIT, AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, fullscreen = true, keyboard = true)
        val distinct = HashSet<Int>()
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) distinct += bitmap.getPixel(x, y)
        assertTrue("Render must show content, found ${distinct.size} colors", distinct.size > 8)
        bitmap.recycle()
    }

    @Test
    fun renderAllScreens() {
        val out = System.getenv("PLEKTRA_NOTES_RENDER")?.let(::File)
        assumeTrue("Set PLEKTRA_NOTES_RENDER to an output directory", out != null)
        out!!.mkdirs()
        val themes = listOf(
            "light" to (AppThemeMode.LIGHT to AppDarkSurfaceStyle.STANDARD),
            "dark" to (AppThemeMode.DARK to AppDarkSurfaceStyle.STANDARD),
            "oled" to (AppThemeMode.DARK to AppDarkSurfaceStyle.OLED),
        )
        for (fullscreen in listOf(false, true)) for ((themeName, theme) in themes) for (screen in Screen.entries) {
            for (keyboard in listOf(false, true)) {
                if (themeName == "oled" && screen == Screen.LIST) continue
                val bitmap = render(screen, theme.first, theme.second, fullscreen, keyboard)
                val name = listOf(
                    if (fullscreen) "fullscreen" else "statusbar",
                    themeName,
                    screen.name.lowercase(),
                    if (keyboard) "bar" else "nobar",
                ).joinToString("-")
                File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                resetState()
            }
        }
    }

    private fun render(
        screen: Screen,
        themeMode: AppThemeMode,
        surface: AppDarkSurfaceStyle,
        fullscreen: Boolean,
        keyboard: Boolean
    ): Bitmap {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(themeMode = themeMode, darkSurfaceStyle = surface, dynamicColor = false)
        )
        SettingsManager.setSettingsFullscreenEnabled(context, fullscreen)
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.notes_heading).setText(TEST_TITLE)
            val editor = activity.findViewById<EditText>(R.id.notes_body)
            editor.setText(TEST_BODY)
            editor.requestFocus()
            editor.setSelection(TEST_BODY.indexOf("- [ ] Milk") + 10)
            if (screen == Screen.PREVIEW) {
                activity.findViewById<TextView>(R.id.notes_preview_toggle).performClick()
                if (keyboard) activity.findViewById<EditText>(R.id.notes_heading).apply { requestFocus(); setSelection(length()) }
            }
            val statusBar = if (fullscreen) 0 else STATUS_BAR
            val ime = if (keyboard) KEYBOARD_BAR else 0
            layout(activity, statusBar, ime)
            val decor = activity.window.decorView
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            if (screen == Screen.LIST) {
                activity.findViewById<View>(android.R.id.content).let { content ->
                    val more = ArrayList<View>()
                    content.findViewsWithText(more, activity.getString(R.string.notes_title), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    more.first().performClick()
                }
                drawDialog(canvas, ShadowAlertDialog.getLatestAlertDialog(), statusBar, ime)
            }
            drawSystemPlaceholders(canvas, statusBar, ime)
            return bitmap
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun drawDialog(canvas: Canvas, dialog: AlertDialog, statusBar: Int, ime: Int) {
        canvas.drawColor(Color.argb(0x80, 0, 0, 0))
        val dialogDecor = dialog.window!!.decorView
        val maxWidth = WIDTH - 2 * 80
        dialogDecor.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT - statusBar - ime, View.MeasureSpec.AT_MOST))
        dialogDecor.layout(0, 0, dialogDecor.measuredWidth, dialogDecor.measuredHeight)
        canvas.save()
        canvas.translate(((WIDTH - dialogDecor.width) / 2).toFloat(),
            (statusBar + (HEIGHT - statusBar - ime - dialogDecor.height) / 2).toFloat())
        dialogDecor.draw(canvas)
        canvas.restore()
        dialog.dismiss()
    }

    private fun drawSystemPlaceholders(canvas: Canvas, statusBar: Int, ime: Int) {
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.rgb(128, 128, 128) }
        if (statusBar > 0) {
            val band = Paint().apply { color = Color.argb(0x30, 128, 128, 128) }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), statusBar.toFloat(), band)
            canvas.drawText("12:00", CUTOUT.right + 24f, statusBar / 2f + 10f, label)
            canvas.drawText("▼ ▲ ▮", WIDTH - 140f, statusBar / 2f + 10f, label)
        }
        canvas.drawRect(CUTOUT, Paint().apply { color = Color.BLACK })
        if (ime > 0) {
            val bar = Paint().apply { color = Color.rgb(60, 62, 64) }
            canvas.drawRect(0f, (HEIGHT - ime).toFloat(), WIDTH.toFloat(), HEIGHT.toFloat(), bar)
            canvas.drawText("keyboard bar", 32f, HEIGHT - ime / 2f + 10f, label.apply { color = Color.rgb(200, 200, 200) })
        }
    }

    private fun layout(activity: NotesActivity, statusBar: Int, ime: Int) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, CUTOUT.bottom, 0, 0))
            .setDisplayCutout(DisplayCutoutCompat(Rect(0, CUTOUT.bottom, 0, 0), listOf(CUTOUT)))
            .build()
        val decor = activity.window.decorView
        repeat(3) {
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, WIDTH, HEIGHT)
            decor.viewTreeObserver.dispatchOnGlobalLayout()
            decor.viewTreeObserver.dispatchOnPreDraw()
        }
    }

    private companion object {
        const val WIDTH = 1076
        const val HEIGHT = 1200
        const val STATUS_BAR = 123
        const val KEYBOARD_BAR = 165
        val CUTOUT = Rect(0, 0, 123, 123)
        const val TEST_TITLE = "Weekend plan"
        val TEST_BODY = """
            # Saturday
            Market in the **morning**, then a *slow* lunch.

            ## Shopping
            - [x] Bread
            - [ ] Milk
            - [ ] Apples

            1. Pack the bag
            2. Water the plants

            > Leave the bike at home.

            `ferry 10:40` · ~~bus~~

            | Item | Price |
            | --- | --- |
            | Coffee | 3 |
            | Cake | 4 |
        """.trimIndent()
    }
}
