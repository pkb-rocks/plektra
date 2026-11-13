package rocks.pkb.plektra

import android.content.Context
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.toolbox.FnCtrlRemap

/**
 * One-button "Reset device settings to stock" safety net.
 *
 * Some of what the toolbox writes lives at the OS / vendor level and SURVIVES an uninstall.
 * Android gives an app no uninstall hook, so we cannot auto-revert — the supported path is the
 * user tapping this before uninstalling. [resetToStock] undoes every change registered below:
 *
 *   1. Fn -> Ctrl remap — Settings.System `fn_programmable_key_enable` / `_function`.
 *
 * PhysiBoard's version also reverted the keyboard backlight, the QS backlight tile,
 * and the orange side key. Each of those features adds its own step here
 * when it is ported (one `StepOutcome` field, one line in [resetToStock]). Density, system tweaks
 * and removed apps have reset buttons on their own pages; they are not part of this reset.
 *
 * Each revert runs INDEPENDENTLY (one failing never skips the others) and clears its own capture
 * log only on success. Every step is wrapped in runCatching, so this NEVER throws. It is BLOCKING
 * (the broker path does network IO) — callers MUST run it OFF the main thread, and never from
 * the IME input path.
 */
object SystemChangeManager {

    /** Outcome of a single revert step. */
    enum class StepOutcome { SUCCESS, NEEDS_PERMISSION, FAILED }

    /**
     * Per-item results of a reset. [allSucceeded] is true only when every step succeeded;
     * [needsPermission] is true when at least one step could not write because neither the
     * Settings API nor a paired wireless-debugging broker was available.
     */
    data class ResetResult(
        val fnCtrl: StepOutcome
    ) {
        private val steps get() = listOf(fnCtrl)

        val allSucceeded: Boolean
            get() = steps.all { it == StepOutcome.SUCCESS }

        val needsPermission: Boolean
            get() = steps.any { it == StepOutcome.NEEDS_PERMISSION }
    }

    /**
     * Revert every system-wide change the toolbox can make. BLOCKING — call OFF the main thread.
     * Never throws. Runs the reverts independently and reports each outcome.
     */
    fun resetToStock(context: Context, trigger: BrokerTrigger?): ResetResult {
        val fn = runCatching { revertFnCtrl(context, trigger) }.getOrDefault(StepOutcome.FAILED)
        return ResetResult(fnCtrl = fn)
    }

    // 1. Fn -> Ctrl. Restore the captured original programmable-key values (or write 0/0 when
    //    the original was unset / never captured). The same code path as the "Reset Fn key"
    //    button, so the two cannot drift apart.
    private fun revertFnCtrl(context: Context, trigger: BrokerTrigger?): StepOutcome =
        when (FnCtrlRemap.revert(context, trigger)) {
            FnCtrlRemap.Result.SUCCESS -> StepOutcome.SUCCESS
            FnCtrlRemap.Result.NEEDS_PERMISSION -> StepOutcome.NEEDS_PERMISSION
            FnCtrlRemap.Result.FAILED -> StepOutcome.FAILED
        }
}
