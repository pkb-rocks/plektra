package rocks.pkb.plektra.toolbox

import android.content.Context
import android.content.res.Resources
import android.util.Log
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedBackends
import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import rocks.pkb.plektra.inputmethod.PrivilegedShell

/**
 * Screen density, the most-asked-for Android tweak there is.
 *
 * On a 1080x1200 Titan panel the payoff is unusually direct: the screen is short, so every
 * step down in density buys another line or two of whatever you are reading, and the physical
 * keyboard means none of that space goes back to a soft keyboard.
 *
 * Applied through [RevertibleChange] rather than directly, because a bad value is exactly the
 * failure a confirmation dialog cannot save you from. The density is an Int that must sit in
 * [range] AND inside [PrivilegedCommand.SetDensity]'s absolute bounds before a command exists.
 */
object DisplayDensity {

    private const val TAG = "DisplayDensity"

    /**
     * Bounds, not a preference. Below roughly 60% of stock the system UI starts laying out in
     * ways nothing was designed for; above 140% the keyboard's own rows stop fitting. The
     * auto-revert catches a bad choice, but it should not be reachable in the first place.
     */
    private const val MIN_FACTOR = 0.6f
    private const val MAX_FACTOR = 1.4f

    data class Info(
        /** What the panel reports — the value "reset" returns to. */
        val physical: Int,
        /** What is in force now, which may be an override. */
        val current: Int
    ) {
        val isOverridden: Boolean get() = physical != current
    }

    fun range(physical: Int): IntRange =
        (physical * MIN_FACTOR).toInt()..(physical * MAX_FACTOR).toInt()

    /**
     * Reads the stock density from the shell and the density in force from the system itself.
     *
     * `wm density` prints "Physical density: N" and, only when an override is in force, a
     * second "Override density: N" line. The stock value can only come from there; what is in
     * force right now is taken from the system resources, which is what the screen is really
     * drawn with, and falls back to the shell's answer.
     */
    fun read(context: Context, trigger: BrokerTrigger?): Info? {
        if (!PrivilegedBackends.hasAccess(context)) return null
        val out = PrivilegedShell.run(context, PrivilegedCommand.ReadDensity, trigger).output ?: return null
        val physical = parsePhysical(out) ?: return null
        val shellCurrent = parseOverride(out) ?: physical
        val local = Resources.getSystem().displayMetrics.densityDpi
        return Info(physical = physical, current = if (local > 0) local else shellCurrent)
    }

    internal fun parsePhysical(output: String): Int? =
        Regex("Physical density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()

    internal fun parseOverride(output: String): Int? =
        Regex("Override density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Applies a density with the revert already armed.
     *
     * The revert is always `wm density reset` rather than "back to the previous number":
     * chaining changes would otherwise leave the undo pointing at another override rather
     * than at the value the panel actually shipped with.
     */
    fun apply(context: Context, info: Info, density: Int, trigger: BrokerTrigger?): RevertibleChange.Outcome {
        val allowed = range(info.physical)
        if (density !in allowed || density !in PrivilegedCommand.SetDensity.MIN_DPI..PrivilegedCommand.SetDensity.MAX_DPI) {
            Log.w(TAG, "Refusing density $density outside $allowed")
            return RevertibleChange.Outcome.Failed("Outside the safe range")
        }
        return RevertibleChange.apply(
            context, RevertibleChange.Kind.DENSITY, PrivilegedCommand.SetDensity(density), trigger
        )
    }

    /** Back to the panel's own density, with no confirmation window — this is always safe. */
    fun reset(context: Context, trigger: BrokerTrigger?): Boolean {
        if (!PrivilegedBackends.hasAccess(context)) return false
        val ok = PrivilegedShell.run(context, PrivilegedCommand.ResetDensity, trigger).ok
        if (ok) RevertibleChange.keep(context)
        return ok
    }
}
