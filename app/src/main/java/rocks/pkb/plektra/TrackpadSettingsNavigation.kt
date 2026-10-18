package rocks.pkb.plektra

/**
 * Pure navigation decisions for Advanced > Trackpad Gesture Suggestions and its
 * Screen trackpad child page. Both screens derive their page from the hosting
 * activity's intent exactly once, through these functions, so the rules are unit
 * testable without Compose.
 */

/** Child section the gesture settings screen reads its page from. */
internal const val TRACKPAD_CHILD_SECTION = "trackpad"
internal const val TRACKPAD_CHILD_PAGE_SENSITIVITY = "sensitivity"
internal const val TRACKPAD_CHILD_PAGE_SCREEN = "screen"

/** Child section the Advanced screen reads its destination from. */
internal const val ADVANCED_CHILD_SECTION = "advanced"

/** Pages of the gesture settings screen. Only [ScreenTrackpad] replaces the whole screen. */
internal enum class TrackpadGesturePage { Gestures, Sensitivity, ScreenTrackpad }

/** Rows that live on the Screen trackpad page. A link to one of them opens that page. */
internal val SCREEN_TRACKPAD_PAGE_LINK_IDS: Set<String> = setOf(
    SettingLinkIds.SCREEN_TRACKPAD_ENABLED,
    SettingLinkIds.SCREEN_TRACKPAD_TRIGGER_KEY,
    SettingLinkIds.SCREEN_TRACKPAD_ACTIVATION,
    SettingLinkIds.SCREEN_TRACKPAD_SENSITIVITY,
    SettingLinkIds.SCREEN_TRACKPAD_SHOW_HINT,
    SettingLinkIds.SCREEN_TRACKPAD_TERMINAL_APPS
)

/** Rows on the sensitivity page of the gesture settings screen. */
internal val TRACKPAD_SENSITIVITY_PAGE_LINK_IDS: Set<String> = setOf(
    SettingLinkIds.TRACKPAD_SUGGESTION_SWIPE_THRESHOLD,
    SettingLinkIds.TRACKPAD_DELETE_SWIPE_THRESHOLD
)

/** Every link id that lives somewhere under Advanced > Trackpad Gesture Suggestions. */
internal val TRACKPAD_SETTING_LINK_IDS: Set<String> = setOf(
    "trackpad.add_word",
    "trackpad.add_word_full_width",
    "trackpad.swipe_to_delete",
    "trackpad.swipe_to_delete_provider",
    SettingLinkIds.TRACKPAD_GESTURES_ENABLED,
    SettingLinkIds.TRACKPAD_PROVIDER,
    SettingLinkIds.TRACKPAD_SHIZUKU_DEVICE,
    SettingLinkIds.TRACKPAD_SENSITIVITY,
    SettingLinkIds.TRACKPAD_DEBUG,
    SettingLinkIds.TRACKPAD_SCREEN_TRACKPAD
) + TRACKPAD_SENSITIVITY_PAGE_LINK_IDS + SCREEN_TRACKPAD_PAGE_LINK_IDS

/**
 * The page the gesture settings screen opens with.
 *
 * An explicit child page (the user tapped a row) wins. Otherwise only a setting link to a
 * row of the Screen trackpad or sensitivity page opens that page; anything else, including
 * the "Screen trackpad" row's own link and a stale or unknown child value, shows the
 * gesture settings.
 */
internal fun trackpadGesturePageFor(childPage: String?, linkId: String?): TrackpadGesturePage =
    when (childPage) {
        TRACKPAD_CHILD_PAGE_SCREEN -> TrackpadGesturePage.ScreenTrackpad
        TRACKPAD_CHILD_PAGE_SENSITIVITY -> TrackpadGesturePage.Sensitivity
        else -> when (linkId) {
            in SCREEN_TRACKPAD_PAGE_LINK_IDS -> TrackpadGesturePage.ScreenTrackpad
            in TRACKPAD_SENSITIVITY_PAGE_LINK_IDS -> TrackpadGesturePage.Sensitivity
            else -> TrackpadGesturePage.Gestures
        }
    }

/**
 * The Advanced destination name (see `AdvancedDestination`) for a hosting activity: the
 * explicit child page when there is one, else the page a setting link points into, else
 * the Advanced main page.
 */
internal fun advancedDestinationNameFor(childPage: String?, linkId: String?): String = when {
    childPage == ADVANCED_DESTINATION_IME_TEST -> ADVANCED_DESTINATION_IME_TEST
    childPage == ADVANCED_DESTINATION_TRACKPAD_GESTURES -> ADVANCED_DESTINATION_TRACKPAD_GESTURES
    linkId in TRACKPAD_SETTING_LINK_IDS -> ADVANCED_DESTINATION_TRACKPAD_GESTURES
    else -> ADVANCED_DESTINATION_MAIN
}

internal const val ADVANCED_DESTINATION_MAIN = "Main"
internal const val ADVANCED_DESTINATION_IME_TEST = "ImeTest"
internal const val ADVANCED_DESTINATION_TRACKPAD_GESTURES = "TrackpadGestures"
