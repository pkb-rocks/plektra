English | [Deutsch](device-facts.md)

# Unihertz Titan 2 Elite: device facts for Plektra developers

This page collects concrete facts about the Unihertz Titan 2 Elite that were observed on a real device while testing Plektra. It complements the [README](README.md) of this directory, which describes the display geometry and device detection.

## Provenance

- All observations come from one retail Titan 2 Elite with a QWERTY layout, tested on 2026-11-14 with logcat, `getevent -lt`, `dumpsys`, `getprop` and `settings`, plus a small key-probe test app and the app's own debug logging.
- Firmware: `Titan 2 Elite_V02.00.06` (`ro.build.display.id`), Android 16, security patch 2026-09-05. Vendor behavior can change with firmware, so treat every fact as "seen on this build on this date".
- Each fact says how it was established. "Inferred" means derived from paired observations, not read from a specification.

## 1. Physical keys and input devices

1. Keycaps and scan codes (kernel scan codes as shown by `getevent -lt`, 2026-11-14):

   | Printed key | Position | Scan code | Notes |
   | --- | --- | --- | --- |
   | Enter (⏎) | | 28 | |
   | Shift (⇧), right | right side of the keyboard | 54 | |
   | Shift (⇧), left | bottom-left corner | 42 | |
   | Fn | | 251 | `FUNC3` |
   | Sym | | 253 | `AGUI_SYM` |
   | ○ (circle) | | 102 | `KEY_HOME`; the vendor layer sees keycode 407, see section 3.6 |
   | Back | | 158 | the printed △ and □ keys are by convention Back and Recent; this was not verified key by key |
   | Recent | | 580 | arrives raw as `KEY_APPSELECT`; the vendor layer turns it into Tab (keycode 61) above the kernel, it is not on the keyboard device |
   | Orange side key | side of the phone | 249 | event device `ff_key`; the vendor settings call it "Func1" |
   | Volume | | 115 / 114 | `gpio-keys` |
   | Power | | 116 | `ff_key` |

