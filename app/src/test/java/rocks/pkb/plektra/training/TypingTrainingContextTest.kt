package rocks.pkb.plektra.training

import org.junit.Assert.assertEquals
import org.junit.Test

class TypingTrainingContextTest {

    @Test
    fun sharedSettingsLeaveOutPersonalContentAndUnknownKeys() {
        val settings = TypingTrainingContext.shareableSettings(
            mapOf(
                "keyboard_layout" to "qwertz",
                "snippets_enabled" to true,
                "snippets_v1" to """[{"trigger":"addr","text":"Hauptstraße 1"}]""",
                "user_dictionary_entries" to "Zauner",
                "launcher_shortcuts" to "com.example.bank",
                "app_raw_mode_packages" to setOf("com.example.chat"),
                "auto_correct_custom_de" to """{"teh":"the"}""",
                "clicks_red_button_binding_output" to "private text",
                "some_future_key" to "anything"
            )
        )

        assertEquals(mapOf("keyboard_layout" to "qwertz", "snippets_enabled" to "true"), settings)
    }
}
