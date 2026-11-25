package rocks.pkb.plektra

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rocks.pkb.plektra.data.mappings.AltModifierMappingResolver
import rocks.pkb.plektra.inputmethod.KeyboardEventTracker
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

internal data class TextMotionEvent(val token: Long, val value: String)

internal data class IdleStroke(val label: String, val atMs: Long, val modifierLabel: String? = null)

internal class IdlePlaybackCursor(
    private val text: CharArray,
    private val altKeyForCharacter: Map<Char, String> = emptyMap()
) {
    var index: Int = 0
        private set
    private var characterPendingAfterShift = false
    private var nextAtMs = 0L

    fun resumeImmediately() {
        nextAtMs = 0L
    }

    fun next(now: Long, typing: Boolean): IdleStroke? {
        if (typing) {
            nextAtMs = now + 800L
            return null
        }
        if (text.isEmpty() || now < nextAtMs) return null
        val character = text[index]
        if (character.isUpperCase() && !characterPendingAfterShift) {
            characterPendingAfterShift = true
            nextAtMs = now + 150L
            return IdleStroke("Shift", now)
        }
        characterPendingAfterShift = false
        val altKey = altKeyForCharacter[character]
        val label = when {
            altKey != null -> altKey
            character == ' ' -> "Space"
            character == '\n' -> "Enter"
            else -> character.uppercase()
        }
        val delay = when (character) {
            '\n' -> 1100L
            '.' -> 900L
            ',' -> 650L
            ' ' -> 420L
            else -> 230L + (index * 47L % 170L)
        }
        index = (index + 1) % text.size
        nextAtMs = now + delay + if (index == 0) 3000L else 0L
        return IdleStroke(label, now, modifierLabel = if (altKey != null) "Alt" else null)
    }
}

@Composable
internal fun MainMotionBackground(
    preference: MainBackgroundPreference,
    textEvent: TextMotionEvent?,
    hardwareEvent: KeyboardEventTracker.KeyEventInfo?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var active by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { MotionBackgroundView(context) },
        update = { view ->
            view.mode = MainBackgroundPreferences.resolve(preference)
            view.active = active
            textEvent?.let(view::acceptTextEvent)
            hardwareEvent?.let(view::acceptHardwareEvent)
        }
    )
}

internal class MotionBackgroundView(context: Context) : View(context), Choreographer.FrameCallback {
    var mode: MainBackgroundPreference = MainBackgroundPreference.Strings
        set(value) {
            if (field == value) return
            field = value
            lastFrameNanos = 0L
            if (value == MainBackgroundPreference.Keyboard) {
                idleCursor.resumeImmediately()
            }
            invalidate()
        }
    var active: Boolean = false
        set(value) {
            field = value
            updateFrameSubscription()
        }

    private val accent = Color.parseColor(
        if (BuildConfig.RELEASE_CHANNEL == "nightly") "#2F80ED" else "#FC7A0F"
    )
    private val ink = Color.parseColor(
        if (BuildConfig.RELEASE_CHANNEL == "nightly") "#15243A" else "#332515"
    )
    private val paper = Color.parseColor(
        if (BuildConfig.RELEASE_CHANNEL == "nightly") "#F1F5FA" else "#F7F4EF"
    )
    private val shiftIcon = icon(R.drawable.shift_24)
    private val ctrlIcon = icon(R.drawable.keyboard_control_key_24)
    private val altIcon = icon(R.drawable.keyboard_option_key_24)
    private val backspaceIcon = icon(R.drawable.backspace_24)
    private val enterIcon = icon(R.drawable.keyboard_return_24)
    private val keyTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var statusBarHeight = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val waves = Array(6) { Wave() }
    private var waveCount = 0
    private var time = 0f
    private var energy = 0f
    private var flow = 0f
    private var lastTextToken = Long.MIN_VALUE
    private var lastHardwareSignature = Long.MIN_VALUE
    private var lastHardwareStrikeMs = Long.MIN_VALUE
    private var lastHardwareLabel = ""
    private var lastInputMs = Long.MIN_VALUE
    private var lastFrameNanos = 0L
    private var framePosted = false

