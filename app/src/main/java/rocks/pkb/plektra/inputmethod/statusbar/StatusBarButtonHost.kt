package rocks.pkb.plektra.inputmethod.statusbar

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Shared host for status bar buttons across different containers.
 * Handles wrapping (badge/flash) and state updates consistently.
 */
class StatusBarButtonHost(
    private val context: Context,
    private val registry: StatusBarButtonRegistry
) {

    data class HostedButton(
        val id: StatusBarButtonId,
        val button: View,
        val container: View
    )

    private var speechFeedback: Pair<Boolean, Int?> = false to null
    private var microphoneActive = false
    private val buttonStates = mutableMapOf<View, ButtonState>()
    private val recordingColors = mutableMapOf<View, Int>()
    private val outerEdges = mutableMapOf<View, StatusBarButtonPosition>()

    private val hostedButtons = mutableMapOf<StatusBarButtonId, HostedButton>()

    var themeOverride: StatusBarButtonStyles.ThemeOverride? = null
        set(value) {
            if (field == value) {
                return
            }
            field = value
            hostedButtons.values.forEach { hosted ->
                applyTheme(hosted.button, resolveThemeHeight(hosted))
            }
        }

    fun getOrCreateButton(
        id: StatusBarButtonId,
        size: Int,
        callbacks: StatusBarCallbacks,
        width: Int,
        height: Int
    ): HostedButton? {
        val existing = hostedButtons[id]
        if (existing != null) {
            if (id == StatusBarButtonId.Translation) {
                rocks.pkb.plektra.inputmethod.statusbar.button.TranslationButtonFactory.bind(existing.button, callbacks)
            }
            if (id == StatusBarButtonId.Language) {
                registry.getLanguageFactory().refreshLanguageText(context, existing.button)
            }
            prepareForAttach(existing, width, height)
            return existing
        }

        val result = registry.createButton(context, id, size, callbacks) ?: return null
        val button = result.view
        applyTheme(button, height.takeIf { it > 0 } ?: size)
        val container = if (result.badgeView != null || result.flashOverlayView != null) {
            createWrappedView(button, result.badgeView, result.flashOverlayView, width, height)
        } else {
            button
        }

        val hosted = HostedButton(id, button, container)
        hostedButtons[id] = hosted
        if (id == StatusBarButtonId.Microphone) {
            registry.getMicrophoneFactory().setBackgroundRenderer(button) { color ->
                if (color == null) recordingColors.remove(button) else recordingColors[button] = color
                applyTheme(button, resolveThemeHeight(hosted))
            }
        }
        if (id == StatusBarButtonId.Microphone) {
            registry.getMicrophoneFactory().setActive(button, microphoneActive)
            registry.getMicrophoneFactory().setSpeechFeedback(button, speechFeedback.first, speechFeedback.second)
        }
        prepareForAttach(hosted, width, height)
        return hosted
    }

    fun setOuterEdge(id: StatusBarButtonId, edge: StatusBarButtonPosition?) {
        val hosted = hostedButtons[id] ?: return
        if (edge == null) outerEdges.remove(hosted.button) else outerEdges[hosted.button] = edge
        applyTheme(hosted.button, resolveThemeHeight(hosted))
    }

    fun updateButtonLayout(id: StatusBarButtonId, width: Int, height: Int) {
        val hosted = hostedButtons[id] ?: return
        if (hosted.container is FrameLayout) {
            val params = (hosted.button.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(width, height)
            params.width = width
            params.height = height
            hosted.button.layoutParams = params
        }
    }

    fun updateClipboardCount(count: Int) {
        updateButton(StatusBarButtonId.Clipboard, ButtonState.ClipboardState(count))
    }

    fun setMicrophoneActive(isActive: Boolean) {
        microphoneActive = isActive
        if (!isActive) speechFeedback = false to null
        val hosted = hostedButtons[StatusBarButtonId.Microphone] ?: return
        registry.getMicrophoneFactory().setActive(hosted.button, isActive)
    }

    fun updateSpeechFeedback(processing: Boolean, countdown: Int?) {
        if (speechFeedback == (processing to countdown)) return
        speechFeedback = processing to countdown
        val hosted = hostedButtons[StatusBarButtonId.Microphone] ?: return
        registry.getMicrophoneFactory().setSpeechFeedback(hosted.button, processing, countdown)
    }

    fun updateMicrophoneAudioLevel(rmsdB: Float) {
        val hosted = hostedButtons[StatusBarButtonId.Microphone] ?: return
        registry.getMicrophoneFactory().updateAudioLevel(hosted.button, rmsdB)
    }

    fun refreshLanguageText() {
        val hosted = hostedButtons[StatusBarButtonId.Language] ?: return
        registry.getLanguageFactory().refreshLanguageText(context, hosted.button)
    }

    fun setMinimalUiActive(isActive: Boolean) {
        updateButton(StatusBarButtonId.MinimalUi, ButtonState.MinimalUiState(isActive))
    }

    fun detachAll() {
        hostedButtons.keys.forEach { detachButton(it) }
    }

    fun detachButton(id: StatusBarButtonId) {
        val hosted = hostedButtons[id] ?: return
        (hosted.container.parent as? ViewGroup)?.removeView(hosted.container)
        hosted.button.visibility = View.GONE
        hosted.button.alpha = 1f
    }

    fun cleanup() {
        hostedButtons.forEach { (id, hosted) ->
            registry.cleanupButton(id, hosted.button)
        }
        hostedButtons.clear()
        outerEdges.clear()
        recordingColors.clear()
        buttonStates.clear()
    }

    fun updateButton(id: StatusBarButtonId, state: ButtonState) {
        val hosted = hostedButtons[id] ?: return
        buttonStates[hosted.button] = state
        registry.updateButton(id, hosted.button, state)
        applyTheme(hosted.button, resolveThemeHeight(hosted), state)
    }

    private fun prepareForAttach(hosted: HostedButton, width: Int, height: Int) {
        (hosted.container.parent as? ViewGroup)?.removeView(hosted.container)
        hosted.container.visibility = View.VISIBLE
        hosted.container.alpha = 1f
        hosted.button.visibility = View.VISIBLE
        hosted.button.alpha = 1f
        if (hosted.container is FrameLayout) {
            val params = (hosted.button.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(width, height)
            params.width = width
            params.height = height
            hosted.button.layoutParams = params
        } else {
            hosted.button.layoutParams = (hosted.button.layoutParams ?: ViewGroup.LayoutParams(width, height)).apply {
                this.width = width
                this.height = height
            }
        }
        applyTheme(hosted.button, height)
    }

    private fun createWrappedView(
        button: View,
        badgeView: View?,
        flashOverlayView: View?,
        width: Int,
        height: Int
    ): View {
        val frame = FrameLayout(context)
        frame.addView(button, FrameLayout.LayoutParams(width, height))

        badgeView?.let { badge ->
            (badge.parent as? ViewGroup)?.removeView(badge)
            frame.addView(
                badge,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.TOP
                ).apply {
                    val margin = dpToPx(2f)
                    val offset = dpToPx(2f)
                    setMargins(margin, margin + offset, margin, margin)
                }
            )
        }

        flashOverlayView?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            frame.addView(overlay)
        }

        return frame
    }

    private fun resolveThemeHeight(hosted: HostedButton): Int? {
        return hosted.button.layoutParams?.height?.takeIf { it > 0 }
            ?: hosted.button.height.takeIf { it > 0 }
            ?: hosted.container.layoutParams?.height?.takeIf { it > 0 }
            ?: hosted.container.height.takeIf { it > 0 }
    }

    private fun applyTheme(view: View, fallbackHeight: Int? = null, state: ButtonState? = null) {
        (view.getTag(rocks.pkb.plektra.R.id.tag_badge_view) as? TextView)?.let { badge ->
            val params = view.layoutParams
            val enlargedSlimButton = params != null && params.height > 0 &&
                params.width > params.height * 1.2f &&
                rocks.pkb.plektra.SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled(context)
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (enlargedSlimButton) 14f else 10f)
            (badge.layoutParams as? FrameLayout.LayoutParams)?.let { badgeParams ->
                val rightMargin = dpToPx(if (enlargedSlimButton) 12f else 2f)
                if (badgeParams.rightMargin != rightMargin) {
                    badgeParams.rightMargin = rightMargin
                    badge.layoutParams = badgeParams
                }
            }
        }
        val theme = themeOverride ?: return
        (view.getTag(rocks.pkb.plektra.R.id.tag_badge_view) as? TextView)?.setTextColor(theme.iconColor)
        val height = view.layoutParams?.height?.takeIf { it > 0 }
            ?: view.height.takeIf { it > 0 }
            ?: fallbackHeight?.takeIf { it > 0 }
        if (height != null) {
            val active = state is ButtonState.MinimalUiState && state.isActive
            val normalColor = recordingColors[view] ?: if (active) theme.pressedColor else theme.normalColor
            val background = StatusBarButtonStyles.createButtonDrawable(
                heightPx = height,
                normalColor = normalColor,
                pressedColor = theme.pressedColor,
                cornerRadiusRatio = theme.cornerRadiusRatio,
                borderColor = theme.borderColor,
                borderWidthPx = theme.borderWidthPx
            )
            KeyboardBarRowGeometry.setStyle(view, KeyboardBarRowGeometry.Style(
                normalColor, theme.pressedColor, height * theme.cornerRadiusRatio,
                theme.borderColor, theme.borderWidthPx, background),
                fallbackLeft = outerEdges[view] == StatusBarButtonPosition.LEFT,
                fallbackRight = outerEdges[view] == StatusBarButtonPosition.RIGHT)
        }
        val effectiveState = state ?: buttonStates[view]
        when (view) {
            is ImageView -> {
                val modifierIcons = view.drawable as? rocks.pkb.plektra.inputmethod.statusbar.button.ModifierIconsDrawable
                if (effectiveState is ButtonState.ModifierState && modifierIcons != null) {
                    view.clearColorFilter()
                    modifierIcons.setIndicatorColors(theme.activeIndicatorColor, theme.lockedIndicatorColor)
                } else view.setColorFilter(theme.iconColor)
            }
            is TextView -> view.setTextColor(theme.iconColor)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}
