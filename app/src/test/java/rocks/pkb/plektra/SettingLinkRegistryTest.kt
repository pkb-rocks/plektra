package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingLinkRegistryTest {

    @Test
    fun idsAreUnique() {
        val ids = SettingLinkRegistry.entries.map { it.id }
        assertEquals("setting link ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun idsFollowStableNamingScheme() {
        val pattern = Regex("^[a-z0-9_]+(\\.[a-z0-9_]+)+$")
        SettingLinkRegistry.entries.forEach { entry ->
            assertTrue("id '${entry.id}' violates the naming scheme", pattern.matches(entry.id))
        }
    }

    @Test
    fun everyRouteDestinationHasBreadcrumbTitle() {
        SettingLinkRegistry.entries.forEach { entry ->
            assertTrue(
                "destination ${entry.route.destination} of '${entry.id}' lacks a breadcrumb title",
                SettingLinkRegistry.destinationTitles.containsKey(entry.route.destination)
            )
        }
    }

    @Test
    fun everyCustomizationSubRouteHasBreadcrumbSubtitle() {
        SettingLinkRegistry.entries
            .mapNotNull { it.route.customizationDestination }
            .forEach { sub ->
                assertTrue(
                    "customization destination '$sub' lacks a breadcrumb subtitle",
                    SettingLinkRegistry.customizationSubtitles.containsKey(sub)
                )
            }
    }

    @Test
    fun mainScreenCustomizationPagesAndTheirChildrenHaveReachableBreadcrumbs() {
        val paths = mapOf(
            "status_bar_buttons" to listOf(R.string.status_bar_buttons_title),
            "keyboard_theme" to listOf(R.string.keyboard_theme_title),
            "display_geometry" to listOf(R.string.keyboard_bar_geometry),
            "launcher_shortcuts" to listOf(R.string.starter_launcher_shortcuts_title),
            "app_enter_behavior" to listOf(R.string.app_enter_behaviour_title),
            "keyboard_bar_buttons" to listOf(R.string.status_bar_buttons_title, R.string.status_bar_buttons_section),
            "keyboard_bar_variations" to listOf(R.string.status_bar_buttons_title, R.string.keyboard_bar_variations_title),
            "keyboard_theme_assignment" to listOf(R.string.keyboard_theme_title, R.string.setting_link_theme_assignment),
            "launcher_shortcut_behavior" to listOf(R.string.starter_launcher_shortcuts_title, R.string.quick_launcher_behaviour_title),
            "launcher_shortcut_cosmetic" to listOf(R.string.starter_launcher_shortcuts_title, R.string.quick_launcher_cosmetic_title),
            "launcher_shortcut_assignments" to listOf(R.string.starter_launcher_shortcuts_title, R.string.launcher_shortcuts_screen_title)
        )
        paths.forEach { (page, path) ->
            val entries = SettingLinkRegistry.entries.filter { it.route.customizationDestination == page }
            assertTrue("No registry entries for $page", entries.isNotEmpty())
            entries.forEach { entry ->
                val breadcrumb = SettingLinkRegistry.breadcrumbTitles(entry.route)
                assertTrue("${entry.id} incorrectly names More Customizations",
                    R.string.settings_category_customization !in breadcrumb)
                assertEquals(entry.id, listOf(R.string.settings_title) + path, breadcrumb)
            }
        }
    }

    @Test
    fun moreCustomizationPagesKeepTheirParentInBreadcrumbs() {
        for (page in listOf(null, "app_icon", "main_background", "sounds", "variations")) {
            val entries = SettingLinkRegistry.entries.filter {
                it.route.destination == SettingsDestination.Customization &&
                    it.route.customizationDestination == page
            }
            assertTrue("No registry entries for $page", entries.isNotEmpty())
            entries.forEach { entry ->
                assertEquals(entry.id, listOfNotNull(
                    R.string.settings_title,
                    R.string.settings_category_customization,
                    page?.let(SettingLinkRegistry.customizationSubtitles::get)
                ), SettingLinkRegistry.breadcrumbTitles(entry.route))
            }
        }
    }

    @Test
    fun everyCustomizationPageIsEitherReachableFromMainOrUnderMoreCustomizations() {
        val moreCustomizationPages = setOf("variations", "sounds", "app_icon", "main_background")
        SettingLinkRegistry.entries
            .filter { it.route.destination == SettingsDestination.Customization }
            .forEach { entry ->
                val page = entry.route.customizationDestination ?: return@forEach
                val underMoreCustomizations = R.string.settings_category_customization in
                    SettingLinkRegistry.breadcrumbTitles(entry.route)
                assertEquals(
                    "Customization page '$page' of '${entry.id}' needs a breadcrumb parent or " +
                        "an entry in the More Customizations set",
                    page in moreCustomizationPages,
                    underMoreCustomizations
                )
            }
    }

    @Test
    fun breadcrumbParentsAreCustomizationPages() {
        SettingLinkRegistry.customizationBreadcrumbParents.values.filterNotNull().forEach { parent ->
            assertTrue("breadcrumb parent '$parent' is not a customization page",
                SettingLinkRegistry.customizationSubtitles.containsKey(parent))
        }
    }

    @Test
    fun otherSettingsBreadcrumbsKeepTheirScreenAndSubscreen() {
        val entry = requireNotNull(SettingLinkRegistry.byId("on_screen.number_row"))
        assertEquals(listOf(R.string.settings_title, R.string.keyboards_devices_title,
            R.string.on_screen_keyboard_title), SettingLinkRegistry.breadcrumbTitles(entry.route))
    }

    @Test
    fun buildLinkAndParseLinkRoundTrip() {
        SettingLinkRegistry.entries.forEach { entry ->
            assertEquals("https://pkb.rocks/settings/${entry.id}", SettingLinkRegistry.buildLink(entry.id))
            for (host in listOf("pkb.rocks", "pkb.rocks")) {
                assertEquals(
                    entry.id,
                    SettingLinkRegistry.parseSettingLink("https", host, "/settings/${entry.id}")
                )
            }
            assertEquals(
                entry.id,
                SettingLinkRegistry.parseSettingLink("plektra", "setting", "/${entry.id}")
            )
        }
    }

    @Test
    fun parseLinkRejectsForeignSchemeHostAndEmptyPath() {
        assertNull(SettingLinkRegistry.parseSettingLink("https", "setting", "/text_input.auto_capitalize"))
        assertNull(SettingLinkRegistry.parseSettingLink("plektra", "settings", "/text_input.auto_capitalize"))
        assertNull(SettingLinkRegistry.parseSettingLink("plektra", "setting", "/"))
        assertNull(SettingLinkRegistry.parseSettingLink("plektra", "setting", null))
    }

    @Test
    fun parseLinkAcceptsPathWithoutLeadingSlashAndTrimsWhitespace() {
        assertEquals(
            "text_input.auto_capitalize",
            SettingLinkRegistry.parseSettingLink("plektra", "setting", " /text_input.auto_capitalize ")
        )
    }

    @Test
    fun webLinksRejectOtherOriginsAndMalformedPaths() {
        val id = "text_input.auto_capitalize"
        for (host in listOf("www.pkb.rocks", "pkb.rocks.example.org", "example.org")) {
            assertNull(SettingLinkRegistry.parseSettingLink("https", host, "/settings/$id"))
        }
        for (host in listOf("pkb.rocks", "pkb.rocks")) {
            assertNull(SettingLinkRegistry.parseSettingLink("http", host, "/settings/$id"))
            for (path in listOf("/", "/settings/", "/setting/$id", "/settings/$id/extra",
                "/settings/$id/", "/settings/../$id", "/settings/ $id", "/settingsx/$id")) {
                assertNull(SettingLinkRegistry.parseSettingLink("https", host, path))
            }
        }
    }

    @Test
    fun unknownIdResolvesToNull() {
        assertNull(SettingLinkRegistry.byId("no.such_entry"))
    }

    @Test
    fun buildSettingMarkdownUsesLabelWithDescriptionAndLinkWithout() {
        val link = "plektra://setting/text_input.auto_capitalize"
        assertEquals(
            "[Auto capitalize]($link)",
            SettingLinkRegistry.buildSettingMarkdown("Auto capitalize", link, withDescription = true)
        )
        assertEquals(
            "[$link]($link)",
            SettingLinkRegistry.buildSettingMarkdown("Auto capitalize", link, withDescription = false)
        )
    }

    @Test
    fun buildSettingMarkdownEscapesBracketsInLabel() {
        val link = "plektra://setting/main.about"
        assertEquals(
            "[\\[Advanced\\] stuff]($link)",
            SettingLinkRegistry.buildSettingMarkdown("[Advanced] stuff", link, withDescription = true)
        )
    }

    @Test
    fun scoreMatchPrefersTitlePrefixOverWordPrefixOverSubstringOverSummary() {
        val title = "Auto capitalize"
        val summary = "Capitalize the first letter of a sentence"

        assertEquals(6, SettingLinkRegistry.scoreMatch(title, summary, "auto") ?: -1)
        assertEquals(4, SettingLinkRegistry.scoreMatch(title, summary, "cap") ?: -1)
        assertEquals(2, SettingLinkRegistry.scoreMatch(title, summary, "pitaliz") ?: -1)
        assertEquals(1, SettingLinkRegistry.scoreMatch(title, summary, "sentence") ?: -1)
        assertEquals(0, SettingLinkRegistry.scoreMatch(title, summary, "entenc") ?: -1)
        assertNull(SettingLinkRegistry.scoreMatch(title, summary, "zzz"))
        assertNull(SettingLinkRegistry.scoreMatch(title, summary, ""))
    }

    @Test
    fun scoreMatchSumsTokensAndRequiresEveryTokenToMatch() {
        val title = "Auto capitalize"
        val summary = "Capitalize the first letter of a sentence"

        assertEquals(10, SettingLinkRegistry.scoreMatch(title, summary, "auto capitalize") ?: -1)
        assertEquals(7, SettingLinkRegistry.scoreMatch(title, summary, "auto sentence") ?: -1)
        assertNull(SettingLinkRegistry.scoreMatch(title, summary, "auto zzz"))
    }

    @Test
    fun scoreMatchFoldsDiacriticsCaseAndSharpS() {
        assertEquals(
            SettingLinkRegistry.scoreMatch("Grosse", null, "grosse"),
            SettingLinkRegistry.scoreMatch("Größe", null, "grosse")
        )
        assertEquals(6, SettingLinkRegistry.scoreMatch("Éclairage", null, "eclairage") ?: -1)
    }

    @Test
    fun scoreMatchConsidersKeywordsLikeSummary() {
        val title = "Menubar"
        val keywords = "LED, lights, icon"

        assertEquals(1, SettingLinkRegistry.scoreMatch(title, null, "led", keywords = keywords) ?: -1)
        assertEquals(0, SettingLinkRegistry.scoreMatch(title, null, "igh", keywords = keywords) ?: -1)
        assertNull(SettingLinkRegistry.scoreMatch(title, null, "led"))
    }

    @Test
    fun everyKeywordAssignmentTargetsARegisteredEntry() {
        SettingLinkRegistry.keywordsById.keys.forEach { id ->
            assertTrue(
                "keyword assignment targets unknown entry id '$id'",
                SettingLinkRegistry.byId(id) != null
            )
        }
    }

    @Test
    fun conditionalEntriesHaveRegisteredVisibleFallbacks() {
        SettingLinkRegistry.entries
            .filter { it.availability != SettingAvailability.Always }
            .forEach { entry ->
                val fallbackId = entry.unavailableFallbackId
                assertTrue("conditional entry '${entry.id}' needs a fallback", fallbackId != null)
                assertTrue(
                    "fallback '$fallbackId' of '${entry.id}' is not registered",
                    fallbackId?.let(SettingLinkRegistry::byId) != null
                )
            }
    }

    @Test
    fun trackpadDetailLinksRouteThroughAdvancedSettings() {
        val trackpadIds = setOf(
            SettingLinkIds.TRACKPAD_GESTURES_ENABLED,
            SettingLinkIds.TRACKPAD_PROVIDER,
            SettingLinkIds.TRACKPAD_SHIZUKU_DEVICE,
            SettingLinkIds.TRACKPAD_SENSITIVITY,
            SettingLinkIds.TRACKPAD_SUGGESTION_SWIPE_THRESHOLD,
            SettingLinkIds.TRACKPAD_DELETE_SWIPE_THRESHOLD,
            SettingLinkIds.TRACKPAD_DEBUG
        )

        trackpadIds.forEach { id ->
            assertEquals(
                SettingsDestination.Advanced,
                requireNotNull(SettingLinkRegistry.byId(id)).route.destination
            )
        }
    }

    @Test
    fun softwareThemeTogglesRouteToKeysTab() {
        val toggleIds = setOf(
            SettingLinkIds.KEYBOARD_THEME_TOGGLE_DISTRIBUTE_SPACING,
            SettingLinkIds.KEYBOARD_THEME_TOGGLE_ORTHOLINEAR,
            SettingLinkIds.KEYBOARD_THEME_TOGGLE_ATTACH_POPUP,
            SettingLinkIds.KEYBOARD_THEME_TOGGLE_POPUP_TAIL,
            SettingLinkIds.KEYBOARD_THEME_TOGGLE_PREVIEW_ON_HOLD,
            SettingLinkIds.KEYBOARD_THEME_TOGGLE_CHARACTER_PICKER
        )

        toggleIds.forEach { id ->
            val route = requireNotNull(SettingLinkRegistry.byId(id)).route
            assertEquals(SettingsManager.KeyboardThemeTarget.SOFTWARE, route.keyboardThemeTarget)
            assertEquals(KeyboardThemeEditorTab.Keys, route.keyboardThemeTab)
        }
    }

    @Test
    fun ledVisibilityRoutesToTheKeyboardBar() {
        val route = requireNotNull(SettingLinkRegistry.byId(SettingLinkIds.KEYBOARD_THEME_TOGGLE_SHOW_LEDS)).route
        assertEquals(SettingsActivity.CUSTOMIZATION_DESTINATION_STATUS_BAR_BUTTONS, route.customizationDestination)
        assertNull(route.keyboardThemeTarget)
    }

    @Test
    fun ledColorsRouteToColorsTab() {
        val route = requireNotNull(
            SettingLinkRegistry.byId(SettingLinkIds.KEYBOARD_THEME_LED_COLORS)
        ).route

        assertEquals(KeyboardThemeEditorTab.Colors, route.keyboardThemeTab)
    }
}
