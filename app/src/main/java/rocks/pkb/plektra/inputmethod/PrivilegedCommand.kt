package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Build

/**
 * The closed set of shell commands the app may run through the embedded ADB broker.
 *
 * SECURITY RULE: the broker runs whatever string it is
 * given as the `shell` user, so nothing may reach it except a case of this class, and the only
 * door is [EmbeddedAdbShell.execute], which takes these cases and never a string. Every case is
 * built from constants. A value that has to vary is either an enum of constants or validated here
 * first (a key fingerprint against [FINGERPRINT]). No user text, intent extra, deep-link parameter, preference or other config
 * value ever becomes part of a command line.
 *
 * A group that needs another privileged command adds one case below, with a comment naming the
 * group and a stable [id] for the audit log, and runs it through [PrivilegedShell].
 */
internal sealed class PrivilegedCommand {

    /**
     * A short, stable name for the audit log. Written out by hand, because R8 renames classes in
     * release builds.
     */
    abstract val id: String

    /** The command line for this case. Throws if a value fails validation, so it never runs. */
    abstract fun commandLine(appPackage: String): String

    /**
     * True for a command that may end the connection (unpairing this key, switching Wireless
     * debugging off). It must be the last command of its session, and its outcome is checked
     * afterwards rather than read from the shell.
     */
    open val terminal: Boolean get() = false

    /**
     * True for a command that only reads state and changes nothing on the phone. An open,
     * resumed screen ([BrokerTrigger.Kind.SCREEN]) may run these to show what the phone holds;
     * everything that writes needs a tap ([BrokerTrigger.Kind.TAP]) or a pairing code. Set it only
     * on a command whose every line is a read (`echo`).
     */
    open val readOnly: Boolean get() = false

    // the broker's own commands.

    /** Proves the broker reaches a real shell: a real adbd echoes the marker back. */
    object Verify : PrivilegedCommand() {
        override val id = "verify"
        override val readOnly: Boolean get() = true
        override fun commandLine(appPackage: String): String = "echo ${EmbeddedAdbShell.VERIFY_MARKER}"
    }

    /**
     * `IAdbManager.unpairDevice(fingerprint)` for THIS app's key: the shell holds
     * MANAGE_DEBUGGING, so the broker can remove itself from Settings > Wireless debugging. adbd
     * drops the connection as it does, so this is terminal; the reset flow then checks that a
     * fresh connection is refused. Construct it through [forThisDevice], which refuses an SDK whose
     * transaction code has not been checked.
     */
    class UnpairSelf private constructor(val fingerprint: String) : PrivilegedCommand() {
        override val id = "unpair_self"
        override val terminal: Boolean get() = true
        override fun commandLine(appPackage: String): String {
            require(FINGERPRINT.matches(fingerprint)) { "invalid key fingerprint" }
            require(canUnpairOnSdk(Build.VERSION.SDK_INT)) { "unpairDevice not verified on this SDK" }
            return "service call adb $TXN_UNPAIR_DEVICE s16 $fingerprint"
        }

        companion object {
            /** Null when this SDK's transaction code is unverified or [fingerprint] is malformed. */
            fun forThisDevice(fingerprint: String, sdkInt: Int = Build.VERSION.SDK_INT): UnpairSelf? =
                if (canUnpairOnSdk(sdkInt) && FINGERPRINT.matches(fingerprint)) UnpairSelf(fingerprint) else null

            /**
             * True when `service call adb` prints a reply parcel with no exception: the first word
             * of a `Result: Parcel(...)` is the exception code, 0 for none.
             */
            fun replyIsSuccess(output: String?): Boolean =
                output != null && Regex("Result: Parcel\\(0{8}[\\s)]").containsMatchIn(output)
        }
    }

    /**
     * Switches Wireless debugging off. adbd stops listening and the connection ends,
     * so this is terminal; the outcome is read back from the `adb_wifi_enabled` global.
     */
    object DisableWirelessDebugging : PrivilegedCommand() {
        override val id = "wireless_debugging_off"
        override val terminal: Boolean get() = true
        override fun commandLine(appPackage: String): String = "settings put global $ADB_WIFI_ENABLED 0"
    }

    companion object {
        /** An adb key fingerprint: 16 MD5 bytes as uppercase hex pairs joined by colons. */
        val FINGERPRINT = Regex("^([0-9A-F]{2}:){15}[0-9A-F]{2}$")

        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

        /**
         * `unpairDevice` is the 7th method of android.debug.IAdbManager, so its transaction code
         * is FIRST_CALL_TRANSACTION + 6 = 7. Checked in AOSP frameworks/base
         * core/java/android/debug/IAdbManager.aidl at android-11.0.0_r1, 12.0.0_r1, 13.0.0_r1,
         * 14.0.0_r1, 15.0.0_r1, 16.0.0_r2, 16.0.0_r3, android16-qpr1-release, android16-qpr2-release
         * and main (2026-10-13): the method order is unchanged from 11 to 16.
         */
        const val TXN_UNPAIR_DEVICE = 7

        /** SDK levels whose IAdbManager source was checked (Android 11 to 16). */
        val UNPAIR_VERIFIED_SDKS = Build.VERSION_CODES.R..36

        fun canUnpairOnSdk(sdkInt: Int): Boolean = sdkInt in UNPAIR_VERIFIED_SDKS
    }
}

/**
 * What a [PrivilegedCommand] did. [failure] is a short reason when [ok] is false. [status] says
 * why the broker could not run at all (no pairing, Wireless debugging off, a changed adbd
 * certificate, ...), or is null when it ran.
 */
internal data class PrivilegedResult(
    val ok: Boolean,
    val output: String?,
    val failure: String?,
    val status: EmbeddedAdbShell.BrokerStatus? = null,
)

/**
 * The broker for the rest of the app: it takes [PrivilegedCommand]s and a [BrokerTrigger], never a
 * string. Blocking; call it off the main thread, and only from a user action in the app's own
 * screens, never from the IME's key-handling path.
 */
internal object PrivilegedShell {
    // One queue for every privileged step, so two steps started together run one after the other
    // instead of competing for the broker's mDNS discovery.
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Runs [block] off the caller's thread, after any step already queued. Never throws. */
    fun runAsync(block: () -> Unit) {
        runCatching { executor.execute { runCatching(block) } }
    }

    fun run(context: Context, command: PrivilegedCommand, trigger: BrokerTrigger?): PrivilegedResult =
        EmbeddedAdbShell.execute(context, listOf(command), trigger).single()

    /** Runs [commands] in order on one connection; see [EmbeddedAdbShell.execute]. */
    fun runSession(
        context: Context,
        commands: List<PrivilegedCommand>,
        trigger: BrokerTrigger?,
    ): List<PrivilegedResult> = EmbeddedAdbShell.execute(context, commands, trigger)
}
