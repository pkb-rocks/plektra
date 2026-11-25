package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClicksButtonBindingCatalogTest {
    @Test
    fun desiredSelectionUsesStableChoiceId() {
        val choices = choices()
        val desired = ClicksDesiredButtonBinding("emoji", byteArrayOf(0x00, 0x44))

        assertEquals("emoji", ClicksButtonBindingCatalog.desiredSelection(desired, choices)?.id)
    }

    @Test
    fun directSelectionRejectsNonDirectModes() {
        val choices = choices()

        assertEquals(
            "emoji",
            ClicksButtonBindingCatalog.directActionSelection(
                SettingsManager.ClicksPowerButtonMode.TOGGLE_EMOJI_PICKER,
                choices
            )?.id
        )
        assertNull(
            ClicksButtonBindingCatalog.directActionSelection(
                SettingsManager.ClicksPowerButtonMode.NATIVE,
                choices
            )
        )
    }

    @Test
    fun directSelectionRequiresReadFirmwareOutputToMatch() {
        val mode = SettingsManager.ClicksPowerButtonMode.TOGGLE_EMOJI_PICKER

        assertEquals(
            "emoji",
            ClicksButtonBindingCatalog.directActionSelection(mode, choices(), byteArrayOf(0x00, 0x44))?.id
        )
        assertNull(
            ClicksButtonBindingCatalog.directActionSelection(
                mode,
                choices(),
                ClicksPowerKeyboardProtocol.leftAltRemapOutput()
            )
        )
    }

    @Test
    fun firmwareSelectionUsesByteContent() {
        assertEquals(
            "native",
            ClicksButtonBindingCatalog.firmwareSelection(byteArrayOf(0x00, 0x00), choices())?.id
        )
    }

    private fun choices() = listOf(
        ClicksButtonBindingChoice(
            id = "native",
            label = "Native",
            description = "Native",
            softwareMode = SettingsManager.ClicksPowerButtonMode.NATIVE,
            firmwareOutput = byteArrayOf(0x00, 0x00)
        ),
        ClicksButtonBindingChoice(
            id = "emoji",
            label = "Emoji",
            description = "Emoji",
            softwareMode = SettingsManager.ClicksPowerButtonMode.TOGGLE_EMOJI_PICKER,
            firmwareOutput = byteArrayOf(0x00, 0x44)
        )
    )
}
