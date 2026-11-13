package rocks.pkb.plektra.toolbox

import android.content.Context
import android.provider.Settings
import android.util.Log
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedBackends
import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import rocks.pkb.plektra.inputmethod.PrivilegedShell

/**
 * Small system settings that Android supports but this ROM never surfaces.
 *
 * Unlike density these cannot make the phone unusable, so they apply directly rather than
 * through [RevertibleChange] — a countdown on "make animations faster" would be theatre. What
 * they do share is that every one is a plain settings write the broker can also undo, which
 * is why each can be put back to stock.
 *
 * The keys and values are constants here; [PrivilegedCommand] turns them into command lines.
 * Animation writes are confirmed by reading the setting back through the Settings API.
 *
 * BLOCKING — call off the main thread, and never from the IME input path.
 */
object SystemTweaks {

    private const val TAG = "SystemTweaks"

    /**
     * Android animates in three places and reads three separate globals. Setting one and not
     * the others is the classic half-done version of this tweak, where menus feel snappy and
     * app launches still crawl.
     */
    internal val ANIMATION_KEYS = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale"
    )

    /** [arg] is the literal written to the settings table. */
    enum class AnimationSpeed(val scale: Float, val arg: String) {
        OFF(0f, "0"),
        FAST(0.5f, "0.5"),
        NORMAL(1f, "1.0");

        companion object {
            fun nearest(scale: Float): AnimationSpeed =
                entries.minByOrNull { kotlin.math.abs(it.scale - scale) } ?: NORMAL
        }
    }

    /** A boolean the platform understands but this ROM leaves unset. */
    enum class Toggle(val namespace: String, val key: String) {
        /** Pixel's notification history: the log of what you swiped away. */
        NOTIFICATION_HISTORY("secure", "notification_history_enabled"),

        /**
         * Swipe down on the nav bar to pull the top of the screen into reach. The AOSP key is
         * `one_handed_mode_enabled` (PhysiBoard wrote `one_handed_enabled`, which nothing reads).
         */
        ONE_HANDED_MODE("secure", "one_handed_mode_enabled")
    }

    data class State(
        val animation: AnimationSpeed,
        val toggles: Map<Toggle, Boolean>
    )

    /** A read: the open screen's trigger is enough ([PrivilegedCommand.readOnly]). */
    fun read(context: Context, trigger: BrokerTrigger?): State? {
        if (!PrivilegedBackends.hasAccess(context)) return null
        val out = PrivilegedShell.run(context, PrivilegedCommand.ReadSystemTweaks, trigger).output ?: return null
        return parse(out)
    }

    /** Parses the output of [PrivilegedCommand.ReadSystemTweaks]: animation scale, then each toggle. */
    internal fun parse(output: String): State? {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null

        val animation = AnimationSpeed.nearest(lines.first().toFloatOrNull() ?: 1f)
        val toggles = Toggle.entries.mapIndexed { index, toggle ->
            // "null" is what settings prints for a key that was never written — which for
            // these two is the shipped state, not an error.
            toggle to (lines.getOrNull(index + 1) == "1")
        }.toMap()
        return State(animation = animation, toggles = toggles)
    }

    fun setAnimationSpeed(context: Context, speed: AnimationSpeed, trigger: BrokerTrigger?): Boolean {
        if (!PrivilegedShell.run(context, PrivilegedCommand.SetAnimationSpeed(speed), trigger).ok) return false
        // Public API, so this is a real second opinion rather than the shell agreeing with itself.
        val seen = runCatching {
            Settings.Global.getFloat(context.contentResolver, ANIMATION_KEYS.first())
        }.getOrNull()
        if (seen != null && kotlin.math.abs(seen - speed.scale) > 0.01f) {
            Log.w(TAG, "Animation scale reads $seen after writing ${speed.scale}")
            return false
        }
        return true
    }

    /**
     * Turning one of these off DELETES the key rather than writing 0: these ship unset, so
     * restoring means putting them back to absent, not to an explicit false the ROM never had.
     *
     * Not read back: the key is a hidden setting this app may not be allowed to read, and an
     * unreadable key looks exactly like an unset one.
     */
    fun setToggle(context: Context, toggle: Toggle, enabled: Boolean, trigger: BrokerTrigger?): Boolean =
        PrivilegedShell.run(context, PrivilegedCommand.SetTweak(toggle, enabled), trigger).ok

    /** Everything on this screen back to how the phone shipped. */
    fun resetAll(context: Context, trigger: BrokerTrigger?): Boolean =
        PrivilegedShell.run(context, PrivilegedCommand.ResetSystemTweaks, trigger).ok
}
