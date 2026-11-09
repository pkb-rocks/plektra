package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.TextView
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.core.SymPagedLayer
import rocks.pkb.plektra.data.emoji.EmojiRendering
import rocks.pkb.plektra.data.emoji.TestEmojiFont
import rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView

/**
 * The emoji pages on the keyboards the user touches: the full software keyboard (the AOSP key
 * grid the status bar controller builds) and the hardware keyboard's on-screen SYM grid, wired
 * to the SYM layer as the input method service wires them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h850dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmojiPagesSurfacesTest {
    private lateinit var activity: Activity
    private lateinit var sym: SymLayoutController
    private lateinit var bar: StatusBarController
    private val commits = mutableListOf<String>()
    private val connection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, args ->
        if (method.name == "commitText") commits.add(args!![0].toString())
        when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
    } as InputConnection

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SettingsManager.getPreferences(activity).edit().clear().commit()
        activity.getSharedPreferences("recent_emojis_prefs", 0).edit().clear().commit()
        SettingsManager.setSymAutoClose(activity, false)
        TestEmojiFont.install(activity, drawable = true)
        val prefs = SettingsManager.getPreferences(activity)
        sym = SymLayoutController(activity, prefs, AlternateCharacterManager(activity.assets, prefs, activity))
        sym.openEmojiPage()
    }

    @After
    fun tearDown() {
        EmojiRendering.setStyle(activity, EmojiRendering.Style.NOTO)
    }

    private fun snapshot() = StatusBarController.StatusSnapshot(
        false, false, false, false, false, false, false, false, false, false, sym.currentSymPage()
    )

    /** The status bar controller as the input method service drives it. */
    private fun showKeyboard(mode: StatusBarController.Mode) {
        bar = StatusBarController(activity, mode)
        bar.onSymPageKeyTapped = { keyCode -> sym.handlePagedTouchTap(keyCode, connection) { render() } }
        bar.onSymPageKeyLongPressed = { keyCode -> sym.handlePagedTouchLongPress(keyCode).also { if (it) render() } }
        render()
    }

    private fun render() {
        bar.update(snapshot(), inputConnection = connection, symMappings = sym.currentSymMappings())
        val root = bar.getLayout()!!
        if (root.parent == null) activity.setContentView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            runCatching { return type.getDeclaredField(name).apply { isAccessible = true }.get(target) }
            type = type.superclass
        }
        error("no field $name")
    }

    private fun softwareKeyboard() = field(bar, "softwareKeyboardView") as AospKeyboardView

    private fun keyRect(view: AospKeyboardView, label: String): RectF {
        @Suppress("UNCHECKED_CAST")
        val key = (field(view, "keys") as List<Any>).first { field(field(it, "spec")!!, "label") == label }
        return field(key, "hitRect") as RectF
    }

    private fun touch(view: AospKeyboardView, label: String, holdMs: Long = 0L) {
        val rect = keyRect(view, label)
        view.dispatchTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
        if (holdMs > 0) Shadows.shadowOf(Looper.getMainLooper()).idleFor(holdMs, TimeUnit.MILLISECONDS)
        view.dispatchTouchEvent(MotionEvent.obtain(0L, holdMs + 10, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun startedActivity() = Shadows.shadowOf(activity.application).nextStartedActivity

    @Test
    fun fullSoftwareKeyboardTurnsPagesAndShowsSkinTonesInsteadOfTypingTheArrows() {
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        showKeyboard(StatusBarController.Mode.INPUT_VIEW)
        val home = sym.currentSymMappings()!!
        assertEquals(SymPagedLayer.NEXT_LABEL, home[KeyEvent.KEYCODE_P])

        // Tapping P turns the page: nothing is typed
        touch(softwareKeyboard(), "p")
        assertEquals(emptyList<String>(), commits)
        val smileys = sym.currentSymMappings()!!
        assertNotEquals(home[KeyEvent.KEYCODE_W], smileys[KeyEvent.KEYCODE_W])

        // Holding P jumps to the next section, without opening the customization
        softwareKeyboard().longPressTimeoutMs = 50L
        touch(softwareKeyboard(), "p", holdMs = 80L)
        assertNull(startedActivity())
        assertEquals(emptyList<String>(), commits)
        assertEquals("👋", sym.currentSymMappings()!![KeyEvent.KEYCODE_W])

        // Holding an emoji shows its tones on the keys; tapping one types it
        softwareKeyboard().longPressTimeoutMs = 50L
        touch(softwareKeyboard(), "w", holdMs = 80L)
        assertNull(startedActivity())
        assertEquals("👋🏻", sym.currentSymMappings()!![KeyEvent.KEYCODE_E])
        touch(softwareKeyboard(), "e")
        assertEquals(listOf("👋🏻"), commits)

        // A plain tap types the emoji on the key
        touch(softwareKeyboard(), "w")
        assertEquals(listOf("👋🏻", "👋"), commits)
    }

    @Test
    fun hardwareSymGridTurnsPagesAndShowsSkinTones() {
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        showKeyboard(StatusBarController.Mode.CANDIDATES_ONLY)
        fun key(letter: String): View {
            val grid = field(bar, "emojiKeyboardContainer") as ViewGroup
            fun find(view: View): View? {
                if (view.isLongClickable && view is ViewGroup && labels(view).contains(letter)) return view
                return (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
            }
            return requireNotNull(find(grid)) { "no key $letter" }
        }
        key("P").performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        key("P").performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), commits)
        key("P").performLongClick()
        assertNull(startedActivity())
        assertEquals("👋", sym.currentSymMappings()!![KeyEvent.KEYCODE_W])
        key("W").performLongClick()
        assertNull(startedActivity())
        assertEquals("👋🏻", sym.currentSymMappings()!![KeyEvent.KEYCODE_E])
        key("E").performClick()
        assertEquals(listOf("👋🏻"), commits)
    }

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun fullSoftwareKeyboardDrawsEmojiTheSystemFontLacksWithTheBundledFont() {
        val view = AospKeyboardView(activity)
        val parent = android.widget.FrameLayout(activity)
        parent.addView(view, android.widget.FrameLayout.LayoutParams(1000, 240))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 1000, 240)
        view.symPageActive = true
        // Unicode 18 emoji: Robolectric's system emoji font (Android 13) has none of them
        val emoji = mapOf(
            KeyEvent.KEYCODE_W to "🫹", // 🫹
            KeyEvent.KEYCODE_E to "🧑‍🩰" // 🧑‍🩰
        )
        view.symPageLabels = emoji

        fun colourfulPixels(label: String): Int {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val rect = keyRect(view, label)
            var count = 0
            for (x in rect.left.toInt() until rect.right.toInt()) for (y in rect.top.toInt() until rect.bottom.toInt()) {
                val pixel = bitmap.getPixel(x, y)
                val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (channels.max() - channels.min() > 60) count++
            }
            return count
        }
        assertTrue("🫹 in colour", colourfulPixels("w") > 40)
        assertTrue("🧑‍🩰 in colour", colourfulPixels("e") > 40)

        // With the system font chosen the labels stay plain text
        EmojiRendering.setStyle(activity, EmojiRendering.Style.SYSTEM)
        view.symPageLabels = emptyMap()
        view.symPageLabels = emoji
        assertEquals(0, colourfulPixels("w"))
    }
}
