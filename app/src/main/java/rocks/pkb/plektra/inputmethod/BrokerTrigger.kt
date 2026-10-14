package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import moe.shizuku.manager.adb.AdbPairingService

/**
 * Proof that a broker connection was asked for by the user (adversary review 2026-10-12, finding
 * 1.3g). [EmbeddedAdbShell.execute] refuses to connect without one.
 *
 * The broker connects only on an explicit user action in the app's own screens: a tap (pair,
 * apply, forget), one of the app's screens being open (reads only: [PrivilegedCommand.readOnly]),
 * or a pairing code the user just typed. The IME service is not an Activity, so the input-handling path cannot mint one, and a
 * trigger goes stale after [VALID_FOR_MS], so one cannot be kept for later.
 *
 * This is a design rule made hard to break by accident, not a boundary against code already
 * running in this process: such code could call [fromTap] itself. What protects the key from that
 * is AndroidKeyStore (finding 1.3d), Wireless debugging being off (1.3e) and the audit log (1.3h).
 */
class BrokerTrigger private constructor(
    val kind: Kind,
    /** Where it came from, for the audit log, e.g. "forget_pairing". A constant, never user text. */
    val source: String,
    private val mintedAtMs: Long,
) {
    enum class Kind {
        /** A tap on a button or switch in one of the app's screens. */
        TAP,

        /**
         * One of the app's screens is open and resumed. Allows [PrivilegedCommand.readOnly]
         * commands only (verify, and the reads a screen shows); every write needs [TAP].
         */
        SCREEN,

        /** The user typed a pairing code into the pairing notification. */
        PAIRING,
    }

    internal fun isFresh(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        nowMs - mintedAtMs in 0..VALID_FOR_MS

    internal fun allows(command: PrivilegedCommand): Boolean =
        kind != Kind.SCREEN || command.readOnly

    companion object {
        /** Long enough for a reset (two mDNS searches and three commands), short enough not to keep. */
        const val VALID_FOR_MS = 120_000L

        /** For a tap in [context]'s Activity. Null unless that Activity is resumed. */
        fun fromTap(context: Context, source: String): BrokerTrigger? =
            mint(context, Kind.TAP, source)

        /** For a read a screen runs while it is open (read-only commands). Null unless resumed. */
        fun forScreen(context: Context, source: String): BrokerTrigger? =
            mint(context, Kind.SCREEN, source)

        /**
         * For the setup that follows a pairing code the user typed. It takes the pairing service
         * itself, so the IME service (also a Service) cannot pass for it.
         */
        @Suppress("UNUSED_PARAMETER")
        @RequiresApi(Build.VERSION_CODES.R)
        fun fromPairingCode(service: AdbPairingService): BrokerTrigger =
            BrokerTrigger(Kind.PAIRING, "pairing", SystemClock.elapsedRealtime())

        @VisibleForTesting
        internal fun forTest(kind: Kind, mintedAtMs: Long = SystemClock.elapsedRealtime()): BrokerTrigger =
            BrokerTrigger(kind, "test", mintedAtMs)

        private fun mint(context: Context, kind: Kind, source: String): BrokerTrigger? {
            val activity = activityOf(context) ?: return null
            if (activity.isFinishing) return null
            val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) ?: false
            if (!resumed) return null
            return BrokerTrigger(kind, source, SystemClock.elapsedRealtime())
        }

        private tailrec fun activityOf(context: Context): Activity? = when (context) {
            is Activity -> context
            is ContextWrapper -> activityOf(context.baseContext)
            else -> null
        }
    }
}
