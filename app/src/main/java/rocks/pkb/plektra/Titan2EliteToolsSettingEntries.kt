package rocks.pkb.plektra

import rocks.pkb.plektra.inputmethod.DeviceSpecific

/**
 * Entries for the Titan 2 Elite tools screen ([Titan2EliteToolsScreen]).
 *
 * The screen only exists on the Titan 2 Elite, so every entry here is conditional. An unavailable
 * deep link or search hit falls back to the Advanced category, which is always visible.
 *
 * Adding a row from another feature: register its entry in this list with
 * `route = SettingRoute(SettingsDestination.Titan2EliteTools)` and the same [titan2EliteOnly]
 * availability, then render the row in [Titan2EliteToolsScreen].
 */
internal fun titan2EliteToolsSettingEntries(): List<SettingEntry> = listOf(
    SettingEntry(
        id = SettingLinkIds.MAIN_TITAN2_ELITE_TOOLS,
        titleRes = R.string.titan2_elite_tools_title,
        summaryRes = R.string.titan2_elite_tools_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    // the notification ring section and the rows of its page
    *notificationRingSettingEntries().toTypedArray(),
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_DEVICE_SETUP,
        titleRes = R.string.device_setup_link_title,
        summaryRes = R.string.device_setup_link_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    // smart keyboard backlight
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_SMART_BACKLIGHT,
        titleRes = R.string.smart_backlight_title,
        summaryRes = R.string.smart_backlight_row_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    // the keyboard-light tile's switch, inside the Smart backlight section
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_BACKLIGHT_TILE,
        titleRes = R.string.backlight_tile_holder_title,
        summaryRes = R.string.backlight_tile_holder_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    // the toolbox rows, rendered by ToolboxSection.kt
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_BLOAT,
        titleRes = R.string.bloat_title,
        summaryRes = R.string.bloat_row_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_DENSITY,
        titleRes = R.string.density_title,
        summaryRes = R.string.density_row_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_TWEAKS,
        titleRes = R.string.tweaks_title,
        summaryRes = R.string.tweaks_row_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_KEY_MAPPING,
        titleRes = R.string.keymap_title,
        summaryRes = R.string.keymap_row_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_FN_CTRL,
        titleRes = R.string.fn_ctrl_map_title,
        summaryRes = R.string.fn_ctrl_map_summary,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_SHOW_STATUS_BAR,
        titleRes = R.string.show_status_bar_title,
        summaryRes = R.string.show_status_bar_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    ),
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_RESET_TO_STOCK,
        titleRes = R.string.reset_to_stock_title,
        summaryRes = R.string.reset_to_stock_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    )
)

/** Availability for anything on the Titan 2 Elite tools screen. */
internal val titan2EliteOnly: (android.content.Context) -> Boolean = { DeviceSpecific.isTitan2EliteDevice() }
