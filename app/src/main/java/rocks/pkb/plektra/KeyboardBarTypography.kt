package rocks.pkb.plektra

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import kotlin.math.roundToInt

/** Scale the displayed ceiling once, retaining the existing whole-candidate fitting floor. */
internal object KeyboardBarTypography {
    const val KEY = "keyboard_bar_text_scale"
    const val MIN_SCALE = .85f
    const val MAX_SCALE = 1.25f

    fun normalize(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else 1f

    fun scale(context: Context): Float = runCatching {
        normalize(SettingsManager.getPreferences(context).getFloat(KEY, 1f))
    }.getOrDefault(1f)

    fun setScale(context: Context, value: Float) {
        SettingsManager.getPreferences(context).edit().putFloat(KEY, normalize(value)).apply()
    }

    private data class ReferenceKey(
        val minimum: Int,
        val maximum: Int,
        val density: Float,
        val scaledDensity: Float,
        val fontScale: Float
    )
    private data class ReferenceSize(val minimum: Int, val maximum: Int)

    // Retain only numbers, never a Context or View. Configuration changes use a new entry.
    private val references = object : LinkedHashMap<ReferenceKey, ReferenceSize>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReferenceKey, ReferenceSize>?): Boolean =
            size > 24
    }

    @Synchronized
    private fun reference(context: Context, minSp: Int, baseMaxSp: Int): ReferenceSize {
        val metrics = context.resources.displayMetrics
        val key = ReferenceKey(minSp, baseMaxSp, metrics.density, metrics.scaledDensity,
            context.resources.configuration.fontScale)
        return references[key] ?: run {
            val view = TextView(context)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                view, minSp, baseMaxSp, 1, TypedValue.COMPLEX_UNIT_SP
            )
            val sizes = TextViewCompat.getAutoSizeTextAvailableSizes(view)
            ReferenceSize(TextViewCompat.getAutoSizeMinTextSize(view), sizes.last())
                .also { references[key] = it }
        }
    }

    /** Use Android's displayed baseline, rather than scaling its nonlinear SP input. */
    fun preferredMaxPx(
        context: Context,
        minSp: Int,
        baseMaxSp: Int,
        scale: Float = scale(context)
    ): Int {
        val base = reference(context, minSp, baseMaxSp)
        return (base.maximum * normalize(scale)).roundToInt().coerceAtLeast(base.minimum + 1)
    }

    fun applyAutoSize(
        view: TextView,
        minSp: Int,
        baseMaxSp: Int,
        scale: Float = scale(view.context)
    ) {
        val base = reference(view.context, minSp, baseMaxSp)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            view, base.minimum, preferredMaxPx(view.context, minSp, baseMaxSp, scale),
            1, TypedValue.COMPLEX_UNIT_PX
        )
    }
}
