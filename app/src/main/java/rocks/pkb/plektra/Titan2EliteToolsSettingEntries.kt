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
    SettingEntry(
        id = SettingLinkIds.TITAN2_ELITE_TOOLS_DEVICE_SETUP,
        titleRes = R.string.device_setup_link_title,
        summaryRes = R.string.device_setup_link_description,
        route = SettingRoute(SettingsDestination.Titan2EliteTools),
        availabilityCheck = titan2EliteOnly,
        unavailableFallbackId = SettingLinkIds.MAIN_ADVANCED
    )
)

/** Availability for anything on the Titan 2 Elite tools screen. */
internal val titan2EliteOnly: (android.content.Context) -> Boolean = { DeviceSpecific.isTitan2EliteDevice() }
