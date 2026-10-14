package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Build
import rocks.pkb.plektra.toolbox.BloatCatalog
import rocks.pkb.plektra.toolbox.SystemTweaks

/**
 * The closed set of shell commands the app may run through the embedded ADB broker.
 *
 * SECURITY RULE (adversary review 2026-10-12, finding 1.3g): the broker runs whatever string it is
 * given as the `shell` user, so nothing may reach it except a case of this class, and the only
 * door is [EmbeddedAdbShell.execute], which takes these cases and never a string. Every case is
 * built from constants. A value that has to vary is either an enum of constants or validated here
 * first (the app's own package name against [isValidPackageName], a key fingerprint against
 * [FINGERPRINT]). No user text, intent extra, deep-link parameter, preference or other config
 * value ever becomes part of a command line.
 *
 * A group that needs another privileged command adds one case below, with a comment naming the
 * group and a stable [id] for the audit log, and runs it through [PrivilegedShell].
 *
 * The backlight, ring and toolbox add their cases below the broker's own, each with an
 * [id]. Package names are validated with [isValidPackageName] (removal also needs the toolbox
 * catalog's approval), numbers are Ints in a fixed range, and settings keys and values come from
 * constants and enums here and in `toolbox.SystemTweaks`.
 */
internal sealed class PrivilegedCommand {

    /**
     * A short, stable name for the audit log. Written out by hand, because R8 renames classes in
     * release builds.
     */
    abstract val id: String

    /**
     * The command as a sequence of argv arrays, run one after the other (a case that used to be
     * `a; b; c` is three arrays). Shizuku starts each with `ProcessBuilder(argv)`, no shell, so
     * nothing in a value can ever be read as shell syntax. Throws if a value fails validation, so
     * it never runs. The only builders are [one] and the cases below.
     */
    abstract fun argvs(appPackage: String): List<List<String>>

    /**
     * The same command as one line for adbd's `shell:` service (the broker route), which does hand
     * it to a shell: [argvs] joined with `;`, each word quoted unless it is plainly safe. Derived,
     * so the two routes cannot drift apart.
     */
    fun commandLine(appPackage: String): String = shellLine(argvs(appPackage))

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
     * on a command whose every line is a read (`echo`, `settings get`, `pm list`, `wm density`
     * without an argument, a vendor GET).
     */
    open val readOnly: Boolean get() = false

    // the broker's own commands.

    /** Proves the broker reaches a real shell: a real adbd echoes the marker back. */
    object Verify : PrivilegedCommand() {
        override val id = "verify"
        override val readOnly: Boolean get() = true
        override fun argvs(appPackage: String) = one("echo", EmbeddedAdbShell.VERIFY_MARKER)
    }

