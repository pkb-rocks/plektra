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
import rocks.pkb.plektra.inputmethod.ui.EmojiPickerView

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

    @Test
    fun pickerAdaptsColumnsAndKeepsControlsInsideConstrainedHeight() {
        val picker = EmojiPickerView(activity)
        picker.configureSoftwareKeyboardMode(700, {})
        fun measure(width: Int) {
            picker.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(350, View.MeasureSpec.AT_MOST)
            )
            picker.layout(0, 0, picker.measuredWidth, picker.measuredHeight)
        }
        measure(1856)
        val wide = field(picker, "columns") as Int
        measure(840)
        assertTrue("column count follows actual view width", (field(picker, "columns") as Int) < wide)
        val vertical = field(picker, "vertical") as View
        val close = field(picker, "closeButton") as View
        val controls = close.parent as View
        assertTrue("stack stays inside measured picker", vertical.bottom <= picker.measuredHeight)
        assertTrue("bottom controls remain visible", controls.bottom <= vertical.height)
        assertTrue("controls have height", controls.height > 0)
    }

    @Test
    fun pickerFitsTheContentHeightOfALandscapeOuterDisplay() {
        val content = ImeContentLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val picker = EmojiPickerView(activity)
        picker.configureSoftwareKeyboardMode(701, {})
        picker.configureControls(147)
        content.addView(picker)
        content.measure(View.MeasureSpec.makeMeasureSpec(2281, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(573, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 2281, 573)
        val vertical = field(picker, "vertical") as View
        val controls = (field(picker, "closeButton") as View).parent as View
        assertTrue("picker fits bounded IME content", picker.bottom <= content.height)
        assertTrue("controls visible inside content", picker.top + vertical.top + controls.bottom <= content.height)
        assertEquals(147, controls.height)
    }

    @Test
    fun abcLabelFitsItsKeyAtLargeFontScale() {
        val metrics = activity.resources.displayMetrics
        @Suppress("DEPRECATION")
        val previous = metrics.scaledDensity
        try {
            @Suppress("DEPRECATION")
            metrics.scaledDensity = metrics.density * 1.5f
            val view = AospKeyboardView(activity).apply { keypad = SoftwareKeypad.PHONE }
            view.measure(View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 840, 500)
            @Suppress("UNCHECKED_CAST")
            val visual = (field(view, "keys") as List<Any>).first {
                field(field(it, "spec")!!, "type").toString() == "LAYOUT"
            }.let { field(it, "visualRect") as RectF }
            fun draw() = Bitmap.createBitmap(840, 500, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            view.layoutKeyLabel = "\u200B"
            val blank = draw()
            view.layoutKeyLabel = "ABC"
            val label = draw()
            val bounds = android.graphics.Rect().also { visual.round(it) }
            val changed = changedBounds(blank, label)
            assertTrue("label draws", changed != null)
            assertTrue("ABC $changed fits $bounds", bounds.contains(changed!!))
        } finally {
            @Suppress("DEPRECATION")
            metrics.scaledDensity = previous
        }
    }

    private fun idleUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun layOutAgain() {
        val root = bar.getLayout()!!.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun picker() = field(bar, "emojiPickerView") as EmojiPickerView

    /** The emoji cells the picker shows now (the grid or the search results). */
    private fun cells(picker: EmojiPickerView): List<TextView> {
        val grid = field(picker, "recyclerView") as androidx.recyclerview.widget.RecyclerView
        return (0 until grid.childCount).mapNotNull { grid.getChildAt(it) as? TextView }
            .filter { it.text.isNotEmpty() && it.contentDescription != null }
    }

    /** The emoji picker as the software keyboard's emoji key opens it, loaded. */
    private fun openPicker(): EmojiPickerView {
        if (sym.currentSymPage() != 4) sym.openEmojiPickerPage()
        render()
        val picker = picker()
        idleUntil("the picker to load") {
            layOutAgain()
            (field(picker, "loadingView") as View).visibility == View.GONE && cells(picker).isNotEmpty()
        }
        return picker
    }

    private fun closePicker() {
        sym.closeSymPage()
        render()
    }

    private fun search(picker: EmojiPickerView, query: String) {
        picker.showSearch()
        render()
        val field = field(picker, "searchField") as android.widget.EditText
        field.setText("")
        assertTrue(picker.handleSearchTextInput(query))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        showingResults(picker)
    }

    @Suppress("UNCHECKED_CAST")
    private fun results(picker: EmojiPickerView) =
        field(picker, "lastSearchResults") as List<rocks.pkb.plektra.data.emoji.EmojiSearchRepository.EmojiSearchResult>

    /** Waits until the grid shows the current search results (their list is diffed off the main thread). */
    private fun showingResults(picker: EmojiPickerView) {
        val adapter = field(picker, "searchAdapter") as androidx.recyclerview.widget.ListAdapter<*, *>
        idleUntil("the results in the grid") {
            layOutAgain()
            val shown = cells(picker).size
            adapter.currentList == results(picker) && shown in 1..results(picker).size
        }
    }

    @Test
    fun aSkinTonedOrZwjEmojiSearchedForIsTheOneTyped() {
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        showKeyboard(StatusBarController.Mode.INPUT_VIEW)
        for (query in listOf("👋🏽", "👩🏽‍💻", "🫱🏻‍🫲🏼", "👋")) {
            commits.clear()
            val picker = openPicker()
            search(picker, query)
            val first = cells(picker).first()
            assertEquals("$query shown", query, first.contentDescription)
            first.performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(query), commits)
            closePicker()
        }

        // Enter types the top result the same way
        commits.clear()
        val picker = openPicker()
        search(picker, "👩🏽‍💻")
        assertTrue(picker.handleSearchKeyDown(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf("👩🏽‍💻"), commits)
    }

    @Test
    fun aWordSearchStillTypesTheDefaultSkinTone() {
        rocks.pkb.plektra.data.emoji.EmojiSkinTone.set(activity, 3)
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        showKeyboard(StatusBarController.Mode.INPUT_VIEW)
        val picker = openPicker()
        search(picker, "waving hand")
        val wave = cells(picker).first { it.contentDescription.toString().startsWith("👋") }
        wave.performClick()
        assertEquals(listOf("👋🏽"), commits)
    }

    @Test
    fun aCategoryTabTappedWhileSearchingShowsThatCategorysResults() {
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        showKeyboard(StatusBarController.Mode.INPUT_VIEW)
        val picker = openPicker()
        search(picker, "cat")
        val all = results(picker)
        assertTrue(all.map { it.categoryId }.distinct().size > 1)
        val searchField = field(picker, "searchField") as android.widget.EditText
        val tabs = field(picker, "tabRow") as ViewGroup
        // The recents tab comes in once an emoji is typed
        @Suppress("UNCHECKED_CAST")
        fun tab(id: String) = tabs.getChildAt((field(picker, "tabCategoryIds") as List<String>).indexOf(id).also { check(it >= 0) { id } })

        tab("ANIMALS_AND_NATURE").performClick()
        showingResults(picker)
        val animals = results(picker)
        assertTrue(animals.isNotEmpty())
        assertTrue(animals.all { it.categoryId == "ANIMALS_AND_NATURE" })
        assertEquals(all.filter { it.categoryId == "ANIMALS_AND_NATURE" }.map { it.entry.base }, animals.map { it.entry.base })
        assertEquals("cat", searchField.text.toString())
        // What the grid shows and types is the narrowed result
        assertEquals(animals.size, cells(picker).size)
        cells(picker).first().performClick()
        assertEquals(listOf(animals.first().entry.base), commits)

        // Another category, then the same tab again: every result again
        tab("SMILEYS_AND_EMOTION").performClick()
        showingResults(picker)
        assertTrue(results(picker).isNotEmpty())
        assertTrue(results(picker).all { it.categoryId == "SMILEYS_AND_EMOTION" })
        tab("SMILEYS_AND_EMOTION").performClick()
        showingResults(picker)
        assertEquals(all, results(picker))

        // Recents: the results among the recently used emoji
        idleUntil("the typed emoji in the recents") {
            animals.first().entry.base in rocks.pkb.plektra.data.emoji.RecentEmojiManager.getRecentEmojis(activity)
        }
        tab(rocks.pkb.plektra.data.emoji.EmojiRepository.RECENTS_CATEGORY_ID).performClick()
        showingResults(picker)
        assertEquals(listOf(animals.first().entry.base), results(picker).map { it.entry.base })

        // Without a search the tabs go to their categories as before
        searchField.setText("")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        tab("FLAGS").performClick()
        assertEquals("FLAGS", field(picker, "selectedCategoryId"))
        assertEquals(null, field(picker, "searchCategoryFilter"))
    }

    /** Whether the picker's cells draw their emoji with the bundled font. */
    private fun drawnWithBundledFont(picker: EmojiPickerView): Boolean {
        val spans = cells(picker).map { cell ->
            val text = cell.text
            text is android.text.Spanned &&
                text.getSpans(0, text.length, android.text.style.ReplacementSpan::class.java).isNotEmpty()
        }
        check(spans.all { it } || spans.none { it }) { "mixed: $spans" }
        return spans.first()
    }

    @Test
    fun thePickerFollowsEveryEmojiStyleChange() {
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        showKeyboard(StatusBarController.Mode.INPUT_VIEW)
        assertTrue(drawnWithBundledFont(openPicker()))
        for ((change, style) in listOf(
            EmojiRendering.Style.SYSTEM, EmojiRendering.Style.NOTO,
            EmojiRendering.Style.SYSTEM, EmojiRendering.Style.NOTO, EmojiRendering.Style.SYSTEM
        ).withIndex()) {
            closePicker()
            EmojiRendering.setStyle(activity, style)
            // The emoji keys show meanwhile
            sym.openEmojiPage()
            render()
            assertEquals("change ${change + 1} to $style", style == EmojiRendering.Style.NOTO, drawnWithBundledFont(openPicker()))
        }
    }

    /** Pixels inside the key [label] that differ from its fill: a drawn label's ink. */
    private fun inkIn(view: AospKeyboardView, label: String): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val rect = keyRect(view, label)
        val inset = (rect.height() * 0.2f).toInt()
        val left = rect.left.toInt() + inset
        val top = rect.top.toInt() + inset
        val right = rect.right.toInt() - inset
        val bottom = rect.bottom.toInt() - inset
        val fill = bitmap.getPixel(left, top)
        var ink = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val difference = kotlin.math.abs(Color.red(pixel) - Color.red(fill)) +
                kotlin.math.abs(Color.green(pixel) - Color.green(fill)) +
                kotlin.math.abs(Color.blue(pixel) - Color.blue(fill))
            if (difference > 60) ink++
        }
        return ink
    }

    @Test
    fun keysWithNothingOnTheRecentsAndVariantsPagesAreDrawnEmptyAndDoNothing() {
        listOf("😀", "🐱", "👋").forEach { rocks.pkb.plektra.data.emoji.RecentEmojiManager.addRecentEmoji(activity, it) }
        sym.closeSymPage()
        sym.openEmojiPage()
        sym.turnPage(1) // the recents
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        showKeyboard(StatusBarController.Mode.INPUT_VIEW)
        val keys = sym.currentSymMappings()!!
        assertEquals("👋", keys[KeyEvent.KEYCODE_W])
        assertNull(keys[KeyEvent.KEYCODE_M])
        val keyboard = softwareKeyboard()

        // The recents' keys show them; the keys after them nothing, not their letters
        assertTrue(inkIn(keyboard, "w") > 40)
        for (free in listOf("t", "s", "m")) assertEquals("$free drawn empty", 0, inkIn(keyboard, free))
        // The keys outside the page keep their labels
        assertTrue(inkIn(keyboard, ".") > 10)
        assertTrue(inkIn(keyboard, "q") > 10)
        assertTrue(inkIn(keyboard, "a") > 10)
        // A free key types nothing, previews nothing and leaves the page as it is
        touch(keyboard, "m")
        assertEquals(emptyList<String>(), commits)
        assertNull(field(keyboard, "previewPopupState"))
        assertEquals(keys, sym.currentSymMappings())
        touch(keyboard, "w")
        assertEquals(listOf("👋"), commits)

        // The variants: P (one page of them) and A (not a mapped emoji) hold nothing
        commits.clear()
        keyboard.longPressTimeoutMs = 50L
        touch(keyboard, "w", holdMs = 80L)
        val variants = sym.currentSymMappings()!!
        assertEquals("👋🏻", variants[KeyEvent.KEYCODE_E])
        assertNull(variants[KeyEvent.KEYCODE_P])
        for (free in listOf("p", "a", "o")) assertEquals("$free drawn empty", 0, inkIn(keyboard, free))
        assertTrue(inkIn(keyboard, "q") > 10)
        touch(keyboard, "p")
        touch(keyboard, "a")
        assertEquals(emptyList<String>(), commits)
        assertEquals(variants, sym.currentSymMappings())
        touch(keyboard, "e")
        assertEquals(listOf("👋🏻"), commits)

        // Without SYM the letters are there again
        sym.closeSymPage()
        render()
        for (letter in listOf("m", "p", "a")) assertTrue("$letter drawn", inkIn(softwareKeyboard(), letter) > 10)
    }
}
