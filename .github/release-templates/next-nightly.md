# Plektra Nightly · 26.11

Changes since `26.10-nightly.25.221136Z`:

- **Emoji, symbols and kaomoji:** new shared picker and search, skin-tone variants, expanded Unicode/CLDR data and bundled Noto emoji rendering. The full picker has its own stay-open setting. Narrow-window layouts, search height, hardware input and multi-character search have been improved.
- **Uyghur input:** added layout and language support, with layout-aware hardware and software keys, Shift layers and multi-character letters. Long-press replacement preserves complete committed text.
- **Keyboard modes and visibility:** phone keypads, a layout chooser and per-app visibility choices, including an option to never show the keyboard bar. Software-key secondary labels can be hidden. Hardware-keypad decisions respect app visibility and language shortcuts.
- **Themes and compact bars:** photo backgrounds shared across keyboard surfaces, fallback colours when a photo is unavailable, and improved narrow-window labels and popups. Slim suggestions rows remain compact while keeping their controls usable.
- **Typing fixes:** immediate sentence capitalization, manual Shift ownership, opening quotes and brackets, remapped autocorrection input, Alt Lock in calculator fields and optional SYM edit shortcuts. Beeper is included in experimental App Enter behaviour support.
- **Settings, launcher and dictionaries:** clearer settings-search paths, better Quick Launcher selection and clipboard shortcuts, streamed custom dictionary imports and matching validation. Clipboard-history ownership and expiry scheduling have been hardened.
- **Before the first unlock after restart:** Plektra can run with the saved layout and configured custom layouts, while dictionaries, clipboard history, text replacements and speech remain unavailable until unlock. A real Pixel 7a cold-boot check passed input-style cycling and continued input after unlock. On the Titan 2 Elite, Android still chose Kika and omitted Plektra from its locked-user IME list; that device limitation remains under investigation. See [Direct Boot behavior and verification](https://github.com/pkb-rocks/plektra/blob/direct-boot-ime/docs/direct-boot.md).

## Known limitations

- On the Pixel 7a, DE QWERTZ selected before unlock returned to an EN QWERTZ label afterwards. Input remained usable. Android's subtype selection and Plektra's stored layout are separate state; their exact transition remains under investigation. This Nightly retains the current layout carry-over behavior.
- Titan 2 Elite input before the first unlock is not accepted by this release's device check. Q25 and Key2 hardware checks remain outstanding.

## Installation

Update the existing `rocks.pkb.plektra.nightly` installation to keep its data. Choose **Full** for Clicks launcher-button interception, or **No accessibility** to omit that service. Normal keyboard features and TalkBack remain available in both. The variants share a package and replace each other.

See [getting started](https://github.com/pkb-rocks/plektra/blob/main/docs/getting-started.md) and [release channels](https://github.com/pkb-rocks/plektra/blob/main/docs/releasing.md).
