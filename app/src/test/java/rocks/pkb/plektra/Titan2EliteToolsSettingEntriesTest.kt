package rocks.pkb.plektra

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class Titan2EliteToolsSettingEntriesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val entries = titan2EliteToolsSettingEntries()

    @Test
    fun everyEntryIsRegisteredAndRoutesToTheToolsScreen() {
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertNotNull("'${entry.id}' is not in the registry", SettingLinkRegistry.byId(entry.id))
            assertEquals(SettingsDestination.Titan2EliteTools, entry.route.destination)
        }
    }

    @Test
    fun everyEntryIsDeviceConditionalWithAnAlwaysVisibleFallback() {
        entries.forEach { entry ->
            assertNotNull("'${entry.id}' must only show on the Titan 2 Elite", entry.availabilityCheck)
            val fallback = SettingLinkRegistry.byId(requireNotNull(entry.unavailableFallbackId))
            assertNotNull(fallback)
            assertTrue(
                "fallback of '${entry.id}' must not itself be conditional",
                fallback!!.availabilityCheck == null &&
                    fallback.availability == SettingAvailability.Always
            )
        }
    }

    @Test
    fun toolsScreenHasABreadcrumbTitle() {
        assertTrue(SettingLinkRegistry.destinationTitles.containsKey(SettingsDestination.Titan2EliteTools))
    }

    @Test
    fun toolsScreenIsFoundBySearchingToolboxOrTools() {
        val entry = requireNotNull(SettingLinkRegistry.byId(SettingLinkIds.MAIN_TITAN2_ELITE_TOOLS))
        val title = context.getString(entry.titleRes)
        val summary = entry.summaryRes?.let(context::getString)
        val keywords = context.getString(
            requireNotNull(SettingLinkRegistry.keywordsById[entry.id]) { "no keywords for '${entry.id}'" }
        )
        for (query in listOf("toolbox", "tools", "tool box")) {
            assertNotNull(
                "'$query' does not find '$title'",
                SettingLinkRegistry.scoreMatch(title, summary, query, keywords)
            )
        }
        // The title and summary alone never said "toolbox": it is the keyword that makes the hit.
        assertEquals(null, SettingLinkRegistry.scoreMatch(title, summary, "toolbox"))
    }
}
