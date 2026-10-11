# Features

Plektra focuses on efficient text input on Android devices with physical keyboards.

## Physical keyboard

- QWERTY, QWERTZ, AZERTY, Greek, Cyrillic, Arabic, Uyghur, and transliteration layouts
- Importable JSON layouts and per-layout variations
- Configurable Shift, Ctrl, Alt, SYM, long-press, multi-tap, and navigation behaviour
- App launch shortcuts and command mappings

## Input surfaces

- Configurable keyboard bar with suggestions, variation keys and menu buttons
- **Normal and Slim profiles** with independently configured visible content and menu buttons
- Optional bold word suggestions on physical and software keyboards
- Modifier indicators in the bottom strip, Android status bar and beside suggestions
- Touch SYM and emoji pages
- Optional software keyboard with themes and device-specific geometry
- Cyrillic software keys follow the active input profile; long press exposes additional multi-tap letters

## Text tools

- Dictionaries, suggestions, autocorrection, and locally learned next words
- Snippets plus emoji and symbol shortcodes
- Clipboard history and custom typing sounds
- Cursor and selection gestures
- Local Markdown notes with editing, split and preview views

## Languages

German, English and Italian dictionaries are bundled. Download other base dictionaries from Installed dictionaries. User words remain available without a base dictionary. Installing or removing a package refreshes the active keyboard.

Uyghur (ئۇيغۇرچە) includes a typing layout without a dictionary. Add `ug` in Settings → Input Languages and choose the Uyghur layout. It uses Uyghur computer key positions with extra Shift and Alt shortcuts for compact keyboards such as the Zinwa Q25. See [the key mappings](development.md#uyghur-layout).

## Data

Plektra stores its input data locally. Backups can include settings, layouts, mappings, themes, dictionaries, snippets, and other user-created data. Pastiera backup ZIPs can be imported in Advanced settings. Legacy preference names are translated, and settings absent from the backup remain unchanged. Backups include saved notes and private or modified dictionary packages. Known repository packages remain checksum references. Restore reports missing language packages without starting a download.

## Accessibility

- Configurable TalkBack announcements for keyboard bar changes
- Adjustable suggestion reading delays
- Bounce Keys for accidental repeated physical-key presses

See the [visual feature presentation](showcase/README.md).
