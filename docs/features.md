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

## Share target

Plektra appears in Android's share menu for text, links, pictures and other files, including several at once. It offers only the actions that fit the shared items:

- **Copy to clipboard** for a single item. Text is copied as text. A file or picture is copied as a private copy that other apps can read with its type. Plektra removes the copy when it sees that the clipboard has changed, and after 24 hours in any case; after that the clip can no longer be pasted. Very long text is refused with a hint to save it as a file.
- **Save as file** keeps the original bytes and name. Files go to Downloads unless Settings → Advanced → Location for shared files points to a folder you chose. If that folder becomes unavailable, Plektra says so and offers Downloads instead. Existing names are kept; the new file gets a numbered name.
- **Add to gallery** for pictures whose pixels Android can decode; exactly the checked bytes are added. They appear as the newest entries in Pictures, or in Pictures/Plektra with Settings → Advanced → Own album for shared pictures.

Plektra reads only the items the sharing app grants it and needs no storage permission. Each item reports its own result. A cancelled or failed save leaves no partial file; if Plektra is closed during a save, the incomplete file is removed on the next share or cleanup run. The chosen folder is not part of any backup because its access belongs to this installation.

## Languages

German, English and Italian dictionaries are bundled. Download other base dictionaries from Installed dictionaries. User words remain available without a base dictionary. Installing or removing a package refreshes the active keyboard.

Uyghur (ئۇيغۇرچە) includes a typing layout without a dictionary. Add Uyghur (`ug`) in Settings → Input Languages; it selects the Uyghur layout by default. It uses Uyghur computer key positions with extra Shift and Alt shortcuts for compact keyboards such as the Zinwa Q25. See [the key mappings](development.md#uyghur-layout).

## Data

Plektra stores its input data locally. Backups can include settings, layouts, mappings, themes, dictionaries, snippets, and other user-created data. Pastiera backup ZIPs can be imported in Advanced settings. Legacy preference names are translated, and settings absent from the backup remain unchanged. Backups include saved notes and private or modified dictionary packages. Known repository packages remain checksum references. Restore reports missing language packages without starting a download.

## Accessibility

- Configurable TalkBack announcements for keyboard bar changes
- Adjustable suggestion reading delays
- Bounce Keys for accidental repeated physical-key presses
- **Hardware keyboard only** (Keyboard bar → Visibility): Plektra shows no on-screen surface in any app, including SYM pages, the emoji picker, the clipboard and the caret badge. Physical typing, modifiers, shortcuts and hardware SYM layers keep working. Per-app »Never show the bar« stays available for single apps

See the [visual feature presentation](showcase/README.md).
