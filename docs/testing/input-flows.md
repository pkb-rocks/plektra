# Input flows on a device

`scripts/input_flow.py` drives Plektra with hardware keys and software-keyboard touches and checks the text in the real Notes editor. Flows live in `scripts/input-flows/*.flow`.

```sh
python3 scripts/input_flow.py --device <serial> devices
python3 scripts/input_flow.py --device <serial> plan scripts/input-flows/t2e-alt-lock.flow
python3 scripts/input_flow.py --device <serial> run scripts/input-flows/t2e-alt-lock.flow
```

`--package` selects another installed debug variant; the default is `rocks.pkb.plektra.nightly`. `--verbose` prints every executed operation. The runner needs a debug build, Plektra as the selected input method, an unlocked device and an awake screen.

## Flow format

One step per line; `#` starts a comment.

| Step | Effect |
| --- | --- |
| `hw type TEXT` | Presses the hardware keys that produce TEXT in the active layout and Alt binding |
| `hw press KEY` | Presses and releases one key, named by its key layout label (`Q`, `ALT_LEFT`, `SHIFT_RIGHT`, `AGUI_SYM`, `ENTER`, `DEL`) |
| `hw hold KEY` / `hw release KEY` | Keeps a key down across later steps, for chords and long presses |
| `screen type TEXT` | Taps the software keys for TEXT; uppercase letters tap `plektra_key_shift` first |
| `screen press ID` | Taps one target: a software key by accessibility id (`plektra_key_q`), the bar's emoji button (`bar:emoji`), the full emoji picker's search button (`picker:search`), a visible emoji picker cell (`emoji:👋`) or a skin tone in the open long-press popup (`variant:👋🏽`) |
| `screen hold ID` / `screen release ID` | Keeps a finger down, for example for a long press on an emoji cell; further touches use additional fingers |
| `wait MS` | Pauses, for example inside a hold for a long press |
| `expect TEXT` | Waits up to 3 s until the note body equals TEXT exactly |
| `preserve keyboard mode` | Leaves the keyboard mode unchanged when tapping picker or other panel controls |
| `require layout NAME` | Requires the loaded layout to match `app/src/main/assets/common/layouts/NAME.json` |
| `require profile ID` | Requires Plektra to resolve the virtual keyboard to device profile ID |

TEXT is either the rest of the line or a JSON string such as `"a\nb "` for newlines and surrounding spaces. Every flow needs at least one `expect`, and every `hold` needs a matching `release`.

`type` uses only real keys. The runner asks the debug app for the mapping Plektra resolves per key code (layout letters, Shift, Alt binding including user SYM settings) and picks the chord with the fewest modifiers. A character no key produces fails before the first key event, naming the character and its code point. When `type` repeats a multi-tap key (Greek `εε`), it waits out Plektra's multi-tap timeout (400 ms) so the second press starts a new character; with Bounce Keys enabled it also waits out the bounce delay for every repeated key of a chord whose category (character, modifier, Space, Enter, Backspace) is enabled, so repeated Alt chords such as `01` keep both Alt presses. The debug app reports the affected key codes through Plektra's own category mapping. Explicit `press` steps keep the runner's timing, so `hw press E` twice produces the second multi-tap character. Multi-tap layers, long-press alternatives and SYM pages are not typed implicitly; write them with `press`, `hold`, `wait` and `release`. `screen type` maps characters to accessibility ids the way `AospKeyboardView` names them and fails when a key is not on the visible page.

`plan` prints the intermediate operations as JSON lines: key edges with Linux scan codes, touch edges with accessibility ids, waits and expectations. It reads the mapping the input method has currently loaded, so focus a text field first. `run` plans again after it focused the new note.

## What `run` does

1. Checks the device identity, the selected input method, the lock screen and the screen state.
2. Registers the virtual devices (the keyboard clone only for flows with `hw` steps or `require profile`) with the `uinput` shell command. They disappear when the flow ends or fails; held keys and fingers are released first.
3. Opens `NotesActivity` through `NotesEditorAlias` and taps the new-note button. It continues only when the active note id is new and the note count grew by one, so a missed tap never types into an existing note. It then focuses the empty body. Test notes remain in the note list.
4. For screen steps, sets the temporary software-keyboard mode only if no temporary mode is active, and clears it afterwards. With `preserve keyboard mode`, it leaves the mode unchanged so hardware flows can tap picker cells without opening the software keyboard. Check the intended keyboard surface separately.
5. Executes the operations and compares the note body. The body text and focus come from the editor view inside the app (`KeyboardBarTestReceiver noteBody`): the UI hierarchy reports an empty field's hint as its text.

Cleanup releases held keys and fingers, removes the virtual devices and clears the temporary software-keyboard mode as independent steps. A failing step, such as a closed `uinput` pipe, does not skip the others. Cleanup failures are printed and fail the command only if the flow itself succeeded.

The runner changes no other settings. `require` steps fail with an explanation instead of switching layouts or profiles.

