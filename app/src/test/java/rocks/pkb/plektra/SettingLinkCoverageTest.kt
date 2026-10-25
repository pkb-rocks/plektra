package rocks.pkb.plektra

import android.content.Context
import android.content.res.Configuration
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingLinkCoverageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetSettings() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun auditedSystemControlsAreDiscoverableUsingTheirDisplayedTitle() {
        SettingsManager.setNavModeEnabled(context, true)
        val controls = mapOf(
            "nav_mode.enabled" to R.string.nav_mode_enable_title,
            "nav_mode.ctrl_hold" to R.string.nav_mode_ctrl_hold_title,
            "nav_mode.layout_aware_ctrl_shortcuts" to R.string.layout_aware_ctrl_shortcuts_title,
            "accessibility.bounce_keys_delay" to R.string.settings_accessibility_bounce_keys_delay_link_title,
            "trackpad.add_word" to R.string.trackpad_gesture_add_word_title,
            "trackpad.add_word_full_width" to R.string.trackpad_gesture_add_word_full_width_title,
            "trackpad.swipe_to_delete" to R.string.swipe_to_delete_title,
            "trackpad.swipe_to_delete_provider" to R.string.swipe_to_delete_provider_title
        )
        controls.forEach { (id, titleRes) ->
            val results = SettingLinkRegistry.search(context, context.getString(titleRes))
            assertTrue("Search does not expose $id", results.any { it.id == id })
        }
    }

    @Test
    fun hiddenNavControlsResolveToVisibleEnableSwitchAndReturnWhenEnabled() {
        val ids = listOf("nav_mode.ctrl_hold", "nav_mode.layout_aware_ctrl_shortcuts")
        SettingsManager.setNavModeEnabled(context, false)
        ids.forEach { id ->
            val entry = requireNotNull(SettingLinkRegistry.byId(id))
            assertFalse(entry.isAvailable(context))
            assertEquals("nav_mode.enabled", SettingLinkRegistry.visibleTarget(context, entry).id)
            assertFalse(SettingLinkRegistry.search(context, context.getString(entry.titleRes)).any { it.id == id })
        }
        SettingsManager.setNavModeEnabled(context, true)
        ids.forEach { id ->
            val entry = requireNotNull(SettingLinkRegistry.byId(id))
            assertTrue(entry.isAvailable(context))
            assertEquals(id, SettingLinkRegistry.visibleTarget(context, entry).id)
        }
    }

    @Test
    fun disabledButVisibleLayoutShortcutStillResolvesToItsOwnRow() {
        SettingsManager.setNavModeEnabled(context, true)
        SettingsManager.setNavModeCtrlHoldEnabled(context, true)
        val entry = requireNotNull(SettingLinkRegistry.byId("nav_mode.layout_aware_ctrl_shortcuts"))
        assertTrue(entry.isAvailable(context))
        assertEquals(entry.id, SettingLinkRegistry.visibleTarget(context, entry).id)
    }

    @Test
    fun registeredDeviceControlsRetainTheirActualSubscreenInNavigationStack() {
        val expectedTargets = mapOf(
            "on_screen.number_row" to KeyboardsDevicesDestination.OnScreen,
            "hardware.currency" to KeyboardsDevicesDestination.BuiltIn,
            "clicks.backlight" to KeyboardsDevicesDestination.PowerKeyboard
        )
        expectedTargets.forEach { (id, expected) ->
            val route = requireNotNull(SettingLinkRegistry.byId(id)).route
            val stackEntry = route.toSettingsPage()
            assertEquals(SettingsDestination.KeyboardsDevices, stackEntry.destination)
            assertEquals("Incorrect subpage for $id", expected, stackEntry.keyboardsDevicesDestination)
        }
        assertEquals(
            KeyboardsDevicesDestination.Main,
            requireNotNull(SettingLinkRegistry.byId(SettingLinkIds.MAIN_KEYBOARDS_DEVICES))
                .route.toSettingsPage().keyboardsDevicesDestination
        )
    }

    @Test
    fun appLanguageLinkResolvesToLanguageScreen() {
        val entry = requireNotNull(SettingLinkRegistry.byId(SettingLinkIds.MAIN_APP_LANGUAGE))
        assertEquals(SettingsDestination.AppLanguage, entry.route.toSettingsPage().destination)
    }

    @Test
    fun supplementalLiteralEntriesHaveUiReferences() {
        val sources = mainSources()
        val registrySources = sources.filter { it.name.endsWith("SettingEntries.kt") }
        assertTrue("Supplemental registries were not found", registrySources.isNotEmpty())
        val entryPattern = Regex("""SettingEntry\(\s*(?:id\s*=\s*)?"([a-z0-9_]+(?:\.[a-z0-9_]+)+)"""")
        val ids = registrySources.flatMap { source ->
            entryPattern.findAll(source.readText()).map { it.groupValues[1] }.toList()
        }
        assertTrue("No literal supplemental entries were found", ids.isNotEmpty())
        val uiText = sources.filterNot { it in registrySources || it.name == "SettingLinks.kt" }
            .joinToString("\n") { it.readText() }
        ids.forEach { id ->
            assertTrue("Supplemental entry $id has no literal UI reference", uiText.contains("\"$id\""))
        }
    }

    @Test
    fun literalUiHooksResolveInRegistry() {
        // Only complete static literals match; interpolated per-item IDs are deliberately excluded.
        val hookPattern = Regex("""(?:\.settingRow\(\s*|\blinkId\s*=\s*)"([a-z0-9_]+(?:\.[a-z0-9_]+)+)"""")
        val hooks = mainSources().flatMap { source ->
            hookPattern.findAll(source.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue("No literal UI hooks were found", hooks.isNotEmpty())
        hooks.forEach { id ->
            if (!BuildConfig.ENABLE_ACCESSIBILITY_SERVICE && id == "clicks.buttons.launcher_button") {
                assertEquals(null, SettingLinkRegistry.byId(id))
            } else {
                assertTrue("UI hook $id has no registered entry", SettingLinkRegistry.byId(id) != null)
            }
        }
    }

    private fun mainSources(): List<File> {
        val workingDirectory = File(System.getProperty("user.dir"))
        val sourceDirectory = listOf(
            File(workingDirectory, "src/main/java/rocks/pkb/plektra"),
            File(workingDirectory, "app/src/main/java/rocks/pkb/plektra")
        ).firstOrNull { it.isDirectory }
        requireNotNull(sourceDirectory) { "Main sources not found relative to $workingDirectory" }
        return sourceDirectory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    @Config(qualifiers = "en")
    fun keyboardBarProfilesAreSearchableAndTargetTheProfileTabs() {
        for (mode in SettingsManager.StatusBarPresentationMode.values()) {
            SettingsManager.setStatusBarPresentationMode(context, mode)
            for (query in listOf("Slim", "normal", "profile", "minimal", "compact", "Slim profile")) {
                val entry = SettingLinkRegistry.search(context, query)
                    .single { it.id == "status_bar.presentation" }
                assertEquals(entry, SettingLinkRegistry.visibleTarget(context, entry))
                assertEquals(SettingsDestination.Customization, entry.route.destination)
                assertEquals("status_bar_buttons", entry.route.customizationDestination)
            }
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun searchingForSettingsDoesNotMatchEveryEntry() {
        val available = SettingLinkRegistry.entries.filter { it.isAvailable(context) }
        val matches = SettingLinkRegistry.search(context, "settings")
        assertTrue("'settings' matched all ${available.size} entries", matches.size < available.size)

        // The breadcrumb root is not a keyword, so a hit needs "settings" in the row's own words.
        fun mentionsSettings(entry: SettingEntry): Boolean = listOfNotNull(
            context.getString(entry.titleRes),
            entry.summaryRes?.let(context::getString),
            SettingLinkRegistry.keywordsById[entry.id]?.let(context::getString),
            SettingLinkRegistry.breadcrumbTitles(entry.route).drop(1).joinToString(" ", transform = context::getString),
            entry.id.replace('.', ' ').replace('_', ' ')
        ).any { it.contains("settings", ignoreCase = true) }
        matches.forEach { entry ->
            assertTrue("'${entry.id}' matched 'settings' without mentioning it",
                entry.route.destination == SettingsDestination.Main || mentionsSettings(entry))
        }
        val expected = available.filter {
            listOfNotNull(context.getString(it.titleRes), it.summaryRes?.let(context::getString))
                .any { text -> text.contains("settings", ignoreCase = true) }
        }
        assertTrue("no row mentions 'settings' in its title or summary", expected.isNotEmpty())
        assertTrue("rows mentioning 'settings' went missing: ${expected.map { it.id } - matches.map { it.id }.toSet()}",
            matches.map { it.id }.containsAll(expected.map { it.id }))
    }

    @Test
    fun keyboardBarProfileKeywordsAreLocalized() {
        val queries = mapOf("de" to "kompakt", "it" to "compatto", "el" to "συμπαγές")
        queries.forEach { (language, query) ->
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale(language))
            }
            val localizedContext = context.createConfigurationContext(configuration)
            assertTrue("Profile keyword missing for $language",
                SettingLinkRegistry.search(localizedContext, query).any { it.id == "status_bar.presentation" })
        }
    }

    @Test
    fun keyboardBarLinksOpenTheActualEditorsEvenWhenSlimIsActive() {
        SettingsManager.setStatusBarPresentationMode(context, SettingsManager.StatusBarPresentationMode.SLIM)
        SettingsManager.setStatusBarVariationsEnabled(context, false)
        val targets = mapOf(
            "status_bar.extended_left" to "keyboard_bar_buttons",
            "status_bar.extended_right" to "keyboard_bar_buttons",
            "status_bar.variation_slots" to "keyboard_bar_variations",
            "status_bar.resize_variations" to "keyboard_bar_variations",
            "status_bar.rounded_corners" to "display_geometry",
            "keyboard_bar.geometry" to "display_geometry"
        )
        targets.forEach { (id, destination) ->
            val entry = requireNotNull(SettingLinkRegistry.byId(id))
            assertTrue("Editor should remain accessible: $id", entry.isAvailable(context))
            val route = SettingLinkRegistry.visibleTarget(context, entry).route
            assertEquals(destination, route.toSettingsPage().customizationDestination)
        }
        listOf(SettingLinkIds.MODIFIERS_INDICATORS, SettingLinkIds.MODIFIERS_INDICATOR_BOTTOM_STRIP,
            SettingLinkIds.MODIFIERS_INDICATOR_MENU_BAR, SettingLinkIds.MODIFIERS_INDICATOR_STATUS_BAR).forEach { id ->
            assertEquals(SettingsDestination.ModifierIndicators,
                requireNotNull(SettingLinkRegistry.byId(id)).route.toSettingsPage().destination)
        }
    }

}
