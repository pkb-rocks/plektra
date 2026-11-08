package rocks.pkb.plektra.inputmethod.aospkeyboard

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Touch observation shared between the software keyboard and an opt-in recorder.
 *
 * Recording is only active while a sink is installed and the IME has confirmed that the
 * focused editor requested it (see [TouchTraceHub.TRAINING_IME_OPTION]); normal typing in
 * other apps is never traced.
 */
object TouchTraceHub {
    const val TRAINING_IME_OPTION = "rocks.pkb.plektra.typing_training"

    @Volatile
    var sink: TouchTraceSink? = null

    @Volatile
    var editorAllowsTrace: Boolean = false

    val activeSink: TouchTraceSink?
        get() = if (editorAllowsTrace) sink else null


    fun editorRequestsTrace(privateImeOptions: String?): Boolean =
        privateImeOptions?.split(',')?.any { it.trim() == TRAINING_IME_OPTION } == true

    private val seq = AtomicLong()

    /** Process-wide event sequence shared by all traced events; orders events across lists. */
    fun nextSeq(): Long = seq.incrementAndGet()

    /** Last traced raw DOWN (repeat 0) per key code, so later timers can name their cause. */
    class RawKeyDown(val seq: Long, val eventTimeMs: Long, val downTimeMs: Long)

    private val lastRawDowns = java.util.concurrent.ConcurrentHashMap<Int, RawKeyDown>()

    fun noteRawDown(keyCode: Int, down: RawKeyDown) {
        lastRawDowns[keyCode] = down
    }

    fun lastRawDown(keyCode: Int): RawKeyDown? = lastRawDowns[keyCode]
}

interface TouchTraceSink {
    fun onGeometry(snapshot: KeyboardGeometrySnapshot)
    fun onTouch(sample: TouchSample)
    fun onKeyCommitted(commit: KeyCommit)
    fun onHardwareKey(sample: HardwareKeySample) = Unit

    /** The user pressed Backspace/Delete; in training it marks a mistake instead of deleting. */
    fun onUserDelete(uptimeMs: Long) = Unit

    /** IME-internal timing facts that explain lag, e.g. when a long-press timer fired. */
    fun onDiagnostic(event: TraceDiagnostic) = Unit

    /** A filter or remapping step dropped, replayed or rewrote a raw hardware key. */
    fun onKeyDecision(decision: HardwareKeyDecision) = Unit

    /** Lifecycle step of an IME timer such as the hardware long press. */
    fun onTimer(event: TimerEvent) = Unit
}

/**
 * Outcome of one processing stage for a raw [HardwareKeySample]. Only stages that drop, replay or
 * rewrite the key are recorded; a raw key without decisions passed all filters unchanged.
 */
@Serializable
data class HardwareKeyDecision(
    val seq: Long,
    /** [HardwareKeySample.seq] of the raw key this decision applies to; 0 when unknown. */
    val causeSeq: Long,
    val uptimeMs: Long,
    /** `down`, `up` or `long_press`. */
    val action: String,
    /** `accidental_keys`, `remap`, `clicks_shift_bounce` or `bounce_keys`. */
    val stage: String,
    /** `suppressed`, `replay` (held number-row tap re-sent on release), `remapped` or `consumed`. */
    val decision: String,
    val reason: String = "",
    val keyCode: Int,
    val outKeyCode: Int = keyCode,
    val deltaMs: Long = -1,
    val thresholdMs: Long = -1
)

/**
 * One lifecycle step of an IME timer. All steps of one scheduling share [generation]. Times are
 * uptime milliseconds (the Handler clock); late = [uptimeMs] - [dueUptimeMs] on `fired`.
 */
