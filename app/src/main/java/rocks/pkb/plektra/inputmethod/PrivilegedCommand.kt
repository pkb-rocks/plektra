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
 * The backlight and toolbox add their cases below the broker's own, each with an
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
     * on a command whose every line is a read (`echo`, `settings get`, `pm list`, `wm density`
     * without an argument, a vendor GET).
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
     * `pm grant` of WRITE_SECURE_SETTINGS to this app, only while a feature needs it
     * ([WriteSecureSettingsHolders]). The result is confirmed with `checkSelfPermission`, never by
     * reading the shell's output.
     */
    object GrantWriteSecureSettings : PrivilegedCommand() {
        override val id = "grant_write_secure_settings"
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            return "pm grant $appPackage $WRITE_SECURE_SETTINGS"
        }
    }

    /** Undo of [GrantWriteSecureSettings]: the last feature needing it was turned off, or a reset. */
    object RevokeWriteSecureSettings : PrivilegedCommand() {
        override val id = "revoke_write_secure_settings"
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            return "pm revoke $appPackage $WRITE_SECURE_SETTINGS"
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
     * Switches Wireless debugging off (finding 1.3e). adbd stops listening and the connection ends,
     * so this is terminal; the outcome is read back from the `adb_wifi_enabled` global.
     */
    object DisableWirelessDebugging : PrivilegedCommand() {
        override val id = "wireless_debugging_off"
        override val terminal: Boolean get() = true
        override fun commandLine(appPackage: String): String = "settings put global $ADB_WIFI_ENABLED 0"
    }

    // the commands the smart keyboard backlight and its Quick Settings tile need.

    /** Reads the vendor keyboard-backlight timeout (`GET` on the vendor binder service). */
    object ReadBacklightTimeout : PrivilegedCommand() {
        override val id = "read_backlight_timeout"
        override val readOnly: Boolean get() = true
        override fun commandLine(appPackage: String): String =
            "service call $VENDOR_SERVICE $TXN_GET s16 \"$KEY_BACKLIGHT_TIMEOUT\""
    }

    /** Writes the vendor keyboard-backlight timeout (`SET` on the vendor binder service). */
    class WriteBacklightTimeout(val timeout: BacklightTimeout) : PrivilegedCommand() {
        override val id = "write_backlight_timeout_${timeout.name.lowercase()}"
        override fun commandLine(appPackage: String): String =
            "service call $VENDOR_SERVICE $TXN_SET s16 \"$KEY_BACKLIGHT_TIMEOUT\" s16 \"${timeout.wireValue}\""
    }

    /** The only two values the app ever writes for the vendor timeout. */
    enum class BacklightTimeout(val wireValue: String) {
        /** Sentinel: never turn off. Verified on a Titan 2 Elite, 2026-08-21. Survives reboots. */
        ALWAYS_ON("-1"),

        /** The stock 30 s default. */
        STOCK("30000")
    }

    // the commands the Titan 2 Elite toolbox needs (bloat remover, display density,
    // system tweaks, Fn -> Ctrl). Package names are validated with [isValidPackageName], removal
    // also needs the catalog's approval; numbers are Ints in a fixed range; settings keys and
    // values come from constants and enums in this block and in `toolbox.SystemTweaks`.

    /** Three read-only listings in one round trip: enabled, disabled, and everything. */
    object ListPackageStates : PrivilegedCommand() {
        override val id = "list_package_states"
        override val readOnly: Boolean get() = true
        override fun commandLine(appPackage: String): String =
            "echo __E__; pm list packages -e; echo __D__; pm list packages -d; echo __U__; pm list packages -u"
    }

    /** Hide a catalog package and stop it running. Reversible. */
    class DisablePackage(val packageName: String) : PrivilegedCommand() {
        override val id = "disable_package"
        override fun commandLine(appPackage: String): String {
            require(isRemovableCatalogPackage(packageName)) { "package is not removable" }
            return "pm disable-user --user 0 $packageName"
        }
    }

    /** Uninstall a catalog package for user 0. The APK stays on /system, so it can come back. */
    class UninstallPackage(val packageName: String) : PrivilegedCommand() {
        override val id = "uninstall_package"
        override fun commandLine(appPackage: String): String {
            require(isRemovableCatalogPackage(packageName)) { "package is not removable" }
            return "pm uninstall --user 0 $packageName"
        }
    }

    /**
     * Bring back a package the journal says the toolbox removed. Only the name shape is checked:
     * the catalog may have retired the entry since, and a journaled package must still come back.
     */
    class InstallExistingPackage(val packageName: String) : PrivilegedCommand() {
        override val id = "install_existing_package"
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(packageName)) { "invalid package name" }
            return "cmd package install-existing --user 0 $packageName"
        }
    }

    /** Re-enable a package that was disabled. Same name rule as [InstallExistingPackage]. */
    class EnablePackage(val packageName: String) : PrivilegedCommand() {
        override val id = "enable_package"
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(packageName)) { "invalid package name" }
            return "pm enable --user 0 $packageName"
        }
    }

    object ReadDensity : PrivilegedCommand() {
        override val id = "read_density"
        override val readOnly: Boolean get() = true
        override fun commandLine(appPackage: String): String = "wm density"
    }

    object ResetDensity : PrivilegedCommand() {
        override val id = "reset_density"
        override fun commandLine(appPackage: String): String = "wm density reset"
    }

    /** `wm density <dpi>`, the dpi an Int inside [MIN_DPI]..[MAX_DPI]. */
    class SetDensity(val dpi: Int) : PrivilegedCommand() {
        override val id = "set_density"
        override fun commandLine(appPackage: String): String {
            require(dpi in MIN_DPI..MAX_DPI) { "density out of range" }
            return "wm density $dpi"
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
        override fun commandLine(appPackage: String): String = buildList {
            add("settings get global ${SystemTweaks.ANIMATION_KEYS.first()}")
            SystemTweaks.Toggle.entries.forEach { add("settings get ${it.namespace} ${it.key}") }
        }.joinToString("; ")
    }

    class SetAnimationSpeed(val speed: SystemTweaks.AnimationSpeed) : PrivilegedCommand() {
        override val id = "set_animation_speed"
        override fun commandLine(appPackage: String): String =
            SystemTweaks.ANIMATION_KEYS.joinToString("; ") { "settings put global $it ${speed.arg}" }
    }

    /** On writes 1; off DELETES the key, because these ship unset. */
    class SetTweak(val toggle: SystemTweaks.Toggle, val enabled: Boolean) : PrivilegedCommand() {
        override val id = "set_tweak"
        override fun commandLine(appPackage: String): String =
            if (enabled) {
                "settings put ${toggle.namespace} ${toggle.key} 1"
            } else {
                "settings delete ${toggle.namespace} ${toggle.key}"
            }
    }

    object ResetSystemTweaks : PrivilegedCommand() {
        override val id = "reset_system_tweaks"
        override fun commandLine(appPackage: String): String = (
            SystemTweaks.ANIMATION_KEYS.map { "settings put global $it 1.0" } +
                SystemTweaks.Toggle.entries.map { "settings delete ${it.namespace} ${it.key}" }
            ).joinToString("; ")
    }

    /**
     * Writes the two vendor programmable-key rows. Both are Ints in 0..[MAX_VALUE]: 1/1 maps Fn to
     * Ctrl, and a revert writes back whatever small integers were there before.
     */
    class SetFnProgrammableKey(val enable: Int, val function: Int) : PrivilegedCommand() {
        override val id = "set_fn_programmable_key"
        override fun commandLine(appPackage: String): String {
            require(enable in 0..MAX_VALUE && function in 0..MAX_VALUE) { "value out of range" }
            return "settings put system $KEY_ENABLE $enable; settings put system $KEY_FUNCTION $function"
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
