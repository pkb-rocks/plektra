# App appearance

This branch adds optional application UI colours to the official base 5ff5615e42c1b3292a6737f5603e3cc9c7f60e7f. It is independent of the integration launcher's code and of typography PR158.

## Behaviour

Advanced settings > App appearance offers presets, separate main/selected-item colours and a preview. Custom colours are disabled by default. Changing a preset or custom colour enables the custom palette; Use system colours resets only these three preferences. The system dark/light mode and official dynamic-colour selection still choose the underlying scheme. Keyboard photo themes retain their separate settings.

Selected-item colour maps to Material's secondary container; which controls use that role follows the existing component theme. This is not a promise that every third-party or system screen changes colour. Major foreground colours are chosen for contrast; the complete set of component states still needs visual review before any accessibility claim.

The feature reuses the existing keyboard-theme colour picker through a small internal visibility change. Its resources have feature-specific names, including the selected-preview label, so it does not import launcher strings. Eleven resource sets are included. A dedicated settings-link entry opens the appearance screen, and ordinary typed ZIP export/import includes BOOLEAN enabled and INT main/selection values.

## Validation status

Initial focused checks passed (41 cases), and the debug APK and Android lint completed successfully. The subsequent full Windows run executed 1,107 tests with 46 failures. An unchanged official checkout at the exact same base executed 1,101 tests with the same 46 failing methods and no additional failures. These include unavailable Robolectric Windows native graphics, Windows symlink privileges and existing SQLite connection-pointer failures. No existing test was disabled or weakened. This is not a claim that the full local suite is green; the official Linux CI still needs to run on the eventual PR.

After that comparison, the saved prototype route ID `advanced.appearance` was retained and a regression for that URI was added. After this last adjustment, all 42 focused tests passed, along with `:app:lintStableDebug` and `:app:assembleStableDebug`. The source includes 72 combinations of light/dark mode and extreme/saturated main/selection colours with 18 foreground/background pairs per combination. This numerical coverage does not validate every disabled, blended or rendered component state.

A separate review installation on a Titan 2 running Android 16 was checked using the actual production settings screen in English. The recording shows the disabled default, blue and purple presets with persistent selection, an independently chosen orange selected-item colour, and reset to system colours. These checks cover the light-mode layout shown; dark-mode and additional font-scale device runs are not claimed. The 31-second recording and its screenshot are awaiting the user's approval before upload.

Focused checks to run:

```sh
./gradlew :app:testStableDebugUnitTest --tests '*AppAppearanceTest' --tests '*AppAppearanceBackupTest' --tests '*SettingLink*Test' :app:assembleStableDebug
./gradlew :app:lintStableDebug
```

The focused tests cover unchanged system palette, opacity and reset isolation, independent selected-state colour, extreme/saturated colours in light/dark mode, malformed imported types, real ZIP export-clear-restore and settings-link coverage.

## Remaining review

- Review all overridden Material colour pairs, disabled controls, outlines and large-font/long-label layouts.
- Device recording source: `99dcc530dcc0f0c5583f57a1706ff0beb97caea9`; isolated package `rocks.pkb.plektra.review.appearance`; APK SHA-256 `63ce64d3d30d8f591298909d33b47023374e4955a3ce0d4d0c618153482df284`. Documentation-only follow-ups do not change the recorded implementation.
- The everyday IME and accessibility configuration were preserved. The final clip excludes status/navigation areas, contains no audio or inherited recording/device metadata, and was checked across all output frames. Obtain user approval before uploading it.