2. Input devices (`getevent -i`): event5 "TitanKey" (the keys), event4 "touchPad" (the keyboard's touch surface), event6 "fts_ts" (the screen), event7 "ff_key", event0 "gpio-keys" (volume), event1 "mtk-pmic-keys", event3 "hall_sensor". Resting a finger on the keys produces `BTN_TOUCH` on the touchPad device.
3. Space never appears on the keyboard input device in `getevent`. The Space bar is also the fingerprint sensor (vendor package `com.agui.spacebarkey`). Outside a text field, pressing it left the foreground app (Calculator) and showed the launcher page the app was started from; this is more than Back does. Do not use Space as a control key in tests outside text fields.
4. Synthetic touches. During a longer key sequence, `adb shell input tap` on the screen sometimes did not register (three failed tries) while the touchPad device was reporting touches from resting fingers. Lifting the fingers between key presses and retrying (up to 15 times at 0.5 s) fixed it (18 of 18 taps). The cause was not proven.
5. Timestamps. `getevent -lt` prints `CLOCK_MONOTONIC` time. Converting it with `/proc/uptime` (`CLOCK_BOOTTIME`) drifted by about 11 s on this phone. To line key events up with other log events, use the vendor `InputReader` lines in logcat instead.

## 2. Vendor key layer (`com.agui.shortcutsettings`)

The vendor settings app (Settings → Shortcut keys → Key setting) rewrites keys in the firmware before any app, and before the IME, sees them.

1. Where it is stored. What a key now types is in the system property `persist.sys.agui_programmable_<key>_key` (read with `getprop`; the value is an Android keycode). The function chosen in the menu is in `Settings.System` as `<key>_programmable_key_function` (some keys also have `_enable`). A key whose property is absent is presumably native; this was not checked on an untouched factory key.
2. Changes take effect at once. Changing a key in the vendor screen updates the property and the `_function` value immediately, with no reboot (checked one key at a time with `getprop`, 2026-11-14).
3. Function codes (inferred 2026-11-14 by pairing each key's `_function` value with the keycode it typed):

   | `_function` | Menu entry | Keycode typed |
   | --- | --- | --- |
   | 1 | Ctrl key | 113 `CTRL_LEFT` |
   | 2 | Sym key | 63 `SYM` |
   | 3 | Back key | not recorded |
   | 4 | Media key | 85 `MEDIA_PLAY_PAUSE` |
   | 5 | Tab key | 61 `TAB` |
   | 6 | Home key | not recorded |
   | 7 | Shift key | 59 `SHIFT_LEFT` |
   | 8 | Alt key | 58 `ALT_RIGHT` |

   Other menu entries exist whose codes are unknown: None, Magic key, Meta key, Power key, Comma, Period, Number 0, Letter key. The menu list comes from the `edit_key_*` strings in the vendor APK (`aapt2 dump`).
4. The assigned function decides the keycode, not the physical key. "Alt key" always types `ALT_RIGHT` (58) and "Shift key" always types `SHIFT_LEFT` (59), whichever physical key it is assigned to. In particular the right Shift key, when assigned "Shift key", arrives as `KEYCODE_SHIFT_LEFT` with scan code 54. The scan code survives the rewrite, so an app that needs to tell the keys apart can match on it.
5. Keys the vendor screen lists: left Shift, right Shift, left Alt, right Alt (the string exists), Fn, Sym, Func1, Func2, Home, Back, Recent, Space, PTT, Camping lamp, Subscreen control. Not all of them exist on this phone.
6. Home (○) goes through the vendor shortcut layer (`A85ShortcutFunction` in `com.agui.shortcutsettings`), not straight to Android. A short press is Home, a long press (about 0.5 s) is Recents (`KEYCODE_APP_SWITCH`), a double press is Back. Because of the double-press window, Home is injected only after it closes, about 0.5 to 0.7 s after ○ is released (observed 2026-11-14).
7. For Plektra the practical consequence: the vendor layer decides what the IME receives. The README tutorial advice to let Fn act as Ctrl is done in this layer ("Ctrl key", function 1), not in the app.

## 3. Keyboard backlight and configuration changes

This is the most surprising behavior found on the device, and it affects any test that depends on focus.

1. **A key that wakes the keyboard backlight changes the configuration and relaunches the foreground activity.** Evidence from logcat (main, system, events) for one press of the right Shift key in the Calculator's "Choose theme" dialog:
   1. `InputReader` logs `aguiSetProgrammableKey` for scan code 54 (rewritten to 59).
   2. 9 ms later `KeyboardLightController: pressKeyToPowerOn`.
   3. 30 ms after that `ActivityTaskManager: Config changes=30` (`keyboard | keyboardHidden`).
   4. Then `wm_relaunch_resume_activity … Calculator,30`.
2. The relaunched activity rebuilds its dialog, so focus goes back to the dialog's default item. An app that does not declare `configChanges="keyboard|keyboardHidden"` loses its focus and dialog state this way. An IME sees the same event as `onConfigurationChanged` with diff 0x30. Adding an input device (for example a uinput test keyboard) does the same.
3. It also works the other way. A press of the Recent key (vendor Tab, scan code 580, not on the keyboard device) was followed within 40 ms by `Config changes=30` that switched to `-keyb/v/h` (keyboard NOKEYS, hardware keyboard hidden) and another relaunch. The next right-Shift press switched it back to `qwerty/v/v` and relaunched again. Alternating the Recent key with keyboard keys can therefore relaunch the app on every press. Waking the keyboard first does not avoid this.
4. A first test in the stock Calculator looked like a focus bug: with Cancel focused (reached with Tab), the right Shift key and Enter each moved focus back to the first item instead of activating Cancel. The IME was not involved (`dumpsys input_method`: `mServedView=null`, `mInputShown=false`). The cause was the relaunch described above.
5. Testing advice. Keyboard-focus tests need an app that handles `keyboard|keyboardHidden` itself. `dumpsys package` does not show `configChanges` on this phone: pull the APK (`adb pull` from `pm path <pkg>`), read `android:configChanges` with `aapt2 dump xmltree --file AndroidManifest.xml`, and check that bits 0x30 are set. A third-party editor whose main activity declares `configChanges` 0xdb0 (which includes both bits) still sees the `Config changes=30` flips on Recent and on backlight-waking presses, but it is not relaunched, only the launcher is. The stock apps tried (Calculator, Keep, Clock, Contacts, Dialer, Files) had no config-safe activity; stock Settings has a few (homepage, Wi-Fi and network dialogs). The dialog test in section 4.1 was done this way.

## 4. IME and editor behavior

1. **Text-less dialogs bypass the IME.** An AlertDialog with no text-input view sets `FLAG_ALT_FOCUSABLE_IM` (AOSP `AlertController`), so its window has no IME focus. `ImeFocusController` then passes keys straight to the app (`dumpsys input_method` shows `mServedView=null`; the `fallbackInputConnection` listed there is only the client's registered fallback, not an IME session). This is Android design, not a phone setting. Observed 2026-11-14 in a third-party editor's radio-list dialog (Cancel/OK, no text field), with Cancel focused: a key that an IME-level remap would turn into Enter did nothing. The vendor layer had delivered it as `KEYCODE_SHIFT_LEFT` (scan code 54) and the IME logged nothing for it.
   Consequence: **any IME-level key handling (remaps, shortcuts, trackpad gestures) cannot act in such dialogs.** The key acts as whatever the vendor layer maps it to. Acting there would need an AccessibilityService with key filtering (consume) plus injection, as Key Mapper does.
2. **IME startup is not limited to text editors on this phone.** `config_preventImeStartupUnlessTextEditor` resolves to `false` (`adb shell cmd overlay lookup android android:bool/config_preventImeStartupUnlessTextEditor`, with overlays applied). Android therefore keeps the IME session in ordinary non-text windows; only windows flagged `FLAG_ALT_FOCUSABLE_IM` (text-less AlertDialogs, list popups) bypass the IME. Established 2026-11-14.
3. Modifier taps (observed with real keys, default settings, 2026-11-14): a quick tap of Alt arms a one-shot Alt in the app. A blue rounded line appears at the left screen edge (most likely the app's modifier indicator), the next letter types its Alt symbol (`a` becomes `*` on the QWERTY layout), and the line disappears. If Alt is held over a quick tap of Shift and then released, the result is the same as an Alt tap, because the Shift tap counts as a modifier press.

## 5. Held keys and `restartInput`

Findings from tests with a key-probe app that forces `restartInput` on demand, and from focus changes (2026-11-14). They are described as Android and device behavior; the app-side handling was tested with real keys on this phone.

1. A key released between the editor's `restartInput` and the IME's `onStartInput(restarting=true)` has its release delivered to an inactive connection. The app log then shows `sendKeyEvent on inactive InputConnection`. On this phone that gap lies about 150 to 160 ms after the press for the probe's 100 ms restart delay (a tap of about 100 ms releases before the restart, one of about 260 ms after the start). Hitting the gap by hand is unreliable: use many presses (10 to 15) and pick out the ones that landed in the gap from logcat and `getevent -lt`.
2. Without handling, the probe saw the key as held for good (`held: A=[SHIFT_RIGHT]`). An IME that tracks held modifiers therefore has to re-send a canceled `UP` through the new connection. With that handling the same warning appears, the canceled `UP` is delivered through the new connection, and the probe shows nothing held.
3. When the connection is replaced while a Shift key is held, the app sees that Shift as `UP … CANCELED` (flags 0x24). The IME's own Shift state stayed intact. In a roll (key A down, key B down, A up, B up) across a `restartInput`, 7 of 7 valid rolls ended with nothing held; the `UP` of the non-modifier key arrived with meta state 0x81, not canceled.
4. A held Shift key and a pulled-down notification shade: the app gets `UP SHIFT_RIGHT … CANCELED`, nothing stays held, and the next keys type normally.
5. A held key while pressing ○ Home, then released on the launcher: no Plektra shortcut sheet appeared, and after returning to the app the next letter was lower case.
6. A held key (repeating new lines) while switching to another app with the recents gesture, then released: the other app (Chrome, which must already be in recents) received nothing, and the next letter typed lower case.

## 6. Logging

1. The system property `log.tag` is `I` on this phone. Every app `Log.d` is therefore dropped at write time and never reaches logcat, including the app's own key-delivery trace and any debug logging in test apps.
2. Before a capture, raise the level per tag. The setting lasts until reboot:

   ```bash
   adb shell setprop log.tag.<YourTag> D
   ```

   Plektra's input method service logs under the tag `PlektraInputMethod`.
3. For timing work, the vendor `InputReader` lines in logcat are more reliable than converting `getevent` timestamps (section 1.5).

## 7. App storage on the device

All paths are for the app's own data directory. Debug builds are debuggable, so `run-as` works:

```bash
adb exec-out run-as rocks.pkb.plektra cat shared_prefs/plektra_prefs.xml
adb shell run-as rocks.pkb.plektra sh -c 'cd files && find .'
```

Use the package name of the build under test (the official application ID is `rocks.pkb.plektra`; the IME service is `rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService`, see `settings get secure default_input_method`). Never `am force-stop` the app or start its activity with `am start -S` while the IME is selected: that kills the IME and Android switches to another keyboard.

1. `shared_prefs/plektra_prefs.xml` is the main preferences file; `shared_prefs/recent_emojis_prefs.xml` holds recent emoji. The device-protected copy for direct boot lives in `/data/user_de/0/<package>/files/direct_boot_files/`: the file `current` names the published generation, which holds `settings.json` with the allowlisted layout and input settings and `files/` with the functional parts of custom layouts, variations, nav mode and locale-to-layout mappings.
2. `files/` holds `ctrl_key_mappings.json` (Ctrl navigation mappings), `user_defaults.json` (the user word list), `dictionaries_serialized/custom/`, `keyboard_layouts/` and `theme-images/` (created by a restore; may be empty).
3. Backups are ZIP files named `plektra-backup-YYYYMMDD-HHMM.zip`. On the test phone they were saved to `/storage/emulated/0/Documents/Plektra/`; the folder is whatever the file picker was given. The ZIP contains `prefs/plektra_prefs.json`, `files/*`, `theme-images/manifest.json` and `backup_meta.json`.

## 8. Open questions

1. The factory default of each vendor key mapping is not recorded. In particular, whether a key with no `persist.sys.agui_programmable_*` property is native should be confirmed on a factory-reset unit.
2. The function codes for Back, Home and the unnamed menu entries (section 2.3) are unknown.
3. The cause of the failed synthetic taps (section 1.4) is not proven.
4. Behavior on other firmware builds is untested. If you can repeat a fact on a different build, add the build and date next to it.
