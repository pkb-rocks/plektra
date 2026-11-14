package rocks.pkb.plektra

/**
 * registry entries for the notification ring: its section on the Titan 2 Elite tools
 * screen and the rows of its own page. They route to the tools screen, which shows the ring's page
 * when asked for one of the page rows (see [Titan2EliteToolsScreen]).
 *
 * Kept in its own file and spliced into [titan2EliteToolsSettingEntries] with one line, so the other
 * groups' entries on that screen do not collide with these.
 */
internal fun notificationRingSettingEntries(): List<SettingEntry> = listOf(
    ringEntry(
        SettingLinkIds.TITAN2_ELITE_TOOLS_NOTIFICATION_RING,
        R.string.ring_title,
        R.string.ring_row_description
    ),
    ringEntry(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_ENABLE, R.string.ring_enable_title, R.string.ring_enable_description),
    ringEntry(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_DURATION, R.string.ring_duration_title, R.string.ring_duration_description),
    ringEntry(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_BRIGHTNESS, R.string.ring_brightness_title, null),
    ringEntry(
        SettingLinkIds.TITAN2_ELITE_TOOLS_RING_KEYBOARD_DARK,
        R.string.ring_keyboard_dark_title,
        R.string.ring_keyboard_dark_description
    ),
    ringEntry(
        SettingLinkIds.TITAN2_ELITE_TOOLS_RING_DEFAULT_COLOR,
        R.string.ring_default_color_title,
        R.string.ring_default_color_description
    ),
    ringEntry(
        SettingLinkIds.TITAN2_ELITE_TOOLS_RING_APP_COLORS,
        R.string.ring_app_colors_title,
        R.string.ring_app_colors_description
    ),
    ringEntry(SettingLinkIds.TITAN2_ELITE_TOOLS_RING_ADJUST, R.string.ring_adjust, R.string.ring_adjust_description)
)

/** The rows that live on the ring's own page rather than on the tools screen itself. */
internal val notificationRingPageRowIds: Set<String> = setOf(
    SettingLinkIds.TITAN2_ELITE_TOOLS_RING_ENABLE,
    SettingLinkIds.TITAN2_ELITE_TOOLS_RING_DURATION,
    SettingLinkIds.TITAN2_ELITE_TOOLS_RING_BRIGHTNESS,
    SettingLinkIds.TITAN2_ELITE_TOOLS_RING_KEYBOARD_DARK,
    SettingLinkIds.TITAN2_ELITE_TOOLS_RING_DEFAULT_COLOR,
    SettingLinkIds.TITAN2_ELITE_TOOLS_RING_APP_COLORS,
    SettingLinkIds.TITAN2_ELITE_TOOLS_RING_ADJUST
)

private fun ringEntry(id: String, titleRes: Int, summaryRes: Int?) = SettingEntry(
    id = id,
    titleRes = titleRes,
    summaryRes = summaryRes,
    route = SettingRoute(SettingsDestination.Titan2EliteTools),
    availabilityCheck = titan2EliteOnly,
    unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
)
