package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Titan2EliteToolsSettingEntriesTest {

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
}
