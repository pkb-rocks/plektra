package rocks.pkb.plektra

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

internal fun Context.settingsActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.settingsActivity()
    else -> error("Settings must be hosted by an Activity")
}

private const val EXTRA_PAGE = "rocks.pkb.plektra.SETTINGS_PAGE"
private const val EXTRA_NAV_KEY = "rocks.pkb.plektra.SETTINGS_NAV_KEY"
private const val EXTRA_DEVICE_PAGE = "rocks.pkb.plektra.SETTINGS_DEVICE_PAGE"
private const val EXTRA_THEME_TAB = "rocks.pkb.plektra.SETTINGS_THEME_TAB"

internal fun Intent.putSettingsPage(page: SettingsPage): Intent = apply {
    putExtra(EXTRA_PAGE, page.destination.name)
    putExtra(SettingsActivity.EXTRA_CUSTOMIZATION_DESTINATION, page.customizationDestination)
    putExtra(SettingsActivity.EXTRA_KEYBOARD_THEME_TARGET, page.keyboardThemeTarget)
    putExtra(EXTRA_THEME_TAB, page.keyboardThemeTab)
    page.navModeKeyCode?.let { putExtra(EXTRA_NAV_KEY, it) }
    putExtra(EXTRA_DEVICE_PAGE, page.keyboardsDevicesDestination.name)
}

internal fun Intent.settingsPage(): SettingsPage {
    val linked = data?.let(SettingLinkRegistry::parseSettingLinkUri)?.let(SettingLinkRegistry::byId)
    if (linked != null && !linked.route.symCustomization) return linked.route.toSettingsPage()
    val destination = getStringExtra(EXTRA_PAGE)?.let { value ->
        SettingsDestination.entries.firstOrNull { it.name == value }
    } ?: when (getStringExtra(SettingsActivity.EXTRA_DESTINATION)) {
        SettingsActivity.DESTINATION_CUSTOMIZATION -> SettingsDestination.Customization
        SettingsActivity.DESTINATION_DEVICE_SYM_LAYER_EDITOR -> SettingsDestination.DeviceSymLayerEditor
        SettingsActivity.DESTINATION_MODIFIERS -> SettingsDestination.Modifiers
        else -> SettingsDestination.Main
    }
    return SettingsPage(destination,
        getStringExtra(SettingsActivity.EXTRA_CUSTOMIZATION_DESTINATION),
        getStringExtra(SettingsActivity.EXTRA_KEYBOARD_THEME_TARGET),
        getStringExtra(EXTRA_THEME_TAB),
        if (hasExtra(EXTRA_NAV_KEY)) getIntExtra(EXTRA_NAV_KEY, 0) else null,
        KeyboardsDevicesDestination.entries.firstOrNull { it.name == getStringExtra(EXTRA_DEVICE_PAGE) }
            ?: KeyboardsDevicesDestination.Main)
}

internal fun openSettingsPage(context: Context, page: SettingsPage) {
    context.startActivity(Intent(context, SettingsActivity::class.java).putSettingsPage(page))
}

/**
 * Child sections that live below a section's pages. Opening a page of the parent section
 * anew starts from a fresh child state, so a page extra left behind by a deeper page
 * (for example `settings.child.trackpad=screen` under Advanced > Trackpad Gestures) is
 * dropped instead of being inherited by every later copy of the intent.
 */
private val NESTED_SETTINGS_CHILD_SECTIONS: Map<String, Set<String>> = mapOf(
    "advanced" to setOf("trackpad")
)

/** The child sections whose page extras become stale when a page of [section] opens. */
internal fun staleSettingsChildSections(section: String): Set<String> =
    NESTED_SETTINGS_CHILD_SECTIONS[section].orEmpty()

internal fun settingsChildExtraKey(section: String): String = "settings.child.$section"

/** A child is a new platform Activity. Android alone owns the back stack and transitions. */
internal fun openSettingsChild(context: Context, section: String, page: String) {
    val activity = context.settingsActivity()
    activity.startActivity(Intent(activity.intent).apply {
        data = null
        flags = 0
        removeExtra(SymCustomizationActivity.EXTRA_SETTING_ID)
        staleSettingsChildSections(section).forEach { removeExtra(settingsChildExtraKey(it)) }
        putExtra(settingsChildExtraKey(section), page)
    })
}

internal fun settingsChild(context: Context, section: String): String? =
    context.settingsActivity().intent.getStringExtra(settingsChildExtraKey(section))

/**
 * Records the page a screen resolved for [section] on the hosting activity's intent, so a
 * child opened from it inherits the page even when it was derived from the setting-link
 * `data` (which [openSettingsChild] clears) rather than from a child extra.
 */
internal fun rememberSettingsChild(context: Context, section: String, page: String) {
    context.settingsActivity().intent.putExtra(settingsChildExtraKey(section), page)
}

/** The setting-link id the hosting activity was opened with, if any. */
internal fun settingsLinkId(context: Context): String? =
    context.settingsActivity().intent.data?.let(SettingLinkRegistry::parseSettingLinkUri)
