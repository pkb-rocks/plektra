package rocks.pkb.plektra

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.LocalResolvedAppTheme

/*
 * Shared building blocks for Plektra's settings screens (Hybrid C).
 *
 * Both user-selectable layout styles (Compact with dividers and Material) share
 * this single component set, interaction model, accessibility semantics, and
 * compact density. Pages choose components and semantic accent roles rather
 * than defining local colors, paddings, or row variants.
 */

/** Semantic accent roles for purposeful category anchoring and visual skimming. */
internal enum class SettingsAccentRole {
    /** Core input and typing: keyboards, text input, auto-correction, modifiers, speech. */
    PRIMARY,
    /** Personalization and visuals: appearance, keyboard themes, customization, languages. */
    TERTIARY,
    /** System, navigation, and utilities: nav mode, enter behavior, trackpad, accessibility, advanced. */
    SECONDARY
}

/** Spacing and sizing tokens of the settings screens. */
internal object SettingsSpace {
    val edge = 16.dp
    val rowVertical = 8.dp
    val rowMinHeight = 56.dp
    val iconGap = 16.dp
    val controlGap = 12.dp
    val textInsetWithIcon = edge + 24.dp + iconGap
    val materialRowHorizontalMargin = 12.dp
    val materialRowVerticalGap = 2.dp
    val materialRowCorner = 14.dp
    val cardCorner = 18.dp
}

internal object SettingsText {
    /**
     * Long multi-sentence explanations move behind [SettingsInfoButton] while concise
     * descriptions stay readable inline, scaling proportionally at large font sizes.
     */
    @Composable
    fun inlineDescriptionMaxChars(): Int =
        (110f / LocalDensity.current.fontScale.coerceAtLeast(1f)).toInt()
}

/** Receives section headings and their coordinates for [SettingsSectionScrollColumn]. */
internal fun interface SettingsSectionIndex {
    fun onSection(label: String, level: SettingsHeadingLevel, coordinates: LayoutCoordinates)
}

internal val LocalSettingsSectionIndex = staticCompositionLocalOf<SettingsSectionIndex?> { null }

/** True while a page is the root of a detail pane: it has no Back button of its own. */
internal val LocalSettingsDetailRoot = staticCompositionLocalOf { false }

/** The link id of the category shown in the detail pane, so the list can mark it. */
internal val LocalSelectedSettingLink = compositionLocalOf<String?> { null }

/** Heading meaning, independent of window width. */
internal enum class SettingsHeadingLevel { PAGE, SECTION, SUBSECTION }

internal object SettingsTypography {
    private val wrap = TextStyle(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)

    @Composable fun page(): TextStyle =
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold).merge(wrap)

    @Composable fun section(): TextStyle =
        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold).merge(wrap)

    @Composable fun subsection(): TextStyle =
        MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium).merge(wrap)

    @Composable fun rowTitle(): TextStyle =
        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium).merge(wrap)

    @Composable fun rowDescription(): TextStyle =
        MaterialTheme.typography.bodyMedium.merge(wrap)

    @Composable fun rowValue(): TextStyle =
        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium).merge(wrap)
}

/** Theme-coherent semantic color roles derived solely from [MaterialTheme.colorScheme]. */
internal object SettingsColors {
    @Composable
    fun accent(role: SettingsAccentRole = SettingsAccentRole.PRIMARY): Color = when (role) {
        SettingsAccentRole.PRIMARY -> MaterialTheme.colorScheme.primary
        SettingsAccentRole.TERTIARY -> MaterialTheme.colorScheme.tertiary
        SettingsAccentRole.SECONDARY -> MaterialTheme.colorScheme.secondary
    }

    @Composable
    fun iconContainer(role: SettingsAccentRole = SettingsAccentRole.PRIMARY): Color = when (role) {
        SettingsAccentRole.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
        SettingsAccentRole.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer
        SettingsAccentRole.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer
    }