@Serializable
data class TimerEvent(
    val seq: Long,
    val generation: Long,
    /** `hardware_long_press`. */
    val timer: String,
    /** `scheduled`, `fired`, `cancelled` or `superseded` (rescheduled while still posted). */
    val kind: String,
    val keyCode: Int,
    val uptimeMs: Long,
    val thresholdMs: Long,
    /** Scheduling is relative to when the IME handled the DOWN, not to its eventTime. */
    val scheduledUptimeMs: Long,
    val dueUptimeMs: Long,
    /** [HardwareKeySample.seq] of the raw DOWN that started the timer; 0 when unknown. */
    val causeSeq: Long = 0,
    val downTimeMs: Long = -1,
    val downEventTimeMs: Long = -1,
    /** Long-press mode, e.g. `alt`, `shift` or `variations`. */
    val mode: String = "",
    /** Cancellation reason: `key_up`, `alt_active` or `reset`. */
    val reason: String = "",
    /** On `fired`: whether the key was still held, i.e. the long press took effect. */
    val keyStillPressed: Boolean? = null
)

@Serializable
data class TraceDiagnostic(
    /** `long_press_fired` or `main_thread_stall`. */
    val type: String,
    val uptimeMs: Long,
    val durationMs: Long = 0,
    val keyCode: Int = 0,
    val detail: String = ""
)

@Serializable
data class TraceKey(
    val index: Int,
    val type: String,
    val label: String,
    val output: String,
    /** Hit rectangle in view pixels: left, top, right, bottom. */
    val hit: List<Float>,
    /** Drawn rectangle in view pixels: left, top, right, bottom. */
    val visual: List<Float>
)

@Serializable
data class KeyboardGeometrySnapshot(
    val revision: Int,
    val uptimeMs: Long,
    val layoutName: String,
    val layoutStyle: String,
    val shifted: Boolean,
    val numberRow: Boolean,
    val nearestKeyTouch: Boolean,
    val viewWidthPx: Int,
    val viewHeightPx: Int,
    val viewLeftOnScreenPx: Int,
    val viewTopOnScreenPx: Int,
    val xdpi: Float,
    val ydpi: Float,
    val keys: List<TraceKey>
)

@Serializable
data class ScoredKey(val index: Int, val logProbability: Float)

@Serializable
data class TouchSample(
    val geometryRevision: Int,
    val phase: String,
    val pointerId: Int,
    val eventTimeMs: Long,
    val downTimeMs: Long,
    val historical: Boolean,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    /** Key shown as pressed after the event was handled; -1 when none. */
    val uiKeyIndex: Int,
    /** Nearby keys with normalised spatial scores; recorded on down and up only. */
    val nearby: List<ScoredKey> = emptyList(),
    /** When the keyboard view handled the event; the gap to [eventTimeMs] is input lag. */
    val processedUptimeMs: Long = 0
)

@Serializable
data class KeyCommit(
    val geometryRevision: Int,
    val uptimeMs: Long,
    val keyIndex: Int,
    val output: String,
    /** `tap`, or `more_keys` for a selection from the long-press popup of [keyIndex]. */
    val via: String = "tap"
)

