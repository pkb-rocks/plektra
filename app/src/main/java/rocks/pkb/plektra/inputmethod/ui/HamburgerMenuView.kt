package rocks.pkb.plektra.inputmethod.ui

import android.content.Context
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonHost
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonId
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarCallbacks
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonStyles
import rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry

/**
 * Overlay menu that replaces the status bar row with fixed buttons.
 */
class HamburgerMenuView(
    private val context: Context,
    private val buttonRegistry: StatusBarButtonRegistry
) {


    private val menuButtonIds = listOf(
        StatusBarButtonId.Symbols,
        StatusBarButtonId.Emoji,
        StatusBarButtonId.Microphone,
        StatusBarButtonId.Clipboard,
        StatusBarButtonId.Undo,
        StatusBarButtonId.Redo,
        StatusBarButtonId.Language,
        StatusBarButtonId.MinimalUi,
        StatusBarButtonId.SoftwareKeyboardMode,
        StatusBarButtonId.KeyboardPicker,
        StatusBarButtonId.Settings
    )

    private var root: FrameLayout? = null
    private var row: LinearLayout? = null
    private val buttonHost = StatusBarButtonHost(context, buttonRegistry)
    private var currentButtons: List<StatusBarButtonHost.HostedButton> = emptyList()
    private var closeButton: ImageView? = null
    private var lastClipboardCount: Int? = null
    private var lastMicrophoneActive: Boolean? = null
    private var lastMicrophoneRms: Float? = null
    private var lastMinimalUiActive: Boolean? = null
    var themeOverride: KeyboardThemeColors? = null
        set(value) {
            if (field == value) {
                return
            }
            field = value
            buttonHost.themeOverride = value?.let {
                StatusBarButtonStyles.ThemeOverride(
                    normalColor = it.statusBarButton,
                    pressedColor = it.accent,
                    iconColor = it.textAndIcons,
                    cornerRadiusRatio = it.chromeCornerRadiusRatio,
                    borderColor = it.divider,
                    borderWidthPx = dpToPx(1f)
                )
            }
            rocks.pkb.plektra.ThemeTextureBackground.set(root, value?.background ?: Color.BLACK, value?.textures?.get("background"))
            closeButton?.let { applyCloseButtonTheme(it) }
        }

    fun attachTo(parent: FrameLayout) {
        val view = ensureView()
        if (view.parent !== parent) {
            (view.parent as? ViewGroup)?.removeView(view)
            parent.addView(view)
        }
    }

    fun show(callbacks: StatusBarCallbacks, onClose: () -> Unit) {
        val view = ensureView()
        view.visibility = View.VISIBLE
        view.bringToFront()
        view.post {
            buildButtons(callbacks, onClose)
        }
    }

    fun hide() {
        root?.visibility = View.GONE
    }

    fun isVisible(): Boolean = root?.visibility == View.VISIBLE

    fun updateClipboardCount(count: Int) {
        lastClipboardCount = count
        buttonHost.updateClipboardCount(count)
    }

    fun setMicrophoneActive(isActive: Boolean) {
        lastMicrophoneActive = isActive
        buttonHost.setMicrophoneActive(isActive)
    }

    fun updateSpeechFeedback(processing: Boolean, countdown: Int?) {
        lastSpeechFeedback = processing to countdown
        buttonHost.updateSpeechFeedback(processing, countdown)
    }

    fun updateMicrophoneAudioLevel(rmsdB: Float) {
        lastMicrophoneRms = rmsdB
        buttonHost.updateMicrophoneAudioLevel(rmsdB)
    }

    fun setMinimalUiActive(isActive: Boolean) {
        lastMinimalUiActive = isActive
        buttonHost.setMinimalUiActive(isActive)
    }

    fun refreshLanguageText() {
        buttonHost.refreshLanguageText()
    }

    private fun ensureView(): FrameLayout {
        if (root != null) {
            return root!!
        }
        val rowView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateButtonSizes(this)
            }
        }
        row = rowView
        root = FrameLayout(context).apply {
            rocks.pkb.plektra.ThemeTextureBackground.set(this, themeOverride?.background ?: Color.BLACK, themeOverride?.textures?.get("background"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            addView(rowView)
        }
        return root!!
    }

    private fun buildButtons(callbacks: StatusBarCallbacks, onClose: () -> Unit) {
        val rowView = row ?: return
        clearButtons()

        val menuCallbacks = StatusBarCallbacks(
            onClipboardRequested = {
                onClose()
                callbacks.onClipboardRequested?.invoke()
            },
            onSpeechRecognitionRequested = {
                onClose()
                callbacks.onSpeechRecognitionRequested?.invoke()
            },
            onEmojiPickerRequested = {
                onClose()
                callbacks.onEmojiPickerRequested?.invoke()
            },
            onLanguageSwitchRequested = {
                onClose()
                callbacks.onLanguageSwitchRequested?.invoke()
            },
            onHamburgerMenuRequested = null,
            onMinimalUiToggleRequested = {
                callbacks.onMinimalUiToggleRequested?.invoke()
            },
            onSoftwareKeyboardModeToggleRequested = {
                callbacks.onSoftwareKeyboardModeToggleRequested?.invoke()
            },
            onOpenSettings = {
                onClose()
                callbacks.onOpenSettings?.invoke()
            },
            onSymbolsPageRequested = {
                onClose()
                callbacks.onSymbolsPageRequested?.invoke()
            },
            onUndoRequested = {
                onClose()
                callbacks.onUndoRequested?.invoke()
            },
            onRedoRequested = {
                onClose()
                callbacks.onRedoRequested?.invoke()
            },
            onHapticFeedback = callbacks.onHapticFeedback
        )

        val buttonHeight = resolveButtonHeight(rowView)
        val closeButtonView = createCloseButton(onClose, buttonHeight)
        closeButton = closeButtonView
        rowView.addView(closeButtonView)
        val fallbackWidth = buttonHeight
        val hostedButtons = mutableListOf<StatusBarButtonHost.HostedButton>()
        menuButtonIds.forEach { id ->
            val hosted = buttonHost.getOrCreateButton(
                id,
                buttonHeight,
                menuCallbacks,
                fallbackWidth,
                buttonHeight
            ) ?: return@forEach
            hostedButtons.add(hosted)
            rowView.addView(hosted.container)
        }
        currentButtons = hostedButtons

        KeyboardBarRowGeometry.bind(rowView, listOf(closeButtonView) + hostedButtons.map { it.button })

        updateButtonSizes(rowView)
        applyStoredStates()
    }

    private fun clearButtons() {
        buttonHost.detachAll()
        currentButtons = emptyList()
        row?.removeAllViews()
    }

    private fun updateButtonSizes(rowView: LinearLayout) {
        val expectedButtons = menuButtonIds.size + 1
        val totalButtons = rowView.childCount
        if (totalButtons != expectedButtons) {
            return
        }
        val spacing = dpToPx(3f)
        val availableWidth = rowView.width - rowView.paddingLeft - rowView.paddingRight
        if (availableWidth <= 0) {
            return
        }
        val buttonWidth = ((availableWidth - spacing * (totalButtons - 1)) / totalButtons).coerceAtLeast(1)
        val buttonHeight = resolveButtonHeight(rowView)
        for (index in 0 until totalButtons) {
            val child = rowView.getChildAt(index)
            val params = (child.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            val end = if (index == totalButtons - 1) 0 else spacing
            if (params.width != 0 || params.weight != 1f || params.height != buttonHeight || params.marginEnd != end) {
                params.width = 0
                params.weight = 1f
                params.height = buttonHeight
                params.marginEnd = end
                child.layoutParams = params
            }
        }
        currentButtons.forEach { hosted ->
            buttonHost.updateButtonLayout(hosted.id, buttonWidth, buttonHeight)
            (hosted.container.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = 0
                weight = 1f
            }
        }
        closeButton?.let { applyCloseButtonTheme(it, buttonHeight) }
    }

    private fun resolveButtonHeight(rowView: LinearLayout): Int {
        val height = rowView.height - rowView.paddingTop - rowView.paddingBottom
        return if (height > 0) height else dpToPx(39f)
    }


    private var lastSpeechFeedback: Pair<Boolean, Int?> = false to null

    private fun applyStoredStates() {
        lastClipboardCount?.let { buttonHost.updateClipboardCount(it) }
        lastMicrophoneActive?.let { buttonHost.setMicrophoneActive(it) }
        lastMicrophoneRms?.let { buttonHost.updateMicrophoneAudioLevel(it) }
        buttonHost.updateSpeechFeedback(lastSpeechFeedback.first, lastSpeechFeedback.second)
        lastMinimalUiActive?.let { buttonHost.setMinimalUiActive(it) }
        buttonHost.refreshLanguageText()
    }

    private fun createCloseButton(
        onClose: () -> Unit,
        heightPx: Int
    ): ImageView {
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_close_24)
            contentDescription = context.getString(R.string.status_bar_button_close_menu_description)
            scaleType = ImageView.ScaleType.CENTER
            applyCloseButtonTheme(this, heightPx)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClose()
            }
        }
    }

    private fun applyCloseButtonTheme(button: ImageView, heightPx: Int? = null) {
        val theme = themeOverride
        val height = heightPx?.takeIf { it > 0 }
            ?: button.layoutParams?.height?.takeIf { it > 0 }
            ?: button.height.takeIf { it > 0 }
            ?: dpToPx(39f)
        button.setColorFilter(theme?.textAndIcons ?: Color.WHITE)
        val flat = StatusBarButtonStyles.createButtonDrawable(
            heightPx = height,
            normalColor = theme?.statusBarButton ?: StatusBarButtonStyles.NORMAL_COLOR,
            pressedColor = theme?.accent ?: StatusBarButtonStyles.PRESSED_BLUE,
            cornerRadiusRatio = theme?.chromeCornerRadiusRatio ?: StatusBarButtonStyles.BUTTON_CORNER_RADIUS_RATIO,
            borderColor = theme?.divider,
            borderWidthPx = if (theme != null) dpToPx(1f) else 0
        )
        KeyboardBarRowGeometry.setStyle(button, KeyboardBarRowGeometry.Style(
            theme?.statusBarButton ?: StatusBarButtonStyles.NORMAL_COLOR,
            theme?.accent ?: StatusBarButtonStyles.PRESSED_BLUE,
            height * (theme?.chromeCornerRadiusRatio ?: StatusBarButtonStyles.BUTTON_CORNER_RADIUS_RATIO),
            theme?.divider, if (theme != null) dpToPx(1f) else 0, flat
        ))
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