    /**
     * `pm grant` of WRITE_SECURE_SETTINGS to this app, only while a feature needs it
     * ([WriteSecureSettingsHolders]). The result is confirmed with `checkSelfPermission`, never by
     * reading the shell's output.
     */
    object GrantWriteSecureSettings : PrivilegedCommand() {
        override val id = "grant_write_secure_settings"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            return one("pm", "grant", appPackage, WRITE_SECURE_SETTINGS)
        }
    }

    /** Undo of [GrantWriteSecureSettings]: the last feature needing it was turned off, or a reset. */
    object RevokeWriteSecureSettings : PrivilegedCommand() {
        override val id = "revoke_write_secure_settings"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            return one("pm", "revoke", appPackage, WRITE_SECURE_SETTINGS)
        }
    }

    /** Withdraws notification access the broker gave one of this app's listeners. */
    class DisallowNotificationListener(val listener: AppNotificationListener) : PrivilegedCommand() {
        override val id = "disallow_listener_${listener.id}"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            return one("cmd", "notification", "disallow_listener", "$appPackage/${listener.className}")
        }
    }

    /**
     * `IAdbManager.unpairDevice(fingerprint)` for THIS app's key (finding 1.3b): the shell holds
     * MANAGE_DEBUGGING, so the broker can remove itself from Settings > Wireless debugging. adbd
     * drops the connection as it does, so this is terminal; the reset flow then checks that a
     * fresh connection is refused. Construct it through [forThisDevice], which refuses an SDK whose
     * transaction code has not been checked.
     */
    class UnpairSelf private constructor(val fingerprint: String) : PrivilegedCommand() {
        override val id = "unpair_self"
        override val terminal: Boolean get() = true
        override fun argvs(appPackage: String): List<List<String>> {
            require(FINGERPRINT.matches(fingerprint)) { "invalid key fingerprint" }
            require(canUnpairOnSdk(Build.VERSION.SDK_INT)) { "unpairDevice not verified on this SDK" }
            return one("service", "call", "adb", "$TXN_UNPAIR_DEVICE", "s16", fingerprint)
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
     * Switches Wireless debugging off (finding 1.3e). adbd stops listening and the connection ends,
     * so this is terminal; the outcome is read back from the `adb_wifi_enabled` global.
     */
    object DisableWirelessDebugging : PrivilegedCommand() {
        override val id = "wireless_debugging_off"
        override val terminal: Boolean get() = true
        override fun argvs(appPackage: String) = one("settings", "put", "global", ADB_WIFI_ENABLED, "0")
    }

    // the commands the smart keyboard backlight and its Quick Settings tile need.

    /** Reads the vendor keyboard-backlight timeout (`GET` on the vendor binder service). */
    object ReadBacklightTimeout : PrivilegedCommand() {
        override val id = "read_backlight_timeout"
        override val readOnly: Boolean get() = true
        override fun argvs(appPackage: String) =
            one("service", "call", VENDOR_SERVICE, TXN_GET, "s16", KEY_BACKLIGHT_TIMEOUT)
    }

    /** Writes the vendor keyboard-backlight timeout (`SET` on the vendor binder service). */
    class WriteBacklightTimeout(val timeout: BacklightTimeout) : PrivilegedCommand() {
        override val id = "write_backlight_timeout_${timeout.name.lowercase()}"
        override fun argvs(appPackage: String) =
            one("service", "call", VENDOR_SERVICE, TXN_SET, "s16", KEY_BACKLIGHT_TIMEOUT, "s16", timeout.wireValue)
    }

    /** The only two values the app ever writes for the vendor timeout. */
    enum class BacklightTimeout(val wireValue: String) {
        /** Sentinel: never turn off. Verified on a Titan 2 Elite, 2026-08-21. Survives reboots. */
        ALWAYS_ON("-1"),

        /** The stock 30 s default. */
        STOCK("30000")
    }

    // the grants the notification ring needs. ONE command carrying a list, so a single broker
    // connection does all of them; only the cases of [RingGrant] can be named, and the listener
    // component is the validated package plus a constant class name.
    class RingGrants(val grants: List<RingGrant>) : PrivilegedCommand() {
        override val id = "ring_grants"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            require(grants.isNotEmpty()) { "no grants" }
            return grants.map { it.argv(appPackage) }
        }
    }

    enum class RingGrant {
        /** Notification access for the ring listener. */
        ALLOW_LISTENER,

        /** Lets the ring announce itself with a full-screen intent over the lock screen. */
        ALLOW_FULL_SCREEN_INTENT,

        /** The silent notification the full-screen intent hangs on. */
        ALLOW_POST_NOTIFICATIONS,

        /** Undo of [ALLOW_LISTENER], for a reset to stock. */
        DISALLOW_LISTENER,

        /** Undo of [ALLOW_FULL_SCREEN_INTENT], for a reset to stock. */
        RESET_FULL_SCREEN_INTENT;

        fun argv(appPackage: String): List<String> = when (this) {
            ALLOW_LISTENER -> listOf("cmd", "notification", "allow_listener", "$appPackage/$RING_LISTENER_CLASS")
            ALLOW_FULL_SCREEN_INTENT -> listOf("appops", "set", appPackage, "USE_FULL_SCREEN_INTENT", "allow")
            ALLOW_POST_NOTIFICATIONS -> listOf("appops", "set", appPackage, "POST_NOTIFICATION", "allow")
            DISALLOW_LISTENER -> listOf("cmd", "notification", "disallow_listener", "$appPackage/$RING_LISTENER_CLASS")
            RESET_FULL_SCREEN_INTENT -> listOf("appops", "set", appPackage, "USE_FULL_SCREEN_INTENT", "default")
        }
    }

    // the commands the Titan 2 Elite toolbox needs (bloat remover, display density,
    // system tweaks, Fn -> Ctrl). Package names are validated with [isValidPackageName], removal
    // also needs the catalog's approval; numbers are Ints in a fixed range; settings keys and
    // values come from constants and enums in this block and in `toolbox.SystemTweaks`.

    /** Three read-only listings in one round trip: enabled, disabled, and everything. */
    object ListPackageStates : PrivilegedCommand() {
        override val id = "list_package_states"
        override val readOnly: Boolean get() = true
        override fun argvs(appPackage: String) = listOf(
            listOf("echo", "__E__"), listOf("pm", "list", "packages", "-e"),
            listOf("echo", "__D__"), listOf("pm", "list", "packages", "-d"),
            listOf("echo", "__U__"), listOf("pm", "list", "packages", "-u"),
        )
    }

    /** Hide a catalog package and stop it running. Reversible. */
    class DisablePackage(val packageName: String) : PrivilegedCommand() {
        override val id = "disable_package"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isRemovableCatalogPackage(packageName)) { "package is not removable" }
            return one("pm", "disable-user", "--user", "0", packageName)
        }
    }

    /** Uninstall a catalog package for user 0. The APK stays on /system, so it can come back. */
    class UninstallPackage(val packageName: String) : PrivilegedCommand() {
        override val id = "uninstall_package"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isRemovableCatalogPackage(packageName)) { "package is not removable" }
            return one("pm", "uninstall", "--user", "0", packageName)
        }
    }

    /**
     * Bring back a package the journal says the toolbox removed. Only the name shape is checked:
     * the catalog may have retired the entry since, and a journaled package must still come back.
     */
    class InstallExistingPackage(val packageName: String) : PrivilegedCommand() {
        override val id = "install_existing_package"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isValidPackageName(packageName)) { "invalid package name" }
            return one("cmd", "package", "install-existing", "--user", "0", packageName)
        }
    }

    /** Re-enable a package that was disabled. Same name rule as [InstallExistingPackage]. */
    class EnablePackage(val packageName: String) : PrivilegedCommand() {
        override val id = "enable_package"
        override fun argvs(appPackage: String): List<List<String>> {
            require(isValidPackageName(packageName)) { "invalid package name" }
            return one("pm", "enable", "--user", "0", packageName)
        }
    }

    object ReadDensity : PrivilegedCommand() {
        override val id = "read_density"
        override val readOnly: Boolean get() = true
        override fun argvs(appPackage: String) = one("wm", "density")
    }

    object ResetDensity : PrivilegedCommand() {
        override val id = "reset_density"
        override fun argvs(appPackage: String) = one("wm", "density", "reset")
    }

    /** `wm density <dpi>`, the dpi an Int inside [MIN_DPI]..[MAX_DPI]. */
    class SetDensity(val dpi: Int) : PrivilegedCommand() {
        override val id = "set_density"
        override fun argvs(appPackage: String): List<List<String>> {
            require(dpi in MIN_DPI..MAX_DPI) { "density out of range" }
            return one("wm", "density", "$dpi")
        }

        companion object {
            const val MIN_DPI = 100
            const val MAX_DPI = 1000
        }
    }

    /** One `settings get` per tweak, in the order `SystemTweaks.parse` reads them. */
    object ReadSystemTweaks : PrivilegedCommand() {
        override val id = "read_system_tweaks"
        override val readOnly: Boolean get() = true
        override fun argvs(appPackage: String): List<List<String>> = buildList {
            add(listOf("settings", "get", "global", SystemTweaks.ANIMATION_KEYS.first()))
            SystemTweaks.Toggle.entries.forEach { add(listOf("settings", "get", it.namespace, it.key)) }
        }
    }

    class SetAnimationSpeed(val speed: SystemTweaks.AnimationSpeed) : PrivilegedCommand() {
        override val id = "set_animation_speed"
        override fun argvs(appPackage: String) =
            SystemTweaks.ANIMATION_KEYS.map { listOf("settings", "put", "global", it, speed.arg) }
    }

    /** On writes 1; off DELETES the key, because these ship unset. */
    class SetTweak(val toggle: SystemTweaks.Toggle, val enabled: Boolean) : PrivilegedCommand() {
        override val id = "set_tweak"
        override fun argvs(appPackage: String) =
            if (enabled) {
                one("settings", "put", toggle.namespace, toggle.key, "1")
            } else {
                one("settings", "delete", toggle.namespace, toggle.key)
            }
    }

    object ResetSystemTweaks : PrivilegedCommand() {
        override val id = "reset_system_tweaks"
        override fun argvs(appPackage: String) =
            SystemTweaks.ANIMATION_KEYS.map { listOf("settings", "put", "global", it, "1.0") } +
                SystemTweaks.Toggle.entries.map { listOf("settings", "delete", it.namespace, it.key) }
    }

    /**
     * Writes the two vendor programmable-key rows. Both are Ints in 0..[MAX_VALUE]: 1/1 maps Fn to
     * Ctrl, and a revert writes back whatever small integers were there before.
     */
    class SetFnProgrammableKey(val enable: Int, val function: Int) : PrivilegedCommand() {
        override val id = "set_fn_programmable_key"
        override fun argvs(appPackage: String): List<List<String>> {
            require(enable in 0..MAX_VALUE && function in 0..MAX_VALUE) { "value out of range" }
            return listOf(
                listOf("settings", "put", "system", KEY_ENABLE, "$enable"),
                listOf("settings", "put", "system", KEY_FUNCTION, "$function"),
            )
        }

        companion object {
            const val KEY_ENABLE = "fn_programmable_key_enable"
            const val KEY_FUNCTION = "fn_programmable_key_function"
            const val MAX_VALUE = 1000
        }
    }

    companion object {
        /** Same shape the broker review asks for: dotted Java package, no shell metacharacters. */
        private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun isValidPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

        /** A command of one argv. */
        fun one(vararg argv: String): List<List<String>> = listOf(argv.toList())

        private val PLAIN_WORD = Regex("^[A-Za-z0-9_./:=@%+,-]+$")

        /** Argv arrays as one shell line for adbd; a word with anything unusual is single-quoted. */
        fun shellLine(argvs: List<List<String>>): String = argvs.joinToString("; ") { argv ->
            argv.joinToString(" ") { if (PLAIN_WORD.matches(it)) it else "'" + it.replace("'", "'\\''") + "'" }
        }

        /** An adb key fingerprint: 16 MD5 bytes as uppercase hex pairs joined by colons. */
        val FINGERPRINT = Regex("^([0-9A-F]{2}:){15}[0-9A-F]{2}$")

        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
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

        /** the ring's notification listener, as a constant so no class is loaded to name it. */
        const val RING_LISTENER_CLASS = "rocks.pkb.plektra.ring.NotificationRingListener"

        // valid AND approved by the toolbox catalog (its deny-list and patterns apply).
        fun isRemovableCatalogPackage(name: String): Boolean =
            isValidPackageName(name) && BloatCatalog.isRemovable(name)

        // the vendor binder service. transaction 2 = SET(String key, String value),
        // transaction 1 = GET(String key); GET returns a String16 parcel with the current value.
        // Confirmed on a Titan 2 Elite, 2026-08-31.
        private const val VENDOR_SERVICE = "agui_functional_service"
        private const val TXN_SET = "2"
        private const val TXN_GET = "1"
        private const val KEY_BACKLIGHT_TIMEOUT = "keyboard_brightness_timeout"
    }
}

