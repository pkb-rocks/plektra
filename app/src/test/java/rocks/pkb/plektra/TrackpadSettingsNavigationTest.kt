package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Navigation decisions for Advanced > Trackpad Gesture Suggestions and its Screen trackpad
 * child page (TrackpadSettingsNavigation.kt, SettingsActivityNavigation.kt).
 */
class TrackpadSettingsNavigationTest {

    @Test
    fun advancedRowAlwaysOpensTheGestureSettings() {
        // Advanced > Trackpad Gesture Suggestions: the child extra names the page, no link.
        assertEquals(
            ADVANCED_DESTINATION_TRACKPAD_GESTURES,
            advancedDestinationNameFor(ADVANCED_DESTINATION_TRACKPAD_GESTURES, null)
        )
        assertEquals(TrackpadGesturePage.Gestures, trackpadGesturePageFor(null, null))
    }

    @Test
    fun screenTrackpadPageOpensOnlyFromItsRowOrALinkToOneOfItsRows() {
        assertEquals(TrackpadGesturePage.ScreenTrackpad, trackpadGesturePageFor(TRACKPAD_CHILD_PAGE_SCREEN, null))
        for (id in SCREEN_TRACKPAD_PAGE_LINK_IDS) {
            assertEquals(id, TrackpadGesturePage.ScreenTrackpad, trackpadGesturePageFor(null, id))
        }
        // The "Screen trackpad" row's own link highlights the row on the gesture page.
        assertEquals(TrackpadGesturePage.Gestures, trackpadGesturePageFor(null, SettingLinkIds.TRACKPAD_SCREEN_TRACKPAD))
        assertEquals(TrackpadGesturePage.Gestures, trackpadGesturePageFor(null, SettingLinkIds.TRACKPAD_GESTURES_ENABLED))
        assertEquals(TrackpadGesturePage.Gestures, trackpadGesturePageFor("bogus", null))
        assertEquals(TrackpadGesturePage.Sensitivity, trackpadGesturePageFor(TRACKPAD_CHILD_PAGE_SENSITIVITY, null))
        assertEquals(TrackpadGesturePage.Sensitivity, trackpadGesturePageFor(null, SettingLinkIds.TRACKPAD_SUGGESTION_SWIPE_THRESHOLD))
    }

    @Test
    fun everyLinkUnderTheGesturePageResolvesToTheGesturesDestination() {
        for (id in TRACKPAD_SETTING_LINK_IDS) {
            assertEquals(id, ADVANCED_DESTINATION_TRACKPAD_GESTURES, advancedDestinationNameFor(null, id))
        }
        assertEquals(ADVANCED_DESTINATION_MAIN, advancedDestinationNameFor(null, SettingLinkIds.ADVANCED_TRACKPAD_GESTURES))
        assertEquals(ADVANCED_DESTINATION_MAIN, advancedDestinationNameFor(null, null))
        assertEquals(ADVANCED_DESTINATION_IME_TEST, advancedDestinationNameFor(ADVANCED_DESTINATION_IME_TEST, SettingLinkIds.SCREEN_TRACKPAD_ENABLED))
    }

    @Test
    fun openingAnAdvancedPageDropsTheStaleTrackpadChildPage() {
        assertEquals(setOf(TRACKPAD_CHILD_SECTION), staleSettingsChildSections(ADVANCED_CHILD_SECTION))
        assertEquals(emptySet<String>(), staleSettingsChildSections(TRACKPAD_CHILD_SECTION))
        assertEquals("settings.child.trackpad", settingsChildExtraKey(TRACKPAD_CHILD_SECTION))
    }

    /**
     * The sequence seen on the phone: a link to the "Screen trackpad" row, then its row, then
     * the Advanced row. Models what each activity's intent carries after the fix.
     */
    @Test
    fun linkedGesturePageChildrenKeepTheGesturesDestinationAndDropStaleChildPages() {
        // Activity 2: opened by the link; no child extras yet.
        val linked = advancedDestinationNameFor(null, SettingLinkIds.TRACKPAD_SCREEN_TRACKPAD)
        assertEquals(ADVANCED_DESTINATION_TRACKPAD_GESTURES, linked)
        // AdvancedSettingsScreen writes it back as the child extra; the row opens the Screen
        // trackpad child, whose copied intent has no link data but inherits that extra.
        val childExtras = mutableMapOf(settingsChildExtraKey(ADVANCED_CHILD_SECTION) to linked)
        childExtras[settingsChildExtraKey(TRACKPAD_CHILD_SECTION)] = TRACKPAD_CHILD_PAGE_SCREEN
        assertEquals(
            ADVANCED_DESTINATION_TRACKPAD_GESTURES,
            advancedDestinationNameFor(childExtras[settingsChildExtraKey(ADVANCED_CHILD_SECTION)], null)
        )
        assertEquals(
            TrackpadGesturePage.ScreenTrackpad,
            trackpadGesturePageFor(childExtras[settingsChildExtraKey(TRACKPAD_CHILD_SECTION)], null)
        )
        // Any later Advanced page opened from there starts without the trackpad child page.
        staleSettingsChildSections(ADVANCED_CHILD_SECTION).forEach { childExtras.remove(settingsChildExtraKey(it)) }
        childExtras[settingsChildExtraKey(ADVANCED_CHILD_SECTION)] = ADVANCED_DESTINATION_TRACKPAD_GESTURES
        assertEquals(
            TrackpadGesturePage.Gestures,
            trackpadGesturePageFor(childExtras[settingsChildExtraKey(TRACKPAD_CHILD_SECTION)], null)
        )
    }
}