    @Composable
    fun onIconContainer(role: SettingsAccentRole = SettingsAccentRole.PRIMARY): Color = when (role) {
        SettingsAccentRole.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
        SettingsAccentRole.TERTIARY -> MaterialTheme.colorScheme.onTertiaryContainer
        SettingsAccentRole.SECONDARY -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    @Composable fun icon(role: SettingsAccentRole = SettingsAccentRole.PRIMARY): Color = accent(role)
    @Composable fun value(): Color = MaterialTheme.colorScheme.primary
    @Composable fun supportingText(): Color = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun sectionDivider(): Color = MaterialTheme.colorScheme.outlineVariant
    @Composable fun disabled(color: Color): Color = color.copy(alpha = 0.38f)
}

@Composable
internal fun currentSettingsStyle(): AppSettingsStyle =
    LocalResolvedAppTheme.current.settingsStyle

/**
 * Left-aligned page header (H1). Shows Back unless the page is the detail root of a
 * two-pane layout. Uses a subtle hairline separator in [AppSettingsStyle.COMPACT_DIVIDERS].
 *
 * With full-screen settings, the header is the topmost row: it avoids display cutouts
 * element by element and offers the global settings search, which takes the title's
 * place in the same row until it is closed (Back, Escape or the close button).
 */
@Composable
internal fun PlektraSettingsHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    bottom: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val detailRoot = LocalSettingsDetailRoot.current
    val style = currentSettingsStyle()
    val fullscreen = LocalResolvedAppTheme.current.settingsFullscreen
    val showBack = onBack != null && !detailRoot
    // The detail pane of a two-pane layout sits next to the list's own search field.
    val searchAvailable = fullscreen && !detailRoot
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searching = searchAvailable && searchOpen
    val context = LocalContext.current
    val navigator = LocalSettingEntryNavigator.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFieldFocus = remember { FocusRequester() }
    val searchButtonFocus = remember { FocusRequester() }
    var returnFocus by remember { mutableStateOf(false) }
    fun closeSearch() {
        keyboardController?.hide()
        searchQuery = ""
        searchOpen = false
        returnFocus = true
    }
    BackHandler(enabled = searching) { closeSearch() }
    LaunchedEffect(searching) {
        if (!searching && returnFocus) {
            returnFocus = false
            runCatching { searchButtonFocus.requestFocus() }
        }
    }
    // Results lie over the unchanged page below the header, so closing finds it as it was.
    val resultsPanel: (@Composable () -> Unit)? = if (searching && searchQuery.isNotBlank()) {
        {
            val results = remember(searchQuery, context) { SettingLinkRegistry.search(context, searchQuery) }
            val pane = stringResource(R.string.settings_search_hint)
            Surface(
                modifier = Modifier.semantics { paneTitle = pane },
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                SettingsSearchResultList(
                    results = results,
                    onOpen = { entry ->
                        closeSearch()
                        navigator?.invoke(entry) ?: openSettingEntryByLink(context, entry)
                    },
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
                    )
                )
            }
        }
    } else null
    SettingsHeaderWithPanel(modifier, searching, ::closeSearch, resultsPanel) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxWidth()) {
            val backButton: @Composable () -> Unit = {
                if (showBack) {
                    IconButton(onClick = onBack!!) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description)
                        )
                    }
                }
            }
            val titleBlock: @Composable () -> Unit = {
                Column(
                    modifier = Modifier.padding(
                        start = if (showBack) 4.dp else 0.dp, end = 8.dp, top = 4.dp, bottom = 4.dp
                    )
                ) {
                    Text(
                        text = title,
                        style = SettingsTypography.page(),
                        modifier = Modifier.semantics { heading() }
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            text = subtitle,
                            style = SettingsTypography.rowDescription(),
                            color = SettingsColors.supportingText()
                        )
                    }
                }
            }
            val pageActions: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchAvailable) {
                        IconButton(
                            onClick = { searchOpen = true },
                            modifier = if (searching) Modifier else Modifier.focusRequester(searchButtonFocus)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.settings_search_hint)
                            )
                        }
                    }
                    actions()
                }
            }
            val normalPadStart = if (showBack) 4.dp else SettingsSpace.edge
            SettingsHeaderRow(
                cutouts = settingsHeaderCutouts(fullscreen),
                padStart = if (searching) 4.dp else normalPadStart,
                padEnd = 8.dp,
                // Searching keeps the height of the title row: same row, same content start below.
                reference = if (searching) {
                    SettingsHeaderRowReference(normalPadStart, backButton, titleBlock, pageActions)
                } else null,
                start = {
                    if (searching) {
                        IconButton(onClick = { closeSearch() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_search_close)
                            )
                        }
                    } else {
                        backButton()
                    }
                },
                middle = {
                    if (searching) {
                        val inputMethod = LocalSoftwareKeyboardController.current
                        LaunchedEffect(Unit) {
                            searchFieldFocus.requestFocus()
                            inputMethod?.show()
                        }
                        SettingsSearchField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 8.dp)
                                .focusRequester(searchFieldFocus)
                                .onPreviewKeyEvent { event ->
                                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyUp) closeSearch()
                                    true
                                }
                        )
                    } else {
                        titleBlock()
                    }
                },
                end = { if (!searching) pageActions() }
            )
            if (bottom != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Box(if (searching) Modifier.clearAndSetSemantics {} else Modifier) { bottom() }
                }
            }
            if (style == AppSettingsStyle.COMPACT_DIVIDERS) {
                HorizontalDivider(color = SettingsColors.sectionDivider().copy(alpha = 0.65f))
            }
        }
    }
    }
}

