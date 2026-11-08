package rocks.pkb.plektra.inputmethod.aospkeyboard

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Next-character likelihoods after a word prefix, e.g. derived from the active dictionary. */
fun interface CharContext {
    /** Relative weights of the next character after [prefix] (lower case), or null if unknown. */
    fun nextCharWeights(prefix: String): Map<Char, Float>?
}

@Serializable
data class KeyOffset(val dxMm: Float, val dyMm: Float, val samples: Int)

/** Touch model of one device posture and layout, calibrated from typing-training sessions. */
@Serializable
data class KeyCorrectionProfile(
    val key: String,
    val sigmaXMm: Float,
    val sigmaYMm: Float,
    /** Mean touch offset from the key centre, keyed by the key's lower-case output. */
    val offsets: Map<String, KeyOffset>,
    val sessions: Int,
    val taps: Int,
    val createdAtEpochMs: Long,
    /** Offsets measured from the sessions; only copied to [offsets] when validation shows a gain. */
    val measuredOffsets: Map<String, KeyOffset> = offsets,
    val validation: KeyCorrectionValidation? = null
)

/**
 * Leave-one-session-out check: each session is replayed with a model fitted on the others.
 * Counts are wrongly decided taps out of [taps].
 */
@Serializable
data class KeyCorrectionValidation(
    val taps: Int,
    val hitTestErrors: Int,
    val withoutOffsetErrors: Int,
    val withOffsetErrors: Int,
    val offsetsEnabled: Boolean
)

/**
 * Per-tap key correction (#176): in the ambiguous zone between keys, the on-screen key is
 * decided from a calibrated touch model plus the likelihood of the next character after the
 * current word prefix. Clear hits are never re-decided and only letter keys take part. This is
 * independent of word suggestions and of automatic word replacement.
 */
object KeyCorrection {
    const val DEFAULT_SIGMA_X_MM = 3.0f
    const val DEFAULT_SIGMA_Y_MM = 2.5f
    /** Touches farther than this fraction of the key size from every edge are clear hits. */
    const val CLEAR_HIT_INSET = 0.25f
    /** Weight of the character context relative to the touch model (log scale). */
    const val CONTEXT_WEIGHT = 0.25f
    private const val MAX_DISTANCE_MM = 12f
    private const val CANDIDATES = 3

    @Volatile
    var charContext: CharContext? = null

    @Volatile
    var profiles: Map<String, KeyCorrectionProfile> = emptyMap()

    /** Set per editor by the IME: false for passwords, literal and raw fields and for training. */
    @Volatile
    var editorAllowsCorrection: Boolean = true

    /** Incremented when the word context becomes unknown (new editor, cursor jump). */
    @Volatile
    var contextGeneration: Int = 0
        private set

    fun invalidateWordContext() {
        contextGeneration++
    }

    fun profileKey(smallestWidthDp: Int, landscape: Boolean, layoutName: String, layoutStyle: String): String =
        "${if (smallestWidthDp >= 600) "large" else "compact"}-${if (landscape) "landscape" else "portrait"}-$layoutName-$layoutStyle"

    private fun isClearHit(x: Float, y: Float, key: Candidate): Boolean {
        val insetX = (key.right - key.left) * CLEAR_HIT_INSET
        val insetY = (key.bottom - key.top) * CLEAR_HIT_INSET
        return x >= key.left + insetX && x <= key.right - insetX && y >= key.top + insetY && y <= key.bottom - insetY
    }

    data class Candidate(val index: Int, val output: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    data class Decision(val index: Int, val spatialProbability: Float, val reason: String)

    /**
     * Returns the key to press for a touch at ([x], [y]) in view pixels. [geometricIndex] is the
     * plain hit-test result; it is returned unchanged unless the touch is ambiguous.
     */
    fun decide(
        x: Float,
        y: Float,
        candidates: List<Candidate>,
        geometricIndex: Int,
        xdpi: Float,
        ydpi: Float,
        profile: KeyCorrectionProfile?,
        prefix: String
    ): Decision {
        if (xdpi <= 0f || ydpi <= 0f) return Decision(geometricIndex, 1f, "no_dpi")
        val geometric = candidates.firstOrNull { it.index == geometricIndex }
        if (geometric != null && isClearHit(x, y, geometric)) return Decision(geometricIndex, 1f, "clear_hit")
        val mmX = 25.4f / xdpi
        val mmY = 25.4f / ydpi
        val sigmaX = profile?.sigmaXMm ?: DEFAULT_SIGMA_X_MM
        val sigmaY = profile?.sigmaYMm ?: DEFAULT_SIGMA_Y_MM
        val scored = candidates.mapNotNull { key ->
            val offset = profile?.offsets?.get(key.output.lowercase())
            val dx = (x - (key.left + key.right) / 2f) * mmX - (offset?.dxMm ?: 0f)
            val dy = (y - (key.top + key.bottom) / 2f) * mmY - (offset?.dyMm ?: 0f)
            if (sqrt(dx * dx + dy * dy) > MAX_DISTANCE_MM) return@mapNotNull null
            key to -0.5f * (dx * dx / (sigmaX * sigmaX) + dy * dy / (sigmaY * sigmaY))
        }
        if (scored.isEmpty()) return Decision(geometricIndex, 1f, "no_candidates")
        val max = scored.maxOf { it.second }
        val total = scored.sumOf { exp((it.second - max).toDouble()) }.toFloat()
        val spatial = scored.associate { (key, score) -> key.index to exp(score - max) / total }
        val top = scored.sortedByDescending { it.second }.take(CANDIDATES).map { it.first }
        val weights = if (prefix.isNotEmpty()) charContext?.nextCharWeights(prefix) else null
        val chosen = if (weights.isNullOrEmpty()) {
            top.first()
        } else {
            val sum = weights.values.sum()
            // Small smoothing: characters that never follow the prefix (digits inside a word) stay unlikely.
            val smoothing = 0.002f * sum + 1e-4f
            top.maxBy { key ->
                val char = key.output.lowercase().singleOrNull()
                val weight = char?.let { weights[it] } ?: 0f
                ln(spatial.getValue(key.index)) + CONTEXT_WEIGHT * ln((weight + smoothing) / (sum + smoothing * 40))
            }
        }
        val reason = when {
            chosen.index == geometricIndex -> "kept"
            weights.isNullOrEmpty() -> "spatial"
            else -> "spatial_context"
        }
        return Decision(chosen.index, spatial[chosen.index] ?: 0f, reason)
    }
}