/**
 * Notification listeners of this app that the broker may have allowed (`cmd notification
 * allow_listener`), so a reset can withdraw that access. Each feature that allows one records it
 * with [BrokerGrantLedger.recordListenerAllowed] when its grant succeeds.
 */
internal enum class AppNotificationListener(val id: String, val className: String) {
    /** [PrivilegedCommand.RingGrant.ALLOW_LISTENER]. */
    RING("ring", PrivilegedCommand.RING_LISTENER_CLASS),
}

/**
 * What a [PrivilegedCommand] did. [failure] is a short reason when [ok] is false. [status] says
 * why the broker could not run at all (no pairing, Wireless debugging off, a changed adbd
 * certificate, Shizuku unavailable, ...), or is null when it ran. [backend] is the route the door
 * chose, or null when the door refused the command before choosing.
 */
internal data class PrivilegedResult(
    val ok: Boolean,
    val output: String?,
    val failure: String?,
    val status: EmbeddedAdbShell.BrokerStatus? = null,
    val backend: PrivilegedBackend? = null,
)

/**
 * The broker for the rest of the app: it takes [PrivilegedCommand]s and a [BrokerTrigger], never a
 * string. Blocking; call it off the main thread, and only from a user action in the app's own
 * screens (finding 1.3g), never from the IME's key-handling path.
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