    private val keys = createTitan2Keys()
    private val altKeyForCharacter = buildMap {
        AltModifierMappingResolver.resolve(context.assets, context).forEach { (keyCode, output) ->
            val character = output.singleOrNull() ?: return@forEach
            val label = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            if (label.length == 1 && label[0] in 'A'..'Z') put(character, label)
        }
    }
    private val idleText by lazy {
        MainBackgroundCipher.decodePackaged(MainBackgroundCipher.IDLE_SCORE_PACKAGED)
    }
    private val idleCursor by lazy { IdlePlaybackCursor(idleText, altKeyForCharacter) }
    private var ambientGain = 1f

    init {
        setWillNotDraw(false)
        paint.strokeCap = Paint.Cap.ROUND
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        updateFrameSubscription()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        statusBarHeight = if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.statusBars()).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateFrameSubscription()
    }

    fun acceptTextEvent(event: TextMotionEvent) {
        if (event.token == lastTextToken) return
        lastTextToken = event.token
        val value = event.value
        val now = SystemClock.uptimeMillis()
        lastInputMs = now
        exciteStrings(value)
        val character = value.lastOrNull()
        val altKey = character?.let(altKeyForCharacter::get)
        val label = altKey ?: inputLabel(value)
        if (now - lastHardwareStrikeMs > 90L || label != lastHardwareLabel) {
            strikeKey(label, now, held = false)
            if (altKey != null) strikeKey("Alt", now, held = false)
        }
    }

    fun acceptHardwareEvent(event: KeyboardEventTracker.KeyEventInfo) {
        val signature = event.eventTimeUptimeMs xor (event.keyCode.toLong() shl 32) xor event.action.hashCode().toLong()
        if (signature == lastHardwareSignature) return
        lastHardwareSignature = signature
        val label = keyCodeLabel(event.keyCode) ?: return
        val now = SystemClock.uptimeMillis()
        lastInputMs = now
        val down = event.action.contains("DOWN")
        val key = keyForLabel(label) ?: return
        if (down) {
            key.down = true
            key.lastStrike = now
            lastHardwareStrikeMs = now
            lastHardwareLabel = label
        } else {
            key.down = false
            key.lastStrike = now
        }
    }

    private fun updateFrameSubscription() {
        val shouldRun = active && isAttachedToWindow && isShown
        if (shouldRun && !framePosted) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(this)
        } else if (!shouldRun) {
            stopFrames()
        }
    }

    private fun stopFrames() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(this)
        framePosted = false
        lastFrameNanos = 0L
        keys.forEach { it.down = false }
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!active || !isAttachedToWindow || !isShown) return
        val reduced = !ValueAnimator.areAnimatorsEnabled()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else {
            min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.034f)
        }
        lastFrameNanos = frameTimeNanos
        if (reduced) {
            energy = 0f
            flow = 0f
            waveCount = 0
            keys.forEach { it.position = 0f; it.velocity = 0f }
        } else {
            when (mode) {
                MainBackgroundPreference.Keyboard -> updateKeys(dt, SystemClock.uptimeMillis())
                else -> updateStrings(dt)
            }
        }
        invalidate()
        if (!reduced) {
            updateFrameSubscription()
        }
    }

    private fun updateStrings(dt: Float) {
        energy *= exp((-dt * 1.25f).toDouble()).toFloat()
        flow += (energy - flow) * (1f - exp((-dt * 1.6f).toDouble()).toFloat())
        time += dt * 0.72f * (1f + flow * 0.08f)
        var target = 0
        for (index in 0 until waveCount) {
            val wave = waves[index]
            wave.age += dt
            if (wave.age < 4f) {
                if (target != index) waves[target].copyFrom(wave)
                target++
            }
        }
        waveCount = target
    }

    private fun updateKeys(dt: Float, now: Long) {
        val typing = keys.any { it.down } || now - lastInputMs < 700L
        val rate = if (typing) 7f else 0.7f
        ambientGain += ((if (typing) 0f else 1f) - ambientGain) *
            (1f - exp((-dt * rate).toDouble()).toFloat())
        idleCursor.next(now, typing)?.let { stroke ->
            keyForLabel(stroke.label)?.idleStrike = stroke.atMs
            stroke.modifierLabel?.let { keyForLabel(it)?.idleStrike = stroke.atMs }
        }
        keys.forEach { key ->
            val idleAge = now - key.idleStrike
            val idlePress = !typing && idleAge < 115L
            val pressed = key.down || key.until > now
            val target = when {
                pressed -> 7f
                idlePress -> 5.5f * ambientGain
                else -> 0f
            }
            val step = dt / 3f
            repeat(3) {
                key.velocity += ((target - key.position) * 230f - key.velocity * 20f) * step
                key.position += key.velocity * step
            }
        }
    }

    private fun exciteStrings(value: String) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        energy = min(1.8f, energy + 0.34f)
        if (waveCount >= waves.size || waveCount > 0 && waves[waveCount - 1].age <= 0.18f) return
        val region = stringRegion(value)
        waves[waveCount++].set(region.first, region.second, 0f)
    }

    private fun strikeKey(label: String, now: Long, held: Boolean) {
        keyForLabel(label)?.let { key ->
            key.lastStrike = now
            key.down = held
            if (!held) key.until = now + 150L
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            MainBackgroundPreference.Keyboard -> drawKeyboard(canvas, SystemClock.uptimeMillis())
            else -> drawStrings(canvas)
        }
    }

    private fun drawStrings(canvas: Canvas) {
        val scaleX = width / 640f
        val bandTop = height * 0.30f
        val scaleY = min(height * 0.55f / 170f, scaleX)
        canvas.save()
        canvas.clipRect(0f, bandTop - 32f * scaleY, width.toFloat(), bandTop + 180f * scaleY)
        for (index in 0 until 27) {
            val layer = index % 3
            val depth = 0.6f + layer * 0.25f
            path.reset()
            var x = -32f
            var first = true
            while (x <= 672f) {
                var y = index * 3.7f + x * 0.018f
                y += sin(x * 0.007f - time * 0.65f + index * 0.2f) * (12f + layer * 3f)
                y += sin(x * 0.012f + time * 0.39f + index * 0.29f) * 4f * depth
                y += cos(x * 0.0038f - time * 0.28f + index * 0.19f) * 13f
                var pluck = 0.0
                for (waveIndex in 0 until waveCount) {
                    val wave = waves[waveIndex]
                    val distance = hypot((x - wave.x).toDouble(), ((index - wave.string) * 12).toDouble())
                    val front = distance - wave.age * 100.0
                    val attack = min(1.0, wave.age / 0.16)
                    val fade = min(1.0, max(0.0, (4.0 - wave.age) / 1.2))
                    val envelope = smooth(attack) * smooth(fade)
                    pluck += cos(front * 0.055) * exp(-front * front / 2000.0) *
                        exp(-wave.age * 0.65) * envelope * 10.0
                }
                y += (10.0 * tanh(pluck / 10.0)).toFloat()
                val px = x * scaleX
                val py = bandTop + y * scaleY
                if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
                x += 11f
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (index % 7 == 0) 1.8f else 0.75f) * resources.displayMetrics.density
            paint.color = if (index % 4 == 0 || index % 7 == 0) accent else paper
            paint.alpha = (255f * (0.15f + layer * 0.105f +
                0.1f * (1f + sin(time * 0.48f - index * 0.3f)) / 2f) * 0.92f).toInt()
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }

    private fun drawKeyboard(canvas: Canvas, now: Long) {
        val margin = width * 0.045f
        val boardWidth = width - margin * 2f
        val gap = max(3f, width * 0.005f)
        val rowHeight = min(width * 0.026f, resources.displayMetrics.density * 20f)
        val density = resources.displayMetrics.density
        val boardTop = max(height * 0.18f, statusBarHeight + 2f * density)
        val maximumTravel = 7f * density
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = keyTypeface
        canvas.save()
        canvas.rotate(-1.35f, width * 0.52f, boardTop + rowHeight * 2.4f)
        for (row in 0 until 4) {
            val start = ROW_STARTS[row]
            val end = ROW_STARTS[row + 1]
            var totalUnits = 0f
            for (index in start until end) totalUnits += keys[index].units
            val keyWidth = (boardWidth - gap * (end - start - 1)) / totalUnits
            var x = margin
            val y = boardTop + row * (rowHeight + gap * 1.3f)
            for (index in start until end) {
                val key = keys[index]
                val keyPixels = keyWidth * key.units
                val travel = maximumTravel * (key.position / 7f).coerceIn(0f, 1f)
                val idleAge = now - key.idleStrike
                val idleColor = exp((-max(0L, idleAge - 115L) / 280.0)).toFloat() * ambientGain
                val pressed = key.down || key.until > now
                val ghost = max(
                    if (pressed) 0.8f else 0.8f * exp((-(now - key.lastStrike) / 1600.0)).toFloat(),
                    idleColor * 0.6f
                )
                val light = min(0.7f, 0.08f + idleColor * 0.32f)
                val corner = 5f * density

                rect.set(
                    x,
                    y + maximumTravel + 1.7f * density,
                    x + keyPixels,
                    y + rowHeight + maximumTravel + 1.7f * density
                )
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(4, 8, 13)
                paint.alpha = 185
                canvas.drawRoundRect(rect, corner, corner, paint)

                val restingFace = blend(ink, paper, 0.13f)
                rect.set(x, y + maximumTravel, x + keyPixels, y + rowHeight + maximumTravel)
                paint.color = blend(restingFace, Color.BLACK, 0.32f)
                paint.alpha = 246
                canvas.drawRoundRect(rect, corner, corner, paint)

                rect.set(x, y + travel, x + keyPixels, y + rowHeight + travel)
                paint.color = blend(restingFace, accent, light)
                paint.alpha = 238
                canvas.drawRoundRect(rect, corner, corner, paint)
                if (ghost > 0.01f) {
                    paint.color = accent
                    paint.alpha = (ghost * 120f).toInt().coerceIn(0, 120)
                    canvas.drawRoundRect(rect, corner, corner, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 0.75f
                paint.color = blend(paper, accent, if (pressed) 0.8f else ghost)
                paint.alpha = (85 + ghost * 150).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rect, corner, corner, paint)
                paint.strokeWidth = density * 0.55f
                paint.color = paper
                paint.alpha = 42
                canvas.drawLine(
                    rect.left + corner,
                    rect.top + density * 2.2f,
                    rect.right - corner,
                    rect.top + density * 2.2f,
                    paint
                )
                drawKeyGlyph(canvas, key, rowHeight, ghost)
                x += keyPixels + gap
            }
        }
        canvas.restore()
    }

    private fun drawKeyGlyph(canvas: Canvas, key: KeyModel, rowHeight: Float, glow: Float) {
        val drawable = when (key.id) {
            "Shift" -> shiftIcon
            "Fn" -> ctrlIcon
            "Alt" -> altIcon
            "Backspace" -> backspaceIcon
            "Enter" -> enterIcon
            else -> null
        }
        if (drawable != null) {
            val size = rowHeight * if (key.row == 0) 0.52f else 0.48f
            val left = (rect.centerX() - size / 2f).toInt()
            val top = (rect.centerY() - size / 2f).toInt()
            drawable.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
            drawable.alpha = (205 + glow * 45f).toInt().coerceIn(0, 250)
            drawable.draw(canvas)
            return
        }
        if (key.id == "Back") {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 1.35f
            paint.color = paper
            paint.alpha = 220
            val half = rowHeight * 0.16f
            path.reset()
            path.moveTo(rect.centerX() + half * 0.6f, rect.centerY() - half)
            path.lineTo(rect.centerX() - half, rect.centerY())
            path.lineTo(rect.centerX() + half * 0.6f, rect.centerY() + half)
            path.close()
            canvas.drawPath(path, paint)
            return
        }
        if (key.id == "Overview") {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 1.25f
            paint.color = paper
            paint.alpha = 220
            val half = rowHeight * 0.14f
            canvas.drawRoundRect(
                rect.centerX() - half,
                rect.centerY() - half,
                rect.centerX() + half,
                rect.centerY() + half,
                half * 0.18f,
                half * 0.18f,
                paint
            )
            return
        }
        if (key.label.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = paper
        paint.alpha = 215
        paint.textSize = rowHeight * if (key.label.length > 2) 0.30f else 0.43f
        paint.typeface = keyTypeface
        canvas.drawText(
            key.label,
            rect.centerX(),
            rect.centerY() - (paint.ascent() + paint.descent()) / 2f,
            paint
        )
    }

    private fun stringRegion(value: String): Pair<Float, Int> {
        val letter = value.lowercase().lastOrNull() ?: 'l'
        val groups = arrayOf("aä", "e", "i", "oö", "uü", "bpdtgk", "fvwszhß", "lrmn", "cjqtyx")
        val regionsX = floatArrayOf(130f, 230f, 330f, 430f, 530f, 185f, 385f, 285f, 485f)
        val regionsString = intArrayOf(4, 9, 13, 18, 23, 6, 16, 11, 21)
        val index = groups.indexOfFirst { letter in it }.let { if (it < 0) 7 else it }
        return regionsX[index] to regionsString[index]
    }

    private fun inputLabel(value: String): String {
        if (value == DELETE_MARKER) return "Backspace"
        if (value == ENTER_MARKER || value == "\n") return "Enter"
        if (value == " ") return "Space"
        return value.lastOrNull()?.uppercase() ?: "Space"
    }

    private fun keyCodeLabel(keyCode: Int): String? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
        KeyEvent.KEYCODE_SYM -> "Sym"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
        KeyEvent.KEYCODE_BACK -> "Back"
        KeyEvent.KEYCODE_APP_SWITCH -> "Overview"
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_FUNCTION -> "Fn"
        else -> null
    }

    private fun createTitan2Keys(): Array<KeyModel> {
        val rows = arrayOf(
            arrayOf("Shift", "Sym", "Back", "Overview", "Fn", "Alt"),
            arrayOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            arrayOf("A", "S", "D", "F", "G", "H", "J", "K", "L", "Backspace"),
            arrayOf("Z", "X", "C", "V", "Space", "B", "N", "M", "Enter")
        )
        val result = ArrayList<KeyModel>(35)
        rows.forEachIndexed { row, labels ->
            labels.forEachIndexed { column, id ->
                val units = if (id == "Space") 2f else 1f
                val label = when (id) {
                    "Sym" -> "SYM"
                    "Backspace", "Enter", "Shift", "Back", "Overview", "Fn", "Alt" -> ""
                    "Space" -> ""
                    else -> id.lowercase().takeIf { id.length > 1 } ?: id
                }
                result += KeyModel(id, label, row, column, units)
            }
        }
        return result.toTypedArray()
    }

    private fun keyForLabel(label: String): KeyModel? = keys.firstOrNull { it.id == label }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }

    private fun icon(resource: Int): Drawable = requireNotNull(context.getDrawable(resource)).mutate().apply {
        setTint(paper)
    }

    private fun smooth(value: Double): Double = value * value * (3.0 - 2.0 * value)

    private class Wave(var x: Float = 0f, var string: Int = 0, var age: Float = 0f) {
        fun set(x: Float, string: Int, age: Float) { this.x = x; this.string = string; this.age = age }
        fun copyFrom(other: Wave) = set(other.x, other.string, other.age)
    }

    private class KeyModel(
        val id: String,
        val label: String,
        val row: Int,
        val column: Int,
        val units: Float,
        var down: Boolean = false,
        var until: Long = 0L,
        var lastStrike: Long = Long.MIN_VALUE / 2,
        var idleStrike: Long = Long.MIN_VALUE / 2,
        var position: Float = 0f,
        var velocity: Float = 0f
    )

    companion object {
        const val DELETE_MARKER = "\u0008"
        const val ENTER_MARKER = "\u000a"
        private val ROW_STARTS = intArrayOf(0, 6, 16, 26, 35)
    }
}
