package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.SymLayoutController

/**
 * Owns explicit show requests; framework callbacks alone never prove that a child was drawn.
 *
 * Visibility precedence, strongest first: [isSurfaceBlocked] (per-app "never show the bar")
 * vetoes every surface, including explicit opens, hardware-key recovery and transient SYM
 * pages; then [allowsPersistentSurface] together with transient content; then configured
 * content and automatic show decisions. New overrides must stay below the app block.
 */
class KeyboardVisibilityController(
    private val context: Context,
    private val candidatesBarController: CandidatesBarController,
    private val symLayoutController: SymLayoutController,
    private val isInputViewActive: () -> Boolean,
    private val hasActiveTextField: () -> Boolean,
    private val isNavModeLatched: () -> Boolean,
    private val currentInputConnection: () -> InputConnection?,
    private val isInputViewShown: () -> Boolean,
    private val renderedSurface: () -> RenderedSurface,
    private val setRequestedInputViewShown: (Boolean) -> Unit,
    private val attachInputView: (View) -> Unit,
    private val attachCandidatesView: (View) -> Unit,
    private val setCandidatesSurfaceActive: (Boolean) -> Unit,
    private val setCandidatesViewShown: (Boolean) -> Unit,
    private val synchronizeCandidatesContainerVisibility: () -> Unit,
    private val postToUi: (() -> Unit) -> Unit,
    private val postToUiDelayed: (Long, () -> Unit) -> Unit,
    private val showInputWindow: (Boolean) -> Unit,
    private val hideInputWindow: () -> Unit,
    private val requestHideInputView: () -> Unit,
    private val requestShowInputView: () -> Unit,
    private val refreshStatusBar: () -> Unit,
    private val trace: (String) -> Unit = {},
    private val allowsPersistentSurface: () -> Boolean = { true },
    private val isSurfaceBlocked: () -> Boolean = { false }
) {
    enum class RenderedSurface { HIDDEN, FULL_INPUT_VIEW, CANDIDATES_VIEW }

    private var generation = 0
    private var pending = false
    private var changingSurface = false
    private var dismissed = false
    private var candidatesStarted = false
    private var windowShown = false
    private var waitingForBackendHide = false
    private var hardwareConnectionRecovery = false
    private var hardwareConnectionTicket = 0

    fun usesCandidatesView(): Boolean =
        SettingsManager.getExperimentalCandidatesViewEnabled(context) &&
            SettingsManager.resolveEffectiveSoftwareKeyboardMode(context) !=
            SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL

    private fun expectedSurface() = if (usesCandidatesView()) {
        RenderedSurface.CANDIDATES_VIEW
    } else {
        RenderedSurface.FULL_INPUT_VIEW
    }

    fun onCreateInputView(): View =
        candidatesBarController.getInputView(symLayoutController.emojiMapTextForLayout()).also {
            detachFromParent(it)
            refreshStatusBar()
        }

    fun onCreateCandidatesView(): View {
        setCandidatesSurfaceActive(usesCandidatesView())
        return candidatesBarController.getCandidatesView(symLayoutController.emojiMapTextForLayout()).also {
            detachFromParent(it)
            refreshStatusBar()
        }
    }

    fun onEvaluateInputViewShown(shouldShowInputView: Boolean): Boolean {
        SoftwareKeyboardAutoDetector.updateSystemInputViewDecision(shouldShowInputView)
        // The stable backend deliberately uses an input view even for the compact hardware row.
        // Keep evaluation pure: a show from here re-enters Android's unfinished show operation.
        return !usesCandidatesView() && hasConfiguredContent()
    }

    fun allowsSurface(): Boolean = !isSurfaceBlocked() && (allowsPersistentSurface() ||
        symLayoutController.currentSymPage() > 0 || candidatesBarController.hasTransientContent())

    private fun hasConfiguredContent(): Boolean = allowsSurface() && (rocks.pkb.plektra.KeyboardBarConfiguration.read(context)
            .presentation(candidatesBarController.isSlimModeActive()).hasPersistentContent ||
            candidatesBarController.hasConfiguredMenuButtons() ||
            SettingsManager.resolveEffectiveSoftwareKeyboardMode(context) == SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL ||
            symLayoutController.currentSymPage() > 0 || candidatesBarController.hasTransientContent())

    private fun canShow(): Boolean = hasConfiguredContent() && isInputViewActive() &&
        currentInputConnection() != null && !isNavModeLatched()

    fun onKeyboardBarConfigurationChanged() {
        if (hasConfiguredContent()) {
            ensureImeSurfaceVisible()
        } else {
            closeSurface()
        }
    }

    private fun closeSurface() {
        cancelPendingSurfaceTransition()
        setCandidatesViewShown(false)
        hideInputWindow()
        requestHideInputView()
    }

    fun ensureImeSurfaceVisible() {
        if (!canShow() || waitingForBackendHide) return
        dismissed = false
        if (pending || isExpectedSurfaceRequestedOrShown()) return
        pending = true
        val ticket = ++generation
        // onStartInput/onShowInputRequested may be inside a framework hide/show. Wait for it to end.
        postToUi { present(ticket, 0) }
    }

    private fun present(ticket: Int, attempt: Int) {
        if (ticket != generation || !pending) return
        if (!canShow() || dismissed) {
            cancelPendingSurfaceTransition()
            return
        }
        if (isExpectedSurfaceRequestedOrShown()) {
            pending = false
            trace("shown target=${expectedSurface()} attempt=$attempt")
            return
        }
        if (attempt >= MAX_ATTEMPTS) {
            // A failed show must not leave an invisible window intercepting the editor.
            pending = false
            changingSurface = true
            setCandidatesSurfaceActive(false)
            setCandidatesViewShown(false)
            hideInputWindow()
            requestHideInputView()
            changingSurface = false
            trace("show_failed target=${expectedSurface()}; window closed")
            return
        }
        trace("show target=${expectedSurface()} attempt=$attempt rendered=${renderedSurface()}")
        changingSurface = true
        try {
            val candidates = usesCandidatesView()
            setRequestedInputViewShown(!candidates)
            setCandidatesSurfaceActive(candidates)
            if (candidates) {
                // Rebind the actual child, even when Android still reports candidates-started.
                // setCandidatesViewShown(true) alone is an idempotent no-op in that ghost state.
                attachCandidatesView(onCreateCandidatesView())
                setCandidatesViewShown(true)
                synchronizeCandidatesContainerVisibility()
                showInputWindow(false)
                synchronizeCandidatesContainerVisibility()
                // Direct child/window repair does not restore IMMS's show request after Back.
                // Issue one server request for this explicit recovery, never one per retry/key.
                if (attempt == 0) requestShowInputView()
            } else {
                setCandidatesViewShown(false)
                attachInputView(onCreateInputView())
                if (attempt == 0) requestShowInputView()
                else showInputWindow(true)
            }
            refreshStatusBar()
        } catch (error: RuntimeException) {
            // A disappearing editor/window can reject a request. Retry within the same bound.
            trace("show_rejected target=${expectedSurface()} error=${error.javaClass.simpleName}")
        } finally {
            changingSurface = false
        }
        postToUiDelayed(RETRY_DELAY_MS) { present(ticket, attempt + 1) }
    }

    /**
     * The hardware bar always follows the editor (the service holds it back only while a
     * [HardwareKeypadAutoOpen] decision waits for Android). The on-screen keyboard shows itself on input start
     * only when automatic showing is on and app/Android display defaults are not respected: Android
     * does not tell an IME the window's soft-input state, so respecting them means waiting for
     * Android's show request (forward navigation, STATE_VISIBLE, a tap) instead of forcing a show.
     */
    fun shouldShowSurfaceOnInputStart(
        autoShowKeyboardEnabled: Boolean,
        respectAppVisibility: Boolean = false
    ): Boolean =
        SettingsManager.resolveEffectiveSoftwareKeyboardMode(context) !=
            SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL || (autoShowKeyboardEnabled && !respectAppVisibility)

    fun onImeWindowVisibilityChanged(shown: Boolean) {
        windowShown = shown
        trace("window shown=$shown changing=$changingSurface")
        if (shown && isSurfaceBlocked()) {
            // Android can still show the window itself, e.g. after a config change or a
            // show request raced with the app switch. Hide once the framework show settles.
            postToUi {
                if (windowShown && isSurfaceBlocked()) {
                    trace("blocked_surface_hidden")
                    closeSurface()
                }
            }
            return
        }
        if (!shown && waitingForBackendHide) {
            waitingForBackendHide = false
            // Let Android finish clearing mShowInputRequested before starting the new backend.
            val ticket = generation
            postToUi { if (ticket == generation) ensureImeSurfaceVisible() }
            return
        }
        if (!shown && hardwareConnectionRecovery && canShow()) {
            hardwareConnectionRecovery = false
            dismissed = false
            cancelPendingSurfaceTransition()
            trace("recover_hardware_connection_hide")
            val ticket = hardwareConnectionTicket
            postToUi { if (ticket == hardwareConnectionTicket) ensureImeSurfaceVisible() }
            return
        }
        if (!shown && !changingSurface) {
            dismissed = true
            cancelPendingSurfaceTransition()
        }
    }

    fun onCandidatesViewStarted() {
        candidatesStarted = true
        if (usesCandidatesView()) setCandidatesSurfaceActive(true)
        trace("candidates_started rendered=${renderedSurface()}")
    }

    fun onCandidatesViewFinished(finishingInput: Boolean) {
        val wasStarted = candidatesStarted
        candidatesStarted = false
        trace("candidates_finished finishing=$finishingInput changing=$changingSurface")
        if (changingSurface || waitingForBackendHide || finishingInput || !wasStarted || !usesCandidatesView()) return
        dismissed = true
        cancelPendingSurfaceTransition()
        val ticket = generation
        postToUi {
            if (ticket != generation || !dismissed || candidatesStarted) return@postToUi
            changingSurface = true
            setCandidatesSurfaceActive(false)
            setCandidatesViewShown(false)
            hideInputWindow()
            requestHideInputView()
            changingSurface = false
        }
    }

    fun onInputUnbound() {
        hardwareConnectionRecovery = false
        hardwareConnectionTicket++
        cancelPendingSurfaceTransition()
        windowShown = false
        candidatesStarted = false
        dismissed = false
        trace("input_unbound")
    }

    fun onInputStarted(restarting: Boolean) {
        cancelPendingSurfaceTransition()
        if (!restarting) {
            dismissed = false
            hardwareConnectionRecovery = false
            hardwareConnectionTicket++
        }
    }

    fun onHardwareKeyboardConnected() {
        if (!canShow() || (!windowShown && !isInputViewShown())) return
        hardwareConnectionRecovery = true
        val ticket = ++hardwareConnectionTicket
        // Some devices request HIDE_HW_KEYBOARD_CONNECTED independently of the IME's mode.
        postToUi {
            if (ticket == hardwareConnectionTicket && hardwareConnectionRecovery && canShow()) {
                requestShowInputView()
                trace("preserve_hardware_connection_input")
            }
        }
        postToUiDelayed(750L) {
            if (ticket == hardwareConnectionTicket) hardwareConnectionRecovery = false
        }
    }

    fun onExplicitShowRequested() = ensureImeSurfaceVisible()
    fun onHardwareInputRequested() = ensureImeSurfaceVisible()

    fun onKeyboardSurfaceChanged(ensureInputViewShown: Boolean, requireActiveTextField: Boolean = false) {
        cancelPendingSurfaceTransition()
        if (isSurfaceBlocked()) {
            if (windowShown) closeSurface()
            return
        }
        if (requireActiveTextField && !hasActiveTextField()) return
        if (windowShown && !usesCandidatesView() && renderedSurface() == RenderedSurface.FULL_INPUT_VIEW) {
            refreshStatusBar()
            trace("resize_existing_input_view")
            return
        }
        if (windowShown) {
            // hideWindow() only hides the local window. The framework's hideSoftInput path
            // also resets mShowInputRequested and finishes the preceding input/candidates view.
            waitingForBackendHide = true
            val ticket = generation
            try {
                requestHideInputView()
                postToUiDelayed(BACKEND_HIDE_TIMEOUT_MS) {
                    if (ticket == generation && waitingForBackendHide) abortBackendHide()
                }
            } catch (error: RuntimeException) {
                trace("backend_hide_rejected error=${error.javaClass.simpleName}")
                abortBackendHide()
            }
        } else {
            setCandidatesSurfaceActive(false)
            setCandidatesViewShown(false)
            ensureImeSurfaceVisible()
        }
    }

    private fun abortBackendHide() {
        // Do not reopen against an unacknowledged hide, but allow the next explicit tap/key.
        cancelPendingSurfaceTransition()
        dismissed = true
        trace("backend_hide_aborted")
    }

    fun cancelPendingSurfaceTransition() {
        generation++
        pending = false
        waitingForBackendHide = false
    }

    fun isCandidatesOnlySurface() = usesCandidatesView()
    fun isExpectedSurfaceRequestedOrShown(): Boolean = windowShown && renderedSurface() == expectedSurface() &&
        (usesCandidatesView() || isInputViewShown())
    fun shouldRecoverSurfaceOnHardwareKey() = !isExpectedSurfaceRequestedOrShown()
    internal fun isCandidatesSurfaceExplicitlyDismissedForTests() = dismissed

    fun toggleSlimMode() {
        val next = when (SettingsManager.getStatusBarPresentationMode(context)) {
            SettingsManager.StatusBarPresentationMode.SLIM -> SettingsManager.StatusBarPresentationMode.FULL_STATUS_BAR
            SettingsManager.StatusBarPresentationMode.FULL_STATUS_BAR -> SettingsManager.StatusBarPresentationMode.SLIM
        }
        SettingsManager.setStatusBarPresentationMode(context, next)
        syncStatusBarPresentationModeFromSettings()
    }

    fun syncStatusBarPresentationModeFromSettings() {
        val minimal = SettingsManager.getStatusBarPresentationMode(context) == SettingsManager.StatusBarPresentationMode.SLIM
        candidatesBarController.setSlimModeActive(minimal)
        SettingsManager.setSlimModeActive(context, minimal)
        refreshStatusBar()
        onKeyboardBarConfigurationChanged()
    }

    private fun detachFromParent(view: View) { (view.parent as? ViewGroup)?.removeView(view) }

    private companion object {
        const val BACKEND_HIDE_TIMEOUT_MS = 1000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 150L
    }
}
