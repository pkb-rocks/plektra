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

        fun keyPixels(label: String): IntArray {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val rect = keyRect(view, label)
            val x = rect.left.toInt()
            val y = rect.top.toInt()
            return IntArray(rect.width().toInt() * rect.height().toInt()).also {
                bitmap.getPixels(it, 0, rect.width().toInt(), x, y, rect.width().toInt(), rect.height().toInt())
            }
        }
        fun colourful(pixels: IntArray) = pixels.count { pixel ->
            val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            channels.max() - channels.min() > 60
        }
        val bundled = emoji.keys.associateWith { keyPixels(if (it == KeyEvent.KEYCODE_W) "w" else "e") }
        assertTrue("🫹 in colour", colourful(bundled.getValue(KeyEvent.KEYCODE_W)) > 40)
        assertTrue("🧑‍🩰 in colour", colourful(bundled.getValue(KeyEvent.KEYCODE_E)) > 40)

        // The system font (on a device: boxes for these; here: the test machine's emoji font)
        // draws them differently: the labels above came from the bundled font
        EmojiRendering.setStyle(activity, EmojiRendering.Style.SYSTEM)
        view.symPageLabels = emptyMap()
        view.symPageLabels = emoji
        for ((keyCode, pixels) in bundled) {
            val system = keyPixels(if (keyCode == KeyEvent.KEYCODE_W) "w" else "e")
            assertTrue(system.indices.count { system[it] != pixels[it] } > 40)
        }
    }

    private fun colourful(pixel: Int): Boolean {
        val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        return channels.max() - channels.min() > 60
    }

    /** The bounds of the pixels that differ between two drawings of the same size. */
    private fun changedBounds(before: Bitmap, after: Bitmap): android.graphics.Rect? {
        var bounds: android.graphics.Rect? = null
        for (y in 0 until after.height) for (x in 0 until after.width) {
            if (before.getPixel(x, y) != after.getPixel(x, y)) {
                bounds = (bounds ?: android.graphics.Rect(x, y, x + 1, y + 1)).apply { union(x, y) }
            }
        }
        return bounds
    }

    @Test
    fun theKeyPreviewOfATouchedEmojiIsDrawnWithTheBundledFont() {
        // The keyboard at the bottom of the window, its previews drawn above it in the window
        val parent = android.widget.FrameLayout(activity)
        val view = AospKeyboardView(activity)
        parent.addView(view, android.widget.FrameLayout.LayoutParams(1000, 240).apply { topMargin = 360 })
        activity.setContentView(parent, ViewGroup.LayoutParams(1000, 600))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        view.symPageActive = true
        view.symPageLabels = mapOf(KeyEvent.KEYCODE_W to "🫹") // 🫹: no system emoji font has it yet
        val root = view.rootView

        fun previewAfterTouchDown(): Pair<Bitmap, RectF> {
            val rect = keyRect(view, "w")
            view.dispatchTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val popup = field(view, "previewPopupState")!!
            assertEquals("🫹", field(popup, "label"))
            val location = IntArray(2).also { view.getLocationInWindow(it) }
            val popupRect = RectF(field(popup, "rect") as RectF).apply { offset(location[0].toFloat(), location[1].toFloat()) }
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            view.dispatchTouchEvent(MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_CANCEL, rect.centerX(), rect.centerY(), 0))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            return bitmap to popupRect
        }

        val (noto, popupRect) = previewAfterTouchDown()
        val inPreview = android.graphics.Rect().also { RectF(popupRect).roundOut(it) }
        var colour = 0
        var ink: android.graphics.Rect? = null
        for (y in 0 until noto.height) for (x in 0 until noto.width) {
            if (y >= inPreview.bottom || !colourful(noto.getPixel(x, y))) continue
            // Above the key row: only the preview draws there
            colour++
            ink = (ink ?: android.graphics.Rect(x, y, x + 1, y + 1)).apply { union(x, y) }
        }
        assertTrue("the preview shows the emoji in colour: $colour", colour > 200)
        // The emoji sits inside the preview, about its middle
        assertTrue("$ink in $inPreview", inPreview.contains(ink!!))
        assertTrue(kotlin.math.abs(ink.exactCenterX() - popupRect.centerX()) < popupRect.width() * 0.1f)
        assertTrue(kotlin.math.abs(ink.exactCenterY() - popupRect.centerY()) < popupRect.height() * 0.15f)
        assertTrue("a preview-sized emoji: ${ink.height()}", ink.height() > popupRect.height() * 0.35f)

        // With the system font the same touch draws a different preview
        EmojiRendering.setStyle(activity, EmojiRendering.Style.SYSTEM)
        val (system, _) = previewAfterTouchDown()
        var differing = 0
        for (y in inPreview.top.coerceAtLeast(0) until inPreview.bottom) for (x in inPreview.left.coerceAtLeast(0) until inPreview.right.coerceAtMost(system.width)) {
            if (system.getPixel(x, y) != noto.getPixel(x, y)) differing++
        }
        assertTrue("system and bundled previews differ: $differing", differing > 200)
    }

    @Test
    fun keyLabelsFitTheirKeysOnANarrowKeyboardAtLargeFontScale() {
        val metrics = activity.resources.displayMetrics
        @Suppress("DEPRECATION")
        val scaled = metrics.scaledDensity
        try {
            @Suppress("DEPRECATION")
            metrics.scaledDensity = metrics.density * 1.5f
            // 320dp wide at 420dpi
            val width = 840
            val view = AospKeyboardView(activity)
            val parent = android.widget.FrameLayout(activity)
            parent.addView(view, android.widget.FrameLayout.LayoutParams(width, 500))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)
            )
            parent.layout(0, 0, width, 500)
            view.symPageActive = true
            fun drawn(): Bitmap = Bitmap.createBitmap(width, 500, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            @Suppress("UNCHECKED_CAST")
            val visual = (field(view, "keys") as List<Any>).first { field(field(it, "spec")!!, "label") == "w" }
                .let { field(it, "visualRect") as RectF }
            val inKey = android.graphics.Rect().also { visual.round(it) }

            for (label in listOf(
                "🫹", // a new emoji
                "🧑‍🩰", // a ZWJ sequence
                "👋🏽", // a skin-toned emoji
                "🫹🫹🫹", // several emoji
                "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧" // a kaomoji
            )) {
                view.symPageLabels = mapOf(KeyEvent.KEYCODE_W to "")
                val blank = drawn()
                view.symPageLabels = mapOf(KeyEvent.KEYCODE_W to label)
                val withLabel = drawn()
                val changed = changedBounds(blank, withLabel)
                assertTrue("$label drawn", changed != null)
                assertTrue("$label: drawn $changed, key $inKey", inKey.contains(changed!!))
                if (label.startsWith("(")) continue
                var colour = 0
                for (y in changed.top until changed.bottom) for (x in changed.left until changed.right) {
                    if (colourful(withLabel.getPixel(x, y))) colour++
                }
                assertTrue("$label in colour: $colour", colour > 100)
            }
            // A single emoji still fills most of the key's width
            view.symPageLabels = mapOf(KeyEvent.KEYCODE_W to "🫹")
            val single = drawn()
            var ink: android.graphics.Rect? = null
            for (y in inKey.top until inKey.bottom) for (x in inKey.left until inKey.right) {
                if (colourful(single.getPixel(x, y))) ink = (ink ?: android.graphics.Rect(x, y, x + 1, y + 1)).apply { union(x, y) }
            }
            assertTrue("$ink in $inKey", ink!!.width() > inKey.width() * 0.5f)
        } finally {
            @Suppress("DEPRECATION")
            metrics.scaledDensity = scaled
        }
    }
}
