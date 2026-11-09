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
| `screen press ID` | Taps one software key by accessibility id, for example `plektra_key_q` |
| `screen hold ID` / `screen release ID` | Keeps a finger down; further touches use additional fingers |
| `wait MS` | Pauses, for example inside a hold for a long press |
| `expect TEXT` | Waits up to 3 s until the note body equals TEXT exactly |
| `require layout NAME` | Requires the loaded layout to match `app/src/main/assets/common/layouts/NAME.json` |
| `require profile ID` | Requires Plektra to resolve the virtual keyboard to device profile ID |

TEXT is either the rest of the line or a JSON string such as `"a\nb "` for newlines and surrounding spaces. Every flow needs at least one `expect`, and every `hold` needs a matching `release`.

`type` uses only real keys. The runner asks the debug app for the mapping Plektra resolves per key code (layout letters, Shift, Alt binding including user SYM settings) and picks the chord with the fewest modifiers. A character no key produces fails before the first key event, naming the character and its code point. Multi-tap layers, long-press alternatives and SYM pages are not typed implicitly; write them with `press`, `hold`, `wait` and `release`. `screen type` maps characters to accessibility ids the way `AospKeyboardView` names them and fails when a key is not on the visible page.

`plan` prints the intermediate operations as JSON lines: key edges with Linux scan codes, touch edges with accessibility ids, waits and expectations. It reads the mapping the input method has currently loaded, so focus a text field first. `run` plans again after it focused the new note.

## What `run` does

1. Checks the device identity, the selected input method, the lock screen and the screen state.
2. Registers the virtual devices with the `uinput` shell command. They disappear when the flow ends or fails; held keys and fingers are released first.
3. Opens `NotesActivity` through `NotesEditorAlias`, taps the new-note button and focuses the empty body. Existing notes stay untouched. Test notes remain in the note list.
4. For screen steps, sets the temporary software-keyboard mode only if no temporary mode is active, and clears it afterwards.
5. Executes the operations and compares the note body read from the UI hierarchy.

The runner changes no other settings. `require` steps fail with an explanation instead of switching layouts or profiles.

## Hardware keys

The virtual keyboard clones the built-in keyboard: name, vendor and product id, and exactly the key bits the kernel device reports. Android therefore selects the same key layout and key character map files, and the events carry the same scan codes, key codes and meta states. `devices` shows the comparison:

- Plektra's view through `InputDevice`: name, ids, `isExternal`, `isVirtual`, keyboard type, sources and the resolved Plektra input profile.
- EventHub: classes, bus, key layout, key character map and configuration file.
- Kernel key bits, read with `getevent -p`.
- The key character map for every key code the device has, without modifiers, with Shift, Alt and Shift+Alt.

On the Titan 2 Elite (`G72BoardV1`, `Titan 2 Elite_EEA_V02.00.02`, Android 16) all of these match. The only differences are the bus (`0x19` instead of `0x00`, because the `uinput` command accepts only named buses) and an additional EventHub `BATTERY` class on the virtual node; neither enters key mapping. Plektra resolves both keyboards to `titan2elite_qwerty`, `BUILT_IN`. The built-in keyboard reports Shift as scan code 54 (`SHIFT_RIGHT`); scan code 42 appears in `TitanKey.kl` but not in the kernel key bits, so flows must use `SHIFT_RIGHT`.

The clone is not the same device instance: it has its own device id and descriptor, and Android tracks meta state per device. Mixing a real key press with a virtual one in one chord is not covered. Keyboards whose layout differs per device or that are resolved through Bluetooth or USB identity (Clicks) need an equivalent `devices` comparison before flows count as evidence for them.

## Software keyboard

Screen steps use a virtual multi-touch screen (`INPUT_PROP_DIRECT`), so touches pass Android's touch pipeline and `AospKeyboardView.onTouchEvent`, including second fingers for chords. Key positions come from Plektra's accessibility key nodes (`plektra_key_*` ids with bounds), queried through `KeyboardBarTestReceiver inspect` before each touch. Coordinates are mapped from the logical display frame to the raw touch range of the internal viewport, which accounts for a `wm size` override.

The accessibility action `ACTION_CLICK` is a separate code path (`performAccessibilityKeyClick`) without holds, gestures or multi-touch; these flows do not exercise it.

## Limits

- Only the Notes editor is a target. Paths that depend on the target app, such as the Google calculator's key-event output, need their own device check.
- `expect` compares the whole body. Auto-capitalization, auto-correction and auto-spacing are part of the result and must be written into the expectation.
- Timing follows the runner, not human typing: 40 ms per key press and between keys. Use `wait` for long presses, double taps and repeat behaviour.
- The runner cannot tell which Plektra language layout is selected beyond comparing the loaded mapping with bundled layout files.