## Hardware keys

The virtual keyboard clones the built-in keyboard: name, vendor and product id, and exactly the key bits the kernel device reports. `devices` compares both devices:

- Plektra's view through `InputDevice`: name, ids, `isExternal`, `isVirtual`, keyboard type, sources and the resolved Plektra input profile.
- EventHub: classes, bus, key layout, key character map and configuration file.
- Kernel key bits, read with `getevent -p`.
- The key character map for every key code the device has, without modifiers, with Shift, Alt and Shift+Alt.

Only two differences are accepted, and only in their exact form: an additional `BATTERY` class with all other classes equal, and bus `0x0019` instead of `0x0000` with vendor, product and version equal (the `uinput` command accepts only named buses; `HOST` keeps the device internal). Any other difference fails the command. `devices --output FILE` stores the comparison and the built-in key character map.

[`evidence/titan2elite-input-devices.txt`](evidence/titan2elite-input-devices.txt) is the comparison for the Titan 2 Elite (`G72BoardV1`, `Titan 2 Elite_EEA_V02.00.02`, Android 16): Plektra resolves both keyboards to `titan2elite_qwerty`, `BUILT_IN`; both use `TitanKey.kl` and `TitanKey.kcm`; kernel key bits and the key character map for all 34 key codes match. Android derives key code and meta state from the scan code through the key layout file, and the Unicode character from the key character map, so equal files, key bits and maps give equal mappings. The built-in keyboard reports Shift as scan code 54 (`SHIFT_RIGHT`); scan code 42 appears in `TitanKey.kl` but not in the kernel key bits, so flows use `SHIFT_RIGHT`.

Not yet recorded: `KeyEvent`s of physical presses next to the corresponding virtual ones (scan code, key code, meta state, Unicode character and Plektra's output, especially for `SHIFT_RIGHT`, `ALT_LEFT` and `AGUI_SYM`). Physical presses need a person at the device.

The clone is not the same device instance: it has its own device id and descriptor, and Android tracks meta state per device. Mixing a real key press with a virtual one in one chord is not covered. Keyboards whose layout differs per device or that are resolved through Bluetooth or USB identity (Clicks) need an equivalent `devices` comparison before flows count as evidence for them.

## Software keyboard

Screen steps use a virtual multi-touch screen (`INPUT_PROP_DIRECT`), so touches pass Android's touch pipeline and `AospKeyboardView.onTouchEvent`, including second fingers for chords. Key positions come from Plektra's accessibility key nodes (`plektra_key_*` ids with bounds), queried through `KeyboardBarTestReceiver inspect` before each touch. The same query reports the emoji button of the keyboard bar, the emoji picker's visible cells by their shown text and the cells of the skin-tone popup; a long press is `screen hold`, `wait` and `screen release` on a cell.

Screen-only flows need no built-in keyboard and run on phones and foldables without one, such as the Galaxy Z Fold6 (`SM-F956B`): `devices` stops there because no built-in alphabetic keyboard exists to compare. Screen-only `run` flows do not need that hardware comparison. On a foldable the touches go to the active internal display (`displayId=0`): the outer display when folded, the inner one when unfolded. [`fold6-emoji-skin-tones.flow`](../../scripts/input-flows/fold6-emoji-skin-tones.flow) needs 👋 visible when the picker opens. Coordinates are mapped from the logical display frame to the raw touch range of the internal viewport, which accounts for a `wm size` override.

The accessibility action `ACTION_CLICK` is a separate code path (`performAccessibilityKeyClick`) without holds, gestures or multi-touch; these flows do not exercise it.

## UI selectors for other device tests

Use the installed variant’s package name with these Android resource IDs, for example `rocks.pkb.plektra.nightly:id/plektra_toolbar_emoji`. The keyboard and picker controls are present when their corresponding surfaces are visible.

| Resource ID | Control |
| --- | --- |
| `notes_new` | Create a test note |
| `notes_heading`, `notes_body` | Note heading and body |
| `plektra_key_*` | Virtual accessibility nodes for the visible software keys |
| `plektra_toolbar_emoji` | Open the full emoji picker from the keyboard toolbar, when that button is configured |
| `plektra_emoji_search_toggle` | Show or hide the search field in the full IME emoji picker |
| `plektra_emoji_search_field` | Search field in the full IME emoji picker |

The emoji controls keep their localized accessibility labels. Tests can select them by resource ID across languages.

## Limits

- Only the Notes editor is a target. Paths that depend on the target app, such as the Google calculator's key-event output, need their own device check.
- `expect` compares the whole body. Auto-capitalization, auto-correction and auto-spacing are part of the result and must be written into the expectation.
- Timing follows the runner, not human typing: 40 ms per key press and between keys. Use `wait` for long presses, double taps and repeat behaviour.
- The runner cannot tell which Plektra language layout is selected beyond comparing the loaded mapping with bundled layout files.
