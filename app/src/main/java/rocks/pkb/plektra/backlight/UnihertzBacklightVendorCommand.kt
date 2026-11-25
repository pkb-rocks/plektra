package rocks.pkb.plektra.backlight

/**
 * The only shell commands the keyboard backlight feature runs through Shizuku.
 *
 * Each command is a fixed argument vector. The only variable part is the timeout value, which is
 * either the display-linked sentinel or a previously read timeout that passed [isRestorableTimeout].
 * `agui_common` also exposes transactions that write arbitrary files and touchpad firmware, so
 * nothing else may reach it.
 */
internal sealed class UnihertzBacklightVendorCommand {
    abstract fun argv(): List<String>

    /** Lists the registered binder services; used to confirm the interface behind `agui_common`. */
    object ListServices : UnihertzBacklightVendorCommand() {
        override fun argv() = listOf("service", "list")
    }

    /** `IACommonService.getStringFromFile(keyboard_brightness_timeout, null)`. */
    object ReadTimeout : UnihertzBacklightVendorCommand() {
        override fun argv() = listOf(
            "service", "call", SERVICE_NAME, TRANSACTION_GET_STRING, "s16", KEY_TIMEOUT
        )
    }

    /** `IACommonService.putStringToFile(keyboard_brightness_timeout, value)`. */
    class WriteTimeout private constructor(val value: String) : UnihertzBacklightVendorCommand() {
        override fun argv() = listOf(
            "service", "call", SERVICE_NAME, TRANSACTION_PUT_STRING, "s16", KEY_TIMEOUT, "s16", value
        )

        companion object {
            fun followDisplay() = WriteTimeout(TIMEOUT_FOLLOW_DISPLAY)

            /** Null unless [value] is a timeout that may be written back. */
            fun restore(value: String): WriteTimeout? =
                if (isRestorableTimeout(value)) WriteTimeout(value) else null
        }
    }

    companion object {
        const val SERVICE_NAME = "agui_common"
        const val SERVICE_DESCRIPTOR = "com.agui.ext.common.IACommonService"

        /**
         * Transaction codes from `IACommonService$Stub$Proxy` in agui-services.jar of the verified
         * firmware: 3 = getStringFromFile(String, String), 4 = putStringToFile(String, String).
         * The service implementation maps both to its key-value settings store.
         */
        const val TRANSACTION_GET_STRING = "3"
        const val TRANSACTION_PUT_STRING = "4"

        const val KEY_TIMEOUT = "keyboard_brightness_timeout"

        /** The vendor keyboard light controller keeps the light on until the display turns off. */
        const val TIMEOUT_FOLLOW_DISPLAY = "-1"

        /** Longest timeout accepted as an original value, one day in milliseconds. */
        private const val MAX_RESTORABLE_TIMEOUT_MS = 86_400_000L

        private val decimalTimeout = Regex("^(0|[1-9][0-9]{0,7})$")

        /** A plain decimal millisecond timeout: the vendor menu offers 0 (off) to 30000. */
        fun isRestorableTimeout(value: String): Boolean =
            decimalTimeout.matches(value) && value.toLong() <= MAX_RESTORABLE_TIMEOUT_MS

        private val serviceLine = Regex(
            "^\\s*\\d+\\s+" + Regex.escape(SERVICE_NAME) + ": \\[" + Regex.escape(SERVICE_DESCRIPTOR) + "]\\s*$"
        )

        /** True when `service list` shows `agui_common` backed by the verified interface. */
        fun serviceListShowsVerifiedInterface(output: String): Boolean =
            output.lineSequence().any { serviceLine.matches(it) }
    }
}

/**
 * Firmware builds on which the `agui_common` transaction codes and the `-1` timeout behaviour were
 * verified, both from the vendor binaries and on hardware. Any other build fails closed.
 */
internal object UnihertzBacklightFirmware {
    val verifiedFingerprints: Set<String> = setOf(
        // Titan 2 Elite (EEA), V02.00.02. Verified 2026-11-26: the timeout -1 kept the keyboard
        // light on beyond 40 s, switched it off with the display and back on with the display.
        "Unihertz/Titan_2_EEA/Titan_2:16/BP2A.250605.031.A3/V02.00.02:user/release-keys"
    )

    fun isVerified(fingerprint: String): Boolean = fingerprint in verifiedFingerprints
}
