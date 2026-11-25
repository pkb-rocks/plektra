# Design guidelines

These guidelines describe Plektra's existing design so that new screens and
keyboard controls fit in. They do not introduce new components.

Plektra has two design layers:

- **App screens** (settings, notes, tutorial) use Material 3 in Compose,
  resolved by the *Appearance* settings.
- **Keyboard surfaces** (keyboard bar, suggestions, SYM, emoji, clipboard,
  software keyboard) use the keyboard theme (`KeyboardThemeColors`), which the
  user picks independently of the app appearance.

## App screens

`AppThemeResolver` turns three stored choices into one `ResolvedAppTheme`:

- theme mode: system, light or dark;
- dark surface: standard Material dark or OLED black (only while dark), which
  replaces `background`/`surface` with black and steps the surface containers
  in fixed greys;
- dynamic color: wallpaper colors from API 31, the Plektra palette otherwise.

New screens take every color from `MaterialTheme.colorScheme` and never hard
code surface colors, so all six combinations stay correct. Activities apply
the resolved theme through `PlektraTheme`, which also keeps system bar icon
contrast in sync (the main screen switches it while its dark header scrolls
under the status bar).

Settings screens share these building blocks:

- `PlektraSettingsHeader` for the title row with back navigation;
- `SettingsSectionDivider` for section headings;
- rows with a title in `bodyLarge`, an optional description in `bodySmall` on
  `onSurfaceVariant`, and a trailing `Switch` (`KeyboardBarCheckRow`) or a
  navigation arrow; a disabled row dims its text to 38 %;
- `Modifier.settingRow(id)` on every row that has a setting entry, so search
  and [settings links](settings-links.md) can find and highlight it. Each such
  row needs a `SettingEntry` with title and summary resources.

Text uses Material typography roles and scales with the system font size;
new rows must remain usable at large font sizes.

## Keyboard surfaces

- Colors come from the active `KeyboardThemeColors`: `background`, `normalKey`,
  `specialKey`, `statusBarButton`, `textAndIcons`, `accent`, `divider` and the
  LED colors. Icons are tinted with `textAndIcons`; pressed states use
  `accent`.
- Corner radii are a ratio of the control height (`keyCornerRadiusRatio`,
  `chromeCornerRadiusRatio`), so a theme stays consistent across bar heights.
- Bar buttons in one row share height and spacing. Overlays that replace a row,
  such as the ☰ menu, keep that row's button metrics.
- Outer controls of a row are bound with `KeyboardBarRowGeometry`, so they
  follow rounded display corners (see [rounded corners](#rounded-display-corners)).
- Text in the bar follows the keyboard-bar text size setting and the system
  font scale (`KeyboardBarTypography`); controls do not assume a fixed text
  size.
- Give every control a content description and make it focusable and
  clickable as a whole, so TalkBack can reach it.

### Visibility rules

- A surface blocked by the per-app setting or by the global hardware-only
  setting shows no Plektra control at all.
- A control that Android requires the IME to show must be visible on the
  surfaces that are shown. An entry in a closed menu is not shown. Example:
  `ImeSwitcherPolicy` for the API 36 IME switcher request.
- Optional controls prefer the ☰ menu; a setting can move them into the bar.
- Rare actions use low emphasis by size (for example the 18 dp switcher globe),
  not by low contrast.

### Rounded display corners

The keyboard bar follows the bottom corners of the display it is shown on.
Android's `RoundedCorner` report decides; a rectangular display stays flat.
A known panel may add a measured contour and a fallback radius, but only on
its built-in display. Navigation bar and cutout insets are always kept.

## Device builds

Device-specific defaults are data, not parallel code paths:

- `DeviceSpecific.hasBuiltInHardwareKeyboard()` decides keyboard-device
  defaults such as hiding the IME caption bar and its bottom reserve.
- `DisplayCorners` holds one detection per device family whose built-in
  display is the known rounded panel. A build for a device that shares the
  panel adds one entry; the contour, fallback radius and calibration default
  then follow. Detections run on every query and again when input devices
  change, so they may depend on hardware that appears after the IME started.

Stored user choices always win over these defaults, and defaults are never
persisted.