/** Raw hardware key event; the effect on the text is recorded separately by the editor. */
@Serializable
data class HardwareKeySample(
    val action: String,
    val keyCode: Int,
    val scanCode: Int,
    val repeatCount: Int,
    val metaState: Int,
    val flags: Int,
    val eventTimeMs: Long,
    val downTimeMs: Long,
    val deviceId: Int,
    val deviceName: String?,
    val vendorId: Int,
    val productId: Int,
    /** Character the key produces without modifiers, if any. */
    val baseChar: String?,
    /** When the IME received the event; the gap to [eventTimeMs] is input lag. */
    val processedUptimeMs: Long = 0,
    /** Shared event sequence (see [TouchTraceHub.nextSeq]); 0 in sessions before schema 6. */
    val seq: Long = 0,
    /** `device`, `replay` (re-sent by the accidental-key filter) or `accessibility`. */
    val origin: String = "device",
    /** For replays: [seq] of the raw key that caused it; 0 otherwise. */
    val causeSeq: Long = 0
) {
    companion object {
        private class DeviceInfo(val name: String?, val vendorId: Int, val productId: Int)

        // InputDevice lookups go through the input manager; resolve each device id once.
        private val deviceInfo = HashMap<Int, DeviceInfo>()

        fun from(
            event: android.view.KeyEvent,
            seq: Long = 0,
            origin: String = "device",
            causeSeq: Long = 0
        ): HardwareKeySample {
            val device = deviceInfo.getOrPut(event.deviceId) {
                event.device.let { DeviceInfo(it?.name, it?.vendorId ?: 0, it?.productId ?: 0) }
            }
            return HardwareKeySample(
                action = when (event.action) {
                    android.view.KeyEvent.ACTION_DOWN -> "down"
                    android.view.KeyEvent.ACTION_UP -> "up"
                    else -> "multiple"
                },
                keyCode = event.keyCode,
                scanCode = event.scanCode,
                repeatCount = event.repeatCount,
                metaState = event.metaState,
                flags = event.flags,
                eventTimeMs = event.eventTime,
                downTimeMs = event.downTime,
                deviceId = event.deviceId,
                deviceName = device.name,
                vendorId = device.vendorId,
                productId = device.productId,
                baseChar = event.getUnicodeChar(0).takeIf { it > 0 }?.toChar()?.toString(),
                processedUptimeMs = android.os.SystemClock.uptimeMillis(),
                seq = seq,
                origin = origin,
                causeSeq = causeSeq
            )
        }
    }
}

/**
 * Spatial key likelihood in physical units.
 *
 * Each key is modelled by a horizontal core segment (a point for keys narrower than tall,
 * a line for wide keys such as Space) with separate horizontal and vertical spread in
 * millimetres. Scores are log-probabilities normalised over the returned candidates. The
 * default spreads are a best-effort starting point until calibrated from recordings.
 */
class KeyNeighborScorer(
    private val sigmaXMm: Float = 3.0f,
    private val sigmaYMm: Float = 3.5f,
    private val maxCandidates: Int = 5,
    private val maxDistanceMm: Float = 12f
) {
    data class KeyRect(val index: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun score(
        x: Float,
        y: Float,
        keys: List<KeyRect>,
        xdpi: Float,
        ydpi: Float
    ): List<ScoredKey> {
        if (keys.isEmpty() || xdpi <= 0f || ydpi <= 0f) return emptyList()
        val pxPerMmX = xdpi / MM_PER_INCH
        val pxPerMmY = ydpi / MM_PER_INCH
        val raw = keys.mapNotNull { key ->
            val dxMm = coreDistanceX(x, key) / pxPerMmX
            val dyMm = (y - (key.top + key.bottom) / 2f) / pxPerMmY
            if (sqrt(dxMm * dxMm + dyMm * dyMm) > maxDistanceMm) return@mapNotNull null
            val logLikelihood = -0.5f * ((dxMm * dxMm) / (sigmaXMm * sigmaXMm) + (dyMm * dyMm) / (sigmaYMm * sigmaYMm))
            key.index to logLikelihood
        }.sortedByDescending { it.second }.take(maxCandidates)
        if (raw.isEmpty()) return emptyList()
        val max = raw.first().second
        val logSum = max + ln(raw.sumOf { exp((it.second - max).toDouble()) }.toFloat())
        return raw.map { (index, score) -> ScoredKey(index, score - logSum) }
    }

    private fun coreDistanceX(x: Float, key: KeyRect): Float {
        val width = key.right - key.left
        val height = key.bottom - key.top
        val inset = if (width > height) height / 2f else width / 2f
        val coreLeft = key.left + inset
        val coreRight = key.right - inset
        return when {
            x < coreLeft -> coreLeft - x
            x > coreRight -> x - coreRight
            else -> 0f
        }
    }

    private companion object {
        const val MM_PER_INCH = 25.4f
    }
}