/**
 * Left-aligned section (H2) or subsection (H3) heading in the semantic accent color.
 *
 * In [AppSettingsStyle.COMPACT_DIVIDERS], a subtle trailing horizontal rule anchors the
 * section visually without adding vertical bulk. In [AppSettingsStyle.MATERIAL], the
 * clean accented label leads the following tonal rows. Both styles report their position
 * to [LocalSettingsSectionIndex] for the Niagara section jump.
 */
@Composable
internal fun SettingsSectionDivider(
    label: String,
    modifier: Modifier = Modifier,
    level: SettingsHeadingLevel = SettingsHeadingLevel.SECTION,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    val style = currentSettingsStyle()
    val textStyle = if (level == SettingsHeadingLevel.SUBSECTION) {
        SettingsTypography.subsection()
    } else {
        SettingsTypography.section()
    }
    val expandable = expanded != null && onExpandedChange != null
    val stateText = if (expandable) {
        stringResource(if (expanded == true) R.string.settings_section_expanded else R.string.settings_section_collapsed)
    } else {
        null
    }
    val accentColor = if (level == SettingsHeadingLevel.SUBSECTION) {
        SettingsColors.supportingText()
    } else {
        SettingsColors.accent(accentRole)
    }
    val lineColor = SettingsColors.sectionDivider().copy(alpha = 0.72f)
    val sectionIndex = LocalSettingsSectionIndex.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (sectionIndex != null) Modifier.onPlaced { sectionIndex.onSection(label, level, it) }
                else Modifier
            )
            .then(
                if (expandable) Modifier.clickable(role = Role.Button) { onExpandedChange!!(expanded != true) }
                else Modifier
            )
            .semantics(mergeDescendants = true) {
                heading()
                if (stateText != null) stateDescription = stateText
            }
            .padding(
                start = SettingsSpace.edge,
                end = if (expandable) 4.dp else SettingsSpace.edge,
                top = if (level == SettingsHeadingLevel.SUBSECTION) 10.dp else 14.dp,
                bottom = if (style == AppSettingsStyle.MATERIAL) 6.dp else 4.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            style = textStyle,
            color = accentColor,
            modifier = if (style == AppSettingsStyle.MATERIAL) Modifier.weight(1f) else Modifier
        )
        if (style == AppSettingsStyle.COMPACT_DIVIDERS) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = lineColor
            )
        }
        if (expandable) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (expanded == true) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = SettingsColors.supportingText()
                )
            }
        }
    }
}

/** Compatibility alias for screens that use [SettingsSectionLabel] directly. */
@Composable
internal fun SettingsSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY
) {
    SettingsSectionDivider(label = text, modifier = modifier, accentRole = accentRole)
}

