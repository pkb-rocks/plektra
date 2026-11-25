package rocks.pkb.plektra.backlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnihertzBacklightVendorCommandTest {

    @Test
    fun commandsUseOnlyTheVerifiedTransactionsAndKey() {
        assertEquals(
            listOf("service", "call", "agui_common", "3", "s16", "keyboard_brightness_timeout"),
            UnihertzBacklightVendorCommand.ReadTimeout.argv()
        )
        assertEquals(
            listOf("service", "call", "agui_common", "4", "s16", "keyboard_brightness_timeout", "s16", "-1"),
            UnihertzBacklightVendorCommand.WriteTimeout.followDisplay().argv()
        )
        assertEquals(
            listOf("service", "call", "agui_common", "4", "s16", "keyboard_brightness_timeout", "s16", "30000"),
            UnihertzBacklightVendorCommand.WriteTimeout.restore("30000")?.argv()
        )
    }

    @Test
    fun restoreAcceptsOnlyPlainMillisecondTimeouts() {
        listOf("0", "3000", "5000", "10000", "15000", "30000", "86400000").forEach {
            assertTrue(it, UnihertzBacklightVendorCommand.isRestorableTimeout(it))
        }
        listOf("-1", "", " 30000", "030000", "30000 ", "3e4", "86400001", "30000;reboot", "+5", "1\n2")
            .forEach {
                assertFalse(it, UnihertzBacklightVendorCommand.isRestorableTimeout(it))
                assertNull(UnihertzBacklightVendorCommand.WriteTimeout.restore(it))
            }
    }

    @Test
    fun serviceListMustShowTheVerifiedInterface() {
        val fromDevice = """
            0	AService: [com.agui.server.IAService]
            13	agui_common: [com.agui.ext.common.IACommonService]
            15	agui_functional_service: [com.agui.server.functional.IAguiFunctional]
        """.trimIndent()
        assertTrue(UnihertzBacklightVendorCommand.serviceListShowsVerifiedInterface(fromDevice))

        assertFalse(UnihertzBacklightVendorCommand.serviceListShowsVerifiedInterface("13\tagui_common: []"))
        assertFalse(
            UnihertzBacklightVendorCommand.serviceListShowsVerifiedInterface(
                "13\tagui_common: [com.agui.ext.common.IACommonService2]"
            )
        )
        assertFalse(
            UnihertzBacklightVendorCommand.serviceListShowsVerifiedInterface(
                "13\tagui_common_x: [com.agui.ext.common.IACommonService]"
            )
        )
    }

    @Test
    fun onlyTheVerifiedFirmwareBuildIsAccepted() {
        assertTrue(
            UnihertzBacklightFirmware.isVerified(
                "Unihertz/Titan_2_EEA/Titan_2:16/BP2A.250605.031.A3/V02.00.02:user/release-keys"
            )
        )
        assertFalse(
            UnihertzBacklightFirmware.isVerified(
                "Unihertz/Titan_2_EEA/Titan_2:16/BP2A.250605.031.A3/V02.00.03:user/release-keys"
            )
        )
        assertFalse(
            UnihertzBacklightFirmware.isVerified(
                "Unihertz/Titan_2/Titan_2:16/BP2A.250605.031.A3/V02.00.02:user/release-keys"
            )
        )
        assertFalse(UnihertzBacklightFirmware.isVerified(""))
    }
}
