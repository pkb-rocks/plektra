package rocks.pkb.plektra

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class InputDeviceSettingLinkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun hiddenPopupPositionFallsBackToItsEnablingControl() {
        val position = requireNotNull(SettingLinkRegistry.byId("on_screen.layer_popup_below"))
        SettingsManager.setSoftwareKeyboardLongPressLayerPopupEnabled(context, false)

        assertFalse(position.isAvailable(context))
        assertFalse(SettingLinkRegistry.search(context, "on screen layer popup below").any { it.id == position.id })
        val fallback = SettingLinkRegistry.visibleTarget(context, position)
        assertEquals("on_screen.layer_popup", fallback.id)
        assertEquals(SettingsDestination.KeyboardsDevices, fallback.route.destination)
        assertEquals(KeyboardsDevicesDestination.OnScreen, fallback.route.keyboardsDevicesDestination)

        SettingsManager.setSoftwareKeyboardLongPressLayerPopupEnabled(context, true)
        assertTrue(position.isAvailable(context))
        assertEquals(position.id, SettingLinkRegistry.visibleTarget(context, position).id)
        assertTrue(SettingLinkRegistry.search(context, "on screen layer popup below").any { it.id == position.id })
    }

    @Test
    fun keyHintsSwitchIsFoundByTitleAndKeywordAndOpensTheOnScreenPage() {
        assertTrue(SettingsManager.getSoftwareKeyboardKeyHintsEnabled(context))
        val entry = requireNotNull(SettingLinkRegistry.byId("on_screen.key_hints"))
        listOf(context.getString(R.string.software_keyboard_key_hints_title), "corner labels", "long press SYM").forEach { query ->
            assertTrue("Search '$query' misses the key hints switch",
                SettingLinkRegistry.search(context, query).any { it.id == entry.id })
        }
        val german = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.GERMAN)
        })
        assertTrue(SettingLinkRegistry.search(german, "Zusatzbeschriftungen").any { it.id == entry.id })
        assertEquals(entry.id, SettingLinkRegistry.visibleTarget(context, entry).id)
        val page = entry.route.toSettingsPage()
        assertEquals(SettingsDestination.KeyboardsDevices, page.destination)
        assertEquals(KeyboardsDevicesDestination.OnScreen, page.keyboardsDevicesDestination)
    }

    @Test
    fun repeatedActivationLabelsAreDisambiguatedByTheirSection() {
        val snippets = SettingLinkRegistry.search(context, "snippets tab").map { it.id }
        val emoji = SettingLinkRegistry.search(context, "emoji tab").map { it.id }

        assertTrue(snippets.contains("text_expansion.snippets.tab"))
        assertFalse(snippets.contains("text_expansion.emoji_symbols.tab"))
        assertTrue(emoji.contains("text_expansion.emoji_symbols.tab"))
        assertFalse(emoji.contains("text_expansion.snippets.tab"))
        listOf("text_expansion.snippets.tab", "text_expansion.emoji_symbols.tab").forEach { id ->
            assertEquals(SettingsDestination.TextInput, requireNotNull(SettingLinkRegistry.byId(id)).route.destination)
        }
    }

    @Test
    fun entriesResolveToReadableLabelsAndExpectedDeviceRoutes() {
        val unexpandedFormat = Regex("%(?:\\d+\\$)?[sdif]")
        val entries = inputDeviceSettingEntries()
        for (language in listOf("en", "de")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = context.createConfigurationContext(configuration)
            entries.forEach { entry ->
                assertNotNull("Missing registry entry ${entry.id}", SettingLinkRegistry.byId(entry.id))
                listOfNotNull(entry.titleRes, entry.summaryRes).forEach { resource ->
                    val label = localized.getString(resource)
                    assertTrue("Empty label for ${entry.id} ($language)", label.isNotBlank())
                    assertFalse("Unformatted label for ${entry.id}: $label", unexpandedFormat.containsMatchIn(label))
                }
                if (entry.id.startsWith("sym.")) {
                    assertTrue("SYM entry needs its activity: ${entry.id}", entry.route.symCustomization)
                }
            }
        }
        assertEquals(KeyboardsDevicesDestination.BuiltIn, requireNotNull(SettingLinkRegistry.byId("hardware.profile")).route.keyboardsDevicesDestination)
        assertEquals(KeyboardsDevicesDestination.PowerKeyboard, requireNotNull(SettingLinkRegistry.byId("clicks.buttons.red_button")).route.keyboardsDevicesDestination)
    }
}