/**
 * Shared compact settings row. The whole row is the interactive target.
 *
 * Adapts automatically to [AppSettingsStyle.COMPACT_DIVIDERS] (flat full-width row with
 * accented icons) and [AppSettingsStyle.MATERIAL] (compact tonal rounded row surface with
 * optional tonal icon badges).
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY,
    useIconBadge: Boolean = false,
    enabled: Boolean = true,
    /** Refines the row above: no icon of its own, text aligned with that row's title. */
    dependent: Boolean = false,
    selected: Boolean = false,
    alwaysInlineDescription: Boolean = false,
    titleMaxLines: Int = Int.MAX_VALUE,
    descriptionMaxLines: Int = Int.MAX_VALUE,
    /** Current value shown under the title in the accent color for fast skimming. */
    value: String? = null,
    /** Custom leading graphic, such as an app icon or swatch. */
    leading: (@Composable () -> Unit)? = null,
    /** Longer explanation placed behind [SettingsInfoButton]. */
    info: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val style = currentSettingsStyle()
    val maxInline = SettingsText.inlineDescriptionMaxChars()
    val inlineDescription = description?.takeIf {
        alwaysInlineDescription || info != null || it.length <= maxInline
    }
    val popupText = info ?: description?.takeIf { inlineDescription == null }
    val titleColor = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else SettingsColors.disabled(it) }
    val supportColor = SettingsColors.supportingText().let { if (enabled) it else SettingsColors.disabled(it) }
    val valueColor = SettingsColors.value().let { if (enabled) it else SettingsColors.disabled(it) }
    val iconColor = SettingsColors.icon(accentRole).let { if (enabled) it else SettingsColors.disabled(it) }
    val selectedBg = MaterialTheme.colorScheme.secondaryContainer
    val materialBg = if (selected) selectedBg else MaterialTheme.colorScheme.surfaceContainerLow

    val outerModifier = when (style) {
        AppSettingsStyle.MATERIAL -> Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsSpace.materialRowHorizontalMargin,
                vertical = SettingsSpace.materialRowVerticalGap
            )
            .clip(RoundedCornerShape(SettingsSpace.materialRowCorner))
            .background(materialBg)
        AppSettingsStyle.COMPACT_DIVIDERS -> Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.drawBehind {
                    val inset = 8.dp.toPx()
                    drawRoundRect(
                        color = selectedBg,
                        topLeft = Offset(inset, 0f),
                        size = Size(size.width - 2 * inset, size.height),
                        cornerRadius = CornerRadius(14.dp.toPx())
                    )
                } else Modifier
            )
    }

    val startPadding = when {
        dependent && style == AppSettingsStyle.MATERIAL -> 12.dp + 24.dp + SettingsSpace.iconGap
        dependent -> SettingsSpace.textInsetWithIcon
        style == AppSettingsStyle.MATERIAL -> 12.dp
        else -> SettingsSpace.edge
    }
    val endPadding = if (style == AppSettingsStyle.MATERIAL) 12.dp else SettingsSpace.edge

    Row(
        modifier = outerModifier
            .then(modifier)
            .heightIn(min = SettingsSpace.rowMinHeight)
            .padding(
                start = startPadding,
                end = endPadding,
                top = SettingsSpace.rowVertical,
                bottom = SettingsSpace.rowVertical
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.iconGap)
    ) {
        if (!dependent) {
            val hasVectorOrPainter = icon != null || iconPainter != null
            when {
                hasVectorOrPainter && style == AppSettingsStyle.MATERIAL && useIconBadge -> {
                    val badgeBg = SettingsColors.iconContainer(accentRole)
                        .let { if (enabled) it else it.copy(alpha = 0.38f) }
                    val badgeFg = SettingsColors.onIconContainer(accentRole)
                        .let { if (enabled) it else SettingsColors.disabled(it) }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(badgeBg),
                        contentAlignment = Alignment.Center
                    ) {
                        if (icon != null) {
                            Icon(icon, contentDescription = null, tint = badgeFg, modifier = Modifier.size(20.dp))
                        } else if (iconPainter != null) {
                            Icon(iconPainter, contentDescription = null, tint = badgeFg, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                icon != null -> Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                iconPainter != null -> Icon(iconPainter, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                leading != null -> CompositionLocalProvider(LocalContentColor provides iconColor) { leading() }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = SettingsTypography.rowTitle(),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = titleColor,
                maxLines = titleMaxLines
            )
            if (!value.isNullOrEmpty()) {
                Text(
                    text = value,
                    style = SettingsTypography.rowValue(),
                    color = valueColor
                )
            }
            if (!inlineDescription.isNullOrEmpty()) {
                Text(
                    text = inlineDescription,
                    style = SettingsTypography.rowDescription(),
                    color = supportColor,
                    maxLines = descriptionMaxLines
                )
            }
        }
        if (!popupText.isNullOrEmpty()) {
            SettingsInfoButton(title = title, text = popupText)
        }
        if (trailing != null) {
            CompositionLocalProvider(LocalContentColor provides supportColor) { trailing() }
        }
    }
}

/** Row that opens a subpage or dialog. Marks itself selected in two-pane mode. */
@Composable
internal fun SettingsNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY,
    useIconBadge: Boolean = false,
    enabled: Boolean = true,
    dependent: Boolean = false,
    alwaysInlineDescription: Boolean = false,
    linkId: String? = null,
    leading: (@Composable () -> Unit)? = null,
    info: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val selected = linkId != null && linkId == LocalSelectedSettingLink.current
    SettingsRow(
        title = title,
        description = description,
        value = value,
        info = info,
        icon = icon,
        iconPainter = iconPainter,
        accentRole = accentRole,
        useIconBadge = useIconBadge,
        leading = leading,
        enabled = enabled,
        dependent = dependent,
        selected = selected,
        alwaysInlineDescription = alwaysInlineDescription,
        trailing = trailing,
        modifier = modifier
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .then(if (enabled) Modifier.settingRow(linkId, onClick) else Modifier.settingRow(null))
    )
}

/** Switch row with one accessible toggle handler across the entire row. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY,
    enabled: Boolean = true,
    dependent: Boolean = false,
    alwaysInlineDescription: Boolean = false,
    linkId: String? = null,
    leading: (@Composable () -> Unit)? = null,
    info: String? = null,
    extra: (@Composable () -> Unit)? = null
) {
    SettingsRow(
        title = title,
        description = description,
        info = info,
        icon = icon,
        iconPainter = iconPainter,
        accentRole = accentRole,
        leading = leading,
        enabled = enabled,
        dependent = dependent,
        alwaysInlineDescription = alwaysInlineDescription,
        modifier = modifier
            .settingRow(linkId)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                extra?.invoke()
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
        }
    )
}

/** One option of a single-choice group; the whole row selects it. */
@Composable
internal fun SettingsRadioChoice(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    linkId: String? = null,
    /** Decorative mark (e.g. flag, icon, swatch); silent for TalkBack. */
    leading: (@Composable () -> Unit)? = null,
    trailingRadio: Boolean = false
) {
    val style = currentSettingsStyle()
    val titleColor = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else SettingsColors.disabled(it) }
    val supportColor = SettingsColors.supportingText().let { if (enabled) it else SettingsColors.disabled(it) }
    val iconColor = (if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        .let { if (enabled) it else SettingsColors.disabled(it) }
    val rowBg = when {
        style == AppSettingsStyle.MATERIAL && selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        style == AppSettingsStyle.MATERIAL -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> Color.Transparent
    }
    val outer = if (style == AppSettingsStyle.MATERIAL) {
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsSpace.materialRowHorizontalMargin,
                vertical = SettingsSpace.materialRowVerticalGap
            )
            .clip(RoundedCornerShape(SettingsSpace.materialRowCorner))
            .background(rowBg)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = outer
            .then(modifier)
            .settingRow(linkId)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 52.dp)
            .padding(
                horizontal = if (style == AppSettingsStyle.MATERIAL) 12.dp else SettingsSpace.edge,
                vertical = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.controlGap)
    ) {
        if (!trailingRadio) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
        if (leading != null) {
            Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides iconColor) { leading() }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = SettingsTypography.rowTitle(),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = titleColor
            )
            if (!description.isNullOrEmpty()) {
                Text(
                    text = description,
                    style = SettingsTypography.rowDescription(),
                    color = supportColor
                )
            }
        }
        if (trailingRadio) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

