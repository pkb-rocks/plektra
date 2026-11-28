package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import rocks.pkb.plektra.SettingsManager
import kotlin.math.roundToInt

/** Text-only feedback; the caller commits immediately, without waiting for animation completion. */
internal class SuggestionSwipeAnimation(private val context: Context) {
    private var active: PopupWindow? = null
    private var animator: ValueAnimator? = null
    private var root: View? = null
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = clear()
    }
    private val visibility = ViewTreeObserver.OnGlobalLayoutListener {
        if (root?.windowVisibility != View.VISIBLE) clear()
    }

    fun clear() {
        // Clear ownership first: cancelling an animator also invokes its end listener.
        val window = active
        val running = animator
        active = null
        animator = null
        root?.let { view ->
            view.removeOnAttachStateChangeListener(detach)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(visibility)
        }
        root = null
        running?.cancel()
        window?.dismiss()
    }

    /** False retains the existing color flash when disabled, unavailable or reduced-motion is set. */
    fun show(source: TextView): Boolean {
        clear()
        if (!enabled(context) || !ValueAnimator.areAnimatorsEnabled() || !source.isShown ||
            !source.isAttachedToWindow || source.width <= 0 || source.height <= 0 ||
            source.text.isNullOrBlank() || Color.alpha(source.currentTextColor) == 0 ||
            source.compoundDrawables.any { it != null }) return false

        val screen = IntArray(2).also(source::getLocationOnScreen)
        val position = IntArray(2).also(source::getLocationInWindow)
        val rise = (56f * context.resources.displayMetrics.density).roundToInt()
            .coerceAtMost(screen[1].coerceAtLeast(0))
        if (rise == 0) return false
        val label = OutlinedLabel(context).apply {
            text = source.transformationMethod?.getTransformation(source.text, source)?.toString() ?: source.text.toString()
            setTextColor(source.currentTextColor)
            val color = source.currentTextColor
            outlineColor = if (Color.luminance(Color.rgb(Color.red(color), Color.green(color), Color.blue(color))) >= .5f) Color.BLACK else Color.WHITE
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, source.textSize)
            typeface = source.typeface
            gravity = source.gravity
            includeFontPadding = source.includeFontPadding
            textDirection = source.textDirection
            layoutDirection = source.layoutDirection
            letterSpacing = source.letterSpacing
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val stage = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // A source-height popup would clip the word as soon as it crossed its upper edge.
            addView(label, FrameLayout.LayoutParams(source.width, source.height).apply { topMargin = rise })
        }
        val window = PopupWindow(stage, source.width, source.height + rise, false).apply {
            isTouchable = false
            isOutsideTouchable = false
            isClippingEnabled = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0
        }
        try {
            // Attached popups use window coordinates. Screen Y would add the IME origin twice.
            window.showAtLocation(source, Gravity.TOP or Gravity.LEFT, position[0], position[1] - rise)
            active = window
            root = source.rootView.also {
                it.addOnAttachStateChangeListener(detach)
                it.viewTreeObserver.addOnGlobalLayoutListener(visibility)
            }
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    label.translationY = -rise * progress
                    label.alpha = 1f - ((progress - .55f) / .45f).coerceIn(0f, 1f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { if (active === window) clear() }
                })
                start()
            }
            return true
        } catch (_: RuntimeException) {
            clear()
            window.dismiss()
            return false
        }
    }

    internal class OutlinedLabel(context: Context) : AppCompatTextView(context) {
        var outlineColor: Int = Color.BLACK
        override fun onDraw(canvas: Canvas) {
            layout?.let { lines ->
                val paint = lines.paint
                val color = paint.color
                val style = paint.style
                val width = paint.strokeWidth
                val join = paint.strokeJoin
                val available = height - extendedPaddingTop - extendedPaddingBottom
                val offset = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                    Gravity.BOTTOM -> available - lines.height
                    Gravity.CENTER_VERTICAL -> (available - lines.height) / 2
                    else -> 0
                }.coerceAtLeast(0)
                val checkpoint = canvas.save()
                try {
                    canvas.clipRect(compoundPaddingLeft, extendedPaddingTop, this.width - compoundPaddingRight, height - extendedPaddingBottom)
                    canvas.translate((compoundPaddingLeft - scrollX).toFloat(), (extendedPaddingTop + offset - scrollY).toFloat())
                    paint.style = Paint.Style.STROKE
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.strokeWidth = 2f * resources.displayMetrics.density
                    paint.color = outlineColor
                    lines.draw(canvas)
                } finally {
                    canvas.restoreToCount(checkpoint)
                    paint.color = color
                    paint.style = style
                    paint.strokeWidth = width
                    paint.strokeJoin = join
                }
            }
            super.onDraw(canvas)
        }
    }

    companion object {
        internal const val DURATION_MS = 400L
        const val KEY = "suggestion_swipe_animation"
        // Opt-in: existing gestures/feedback are unchanged until the user enables the effect.
        fun enabled(context: Context) = SettingsManager.getPreferences(context).getBoolean(KEY, false)
        fun setEnabled(context: Context, value: Boolean) = SettingsManager.getPreferences(context).edit().putBoolean(KEY, value).apply()
    }
}
