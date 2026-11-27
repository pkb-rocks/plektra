package rocks.pkb.plektra

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** Windows at least this wide show the settings list and a category side by side. */
internal val SettingsTwoPaneMinWidth = 600.dp

/**
 * The settings root. A narrow window shows the familiar single page. A wide
 * window, such as the inner display of a foldable, shows the category list and
 * the selected category next to each other:
 *
 * - Each pane scrolls on its own. The selected category is the detail root: it
 *   has no Back, and the list marks it. Pages below a category open as before.
 * - The panes split evenly, at the hinge of a vertical fold or in the middle. Each
 *   pane has its own dot row on its right; the list also answers the left screen edge,
 *   the category only the right one, so a single track sits at the fold.
 * - The selection survives folding and unfolding. A narrow window shows the
 *   open category with Back to the list, or the list when none is open.
 */
@Composable
internal fun SettingsAdaptiveRoot(
    settingLinkRequest: SettingLinkRequest?,
    initialDestination: String?,
    initialCustomizationDestination: String?,
    initialKeyboardThemeTarget: String?
) {
    val context = LocalContext.current
    val intentPage = remember { context.settingsActivity().intent.settingsPage() }
    val wide = LocalConfiguration.current.screenWidthDp.dp >= SettingsTwoPaneMinWidth
    var selected by rememberSaveable(stateSaver = SettingsPageSaver) {
        mutableStateOf(SettingsPage(SettingsDestination.KeyboardsDevices))
    }
    // True once the person opened a category here; a narrow window then shows it instead of the list.
    var categoryOpen by rememberSaveable { mutableStateOf(false) }

    if (intentPage.destination != SettingsDestination.Main || settingLinkRequest != null) {
        SettingsScreen(
            initialDestination = initialDestination,
            initialCustomizationDestination = initialCustomizationDestination,
            initialKeyboardThemeTarget = initialKeyboardThemeTarget,
            settingLinkRequest = settingLinkRequest
        )
        return
    }

    if (!wide) {
        // One Activity serves both widths, so folding and unfolding keep the open category.
        if (categoryOpen) {
            BackHandler { categoryOpen = false }
            key(selected) {
                val base = LocalContext.current
                val paneContext = remember(base, selected) { SettingsDetailPaneContext(base, selected) }
                CompositionLocalProvider(LocalContext provides paneContext) {
                    SettingsScreen(page = selected, onExit = { categoryOpen = false })
                }
            }
        } else {
            SettingsScreen(
                page = SettingsPage(SettingsDestination.Main),
                onOpenPage = { selected = it; categoryOpen = true }
            )
        }
        return
    }

    val activity = context.settingsActivity()
    val layoutInfo by remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }.collectAsState(initial = null)
    val fold = layoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = maxWidth
        // An even split: at the hinge when the window has a vertical fold, otherwise in the middle.
        val (listWidth, detailStart) = fold?.let {
            with(density) { it.bounds.left.toDp() to it.bounds.right.toDp() }
        }?.takeIf { (left, right) -> left > 200.dp && right < total - 200.dp }
            ?: (total / 2).let { it to it }

        CompositionLocalProvider(LocalSelectedSettingLink provides selectedLinkId(selected)) {
            Box(Modifier.width(listWidth).fillMaxHeight()) {
              CompositionLocalProvider(LocalSectionJumpSides provides SectionJumpSides.Both) {
                SettingsScreen(
                    page = SettingsPage(SettingsDestination.Main),
                    onOpenPage = { selected = it; categoryOpen = true }
                )
              }
            }
        }
        DetailPane(page = selected, start = detailStart, width = total - detailStart)
    }
}

@Composable
private fun DetailPane(page: SettingsPage, start: Dp, width: Dp) {
    val base = LocalContext.current
    val paneContext = remember(base, page) { SettingsDetailPaneContext(base, page) }
    Box(
        Modifier
            .offset { IntOffset(start.roundToPx(), 0) }
            .width(width)
            .fillMaxHeight()
    ) {
        key(page) {
            CompositionLocalProvider(
                LocalContext provides paneContext,
                LocalSettingsDetailRoot provides true
            ) {
                // The category is the detail root; leaving it means picking another category.
                CompositionLocalProvider(LocalSectionJumpSides provides SectionJumpSides.End) {
                    SettingsScreen(page = page, onExit = {})
                }
            }
        }
    }
}

/** The list row that opens [page], so the list can mark the category shown on the right. */
private fun selectedLinkId(page: SettingsPage): String? = when (page.destination) {
    SettingsDestination.KeyboardsDevices -> SettingLinkIds.MAIN_KEYBOARDS_DEVICES
    SettingsDestination.Modifiers -> SettingLinkIds.MAIN_MODIFIERS
    SettingsDestination.CustomInputStyles -> SettingLinkIds.MAIN_CUSTOM_INPUT_STYLES
    SettingsDestination.TextInput -> SettingLinkIds.MAIN_TEXT_INPUT
    SettingsDestination.AutoCorrection -> SettingLinkIds.MAIN_AUTO_CORRECTION
    SettingsDestination.SpeechRecognition -> "main.speech_recognition"
    SettingsDestination.AppAppearance -> SettingLinkIds.MAIN_APP_APPEARANCE
    SettingsDestination.AppLanguage -> SettingLinkIds.MAIN_APP_LANGUAGE
    SettingsDestination.NavMode -> SettingLinkIds.MAIN_NAV_MODE
    SettingsDestination.Advanced -> SettingLinkIds.MAIN_ADVANCED
    SettingsDestination.Accessibility -> SettingLinkIds.MAIN_ACCESSIBILITY
    SettingsDestination.About -> SettingLinkIds.MAIN_ABOUT
    SettingsDestination.Customization -> when (page.customizationDestination) {
        null -> SettingLinkIds.MAIN_CUSTOMIZATION
        SettingsActivity.CUSTOMIZATION_DESTINATION_KEYBOARD_THEME -> SettingLinkIds.MAIN_KEYBOARD_THEME
        SettingsActivity.CUSTOMIZATION_DESTINATION_STATUS_BAR_BUTTONS -> SettingLinkIds.MAIN_STATUS_BAR_BUTTONS
        SettingsActivity.CUSTOMIZATION_DESTINATION_LAUNCHER_SHORTCUTS -> SettingLinkIds.MAIN_LAUNCHER_SHORTCUTS
        SettingsActivity.CUSTOMIZATION_DESTINATION_APP_ENTER_BEHAVIOR -> SettingLinkIds.MAIN_APP_ENTER_BEHAVIOR
        "display_geometry" -> "keyboard_bar.geometry"
        else -> null
    }
    else -> null
}

/** Keeps the selected category across folding, unfolding and process recreation. */
private val SettingsPageSaver = Saver<SettingsPage, List<String?>>(
    save = {
        listOf(
            it.destination.name,
            it.customizationDestination,
            it.keyboardThemeTarget,
            it.keyboardThemeTab,
            it.navModeKeyCode?.toString(),
            it.keyboardsDevicesDestination.name
        )
    },
    restore = { saved ->
        SettingsPage(
            destination = SettingsDestination.entries.firstOrNull { it.name == saved[0] } ?: SettingsDestination.KeyboardsDevices,
            customizationDestination = saved[1],
            keyboardThemeTarget = saved[2],
            keyboardThemeTab = saved[3],
            navModeKeyCode = saved[4]?.toIntOrNull(),
            keyboardsDevicesDestination = KeyboardsDevicesDestination.entries.firstOrNull { it.name == saved[5] }
                ?: KeyboardsDevicesDestination.Main
        )
    }
)