/** Single choice inside a dialog: the whole row selects, the radio button leads. */
@Composable
internal fun DialogRadioRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    val titleColor = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else SettingsColors.disabled(it) }
    val supportColor = SettingsColors.supportingText().let { if (enabled) it else SettingsColors.disabled(it) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.controlGap)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = text,
                style = SettingsTypography.rowTitle(),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = titleColor
            )
            if (!description.isNullOrEmpty()) {
                Text(text = description, style = SettingsTypography.rowDescription(), color = supportColor)
            }
        }
    }
}

/**
 * Row showing the current choice in the accent color and opening a single-choice dialog.
 */
@Composable
internal fun <T> SettingsChoiceRow(
    title: String,
    selected: T?,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    optionDescription: (@Composable (T) -> String?)? = null,
    icon: ImageVector? = null,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY,
    enabled: Boolean = true,
    dependent: Boolean = false,
    linkId: String? = null,
    placeholder: String = "–",
    leading: (@Composable () -> Unit)? = null,
    info: String? = null
) {
    var open by rememberSaveable(key = "settings-choice:$title") { mutableStateOf(false) }
    SettingsNavigationRow(
        title = title,
        value = selected?.let { label(it) } ?: placeholder,
        description = description,
        info = info,
        icon = icon,
        accentRole = accentRole,
        leading = leading,
        enabled = enabled,
        dependent = dependent,
        linkId = linkId,
        modifier = modifier,
        onClick = { open = true }
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier
                        .selectableGroup()
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { option ->
                        DialogRadioRow(
                            text = label(option),
                            description = optionDescription?.invoke(option),
                            selected = option == selected,
                            onClick = {
                                open = false
                                onSelected(option)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

/** Plain supporting text under a heading or between rows, without decorative warning boxes. */
@Composable
internal fun SettingsSupportingText(
    text: String,
    modifier: Modifier = Modifier,
    dependent: Boolean = false,
    isError: Boolean = false
) {
    Text(
        text = text,
        style = SettingsTypography.rowDescription(),
        color = if (isError) MaterialTheme.colorScheme.error else SettingsColors.supportingText(),
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (dependent) SettingsSpace.textInsetWithIcon else SettingsSpace.edge,
                end = SettingsSpace.edge,
                top = 4.dp,
                bottom = 4.dp
            )
    )
}

/** Tonal card reserved for previews, status widgets, and composite controls. */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit
) {
    val style = currentSettingsStyle()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsSpace.edge, vertical = 4.dp),
        shape = RoundedCornerShape(SettingsSpace.cardCorner),
        color = color,
        border = if (style == AppSettingsStyle.COMPACT_DIVIDERS) {
            BorderStroke(1.dp, SettingsColors.sectionDivider())
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
internal fun SettingsBottomSpacer() {
    Spacer(Modifier.height(16.dp))
}

/** A value shown at the end of a row in the primary accent color for fast skimming. */
@Composable
internal fun SettingsValueText(value: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(
        text = value,
        style = SettingsTypography.rowValue(),
        color = SettingsColors.value().let { if (enabled) it else SettingsColors.disabled(it) },
        textAlign = TextAlign.End,
        modifier = modifier.widthIn(max = 160.dp)
    )
}

/** Shared info button opening [SettingsInfoDialog] with the full setting explanation. */
@Composable
internal fun SettingsInfoButton(title: String, text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(key = "settings-info:$title") { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.settings_info_content_description, title),
            tint = SettingsColors.supportingText()
        )
    }
    if (open) SettingsInfoDialog(title = title, text = text, onDismiss = { open = false })
}

/** Shared info dialog for longer setting details. */
@Composable
internal fun SettingsInfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}

/** Exposed read-only dropdown select, visibly editable, with optional info popup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsDropdownRow(
    title: String,
    value: String,
    options: List<Pair<T, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY,
    enabled: Boolean = true,
    dependent: Boolean = false,
    linkId: String? = null
) {
    val iconColor = SettingsColors.icon(accentRole).let { if (enabled) it else SettingsColors.disabled(it) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .settingRow(linkId)
            .padding(
                start = if (dependent) SettingsSpace.textInsetWithIcon else SettingsSpace.edge,
                end = SettingsSpace.edge,
                top = SettingsSpace.rowVertical,
                bottom = SettingsSpace.rowVertical
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.iconGap)
    ) {
        if (!dependent) {
            when {
                icon != null -> Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                iconPainter != null -> Icon(iconPainter, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
        }
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { if (enabled) onExpandedChange(it) },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(title) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
            )
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { onExpandedChange(false) }) {
                options.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelected(id) })
                }
            }
        }
        if (!description.isNullOrEmpty()) SettingsInfoButton(title = title, text = description)
    }
}

/** Compact slider row with accented current value label aligned next to the title. */
@Composable
internal fun SettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    description: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    accentRole: SettingsAccentRole = SettingsAccentRole.PRIMARY,
    enabled: Boolean = true,
    dependent: Boolean = false,
    linkId: String? = null,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth().settingRow(linkId)) {
        SettingsRow(
            title = title,
            description = description,
            value = null,
            icon = icon,
            iconPainter = iconPainter,
            accentRole = accentRole,
            enabled = enabled,
            dependent = dependent,
            trailing = { SettingsValueText(valueLabel, enabled = enabled) }
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (dependent || icon != null || iconPainter != null) SettingsSpace.textInsetWithIcon else SettingsSpace.edge,
                    end = SettingsSpace.edge
                )
        )
    }
}
