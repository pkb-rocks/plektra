# Keyboard-bar text size

This contribution is extracted from the integration prototype onto upstream `5ff5615e42c1b3292a6737f5603e3cc9c7f60e7f` (23 October 2026). It contains no launcher or photo-theme dependencies.

## Behaviour

Open Settings → Keyboard bar → Contents. The text-size card changes the preferred size of suggestions, variations and punctuation from 85% to 125%, in 5% steps. The default is 100%; Default size resets it. The reference preview remains at 100% while the second row follows the slider. Both rows use the production suggestions renderer and dragging the slider does not save intermediate preview values.

The percentage applies once to Android's original pixel ceiling, including the system font setting, rather than multiplying nonlinear SP values. Autosizing retains the existing minimum and row geometry. Long candidates still shrink to fit; the chosen percentage is a preferred maximum, not a guarantee that every word grows. Existing upstream overflow behaviour is not redesigned here. The 1-pixel ladder includes the requested maximum and can choose a slightly different intermediate size at 100% than the previous coarse SP ladder.

The setting is recognized by the typed preferences schema, so ordinary ZIP export/import carries it without a new archive format. The ZIP test exports, clears the preference and restores it.

## Reproduce automated checks

```sh
./gradlew :app:testStableDebugUnitTest --tests '*KeyboardBarTypographyTest' --tests '*KeyboardTextScaleVisualTest' --tests '*KeyboardTextScaleBackupTest' :app:assembleStableDebug
```

Robolectric checks preference bounds, refreshed ceilings when the candidates do not change, whole candidate layouts, preview isolation and ZIP restoration. Its legacy graphics are not evidence of actual glyph-size ratios; use the device recording and native measurements for that.

## Device capture

A debug-only, shell-permission-protected activity displays synthetic content with the actual settings preview widget. It cycles 85%, 100%, 125% without saving preferences. It is excluded from release APKs.

```sh
adb shell am start -W -n <debug-application-id>/rocks.pkb.plektra.TypographyPreviewActivity
adb shell screenrecord --time-limit 20 /data/local/tmp/keyboard-text-size.mp4
adb pull /data/local/tmp/keyboard-text-size.mp4
```

For this review APK, a local Gradle initialization script adds `.review.textsize` to the debug application ID, allowing it to coexist with everyday keyboards. That script also selects the local debug keystore and offline test dependencies; none of those machine paths or signing keys belongs to the PR. No keyboard activation or accessibility permission is needed to view the synthetic comparison.

## Validation on 23 October 2026

Source commit: `809e7351a29c5b8e0bbaf13b58ccd12339cb3363`. APK SHA-256: `2e1bfdd2e27e8ea22248a807d5510fe453025aa95b1e0b54acd50515a38b8e02`.

- Seven focused tests passed (4 typography/render refresh, 2 preview/pixel configuration, 1 ZIP export-clear-restore). No failures or skipped tests. The full suite was not rerun.
- Native Android 16/API 36, Titan 2, 1076px width, density 1.84375, system font scale 1.3. Twenty-four synthetic candidates were measured at 85%, 100%, 110%, 125% with the installed production renderer.
- Short samples measured 29px, 34px, 37px, 43px respectively. All tested candidates retained their complete text with zero ellipsis and no measured horizontal overflow. A long word remained at 27px to fit rather than growing beyond its slot.
- A 20-second real-device recording shows the actual comparison widget. System bars and unused screen space were cropped; no text rendering or size transitions were simulated in editing. Audio and inherited metadata were removed.
- The author approved the cropped recording and its extracted screenshot after privacy review. These are supplied as pull-request attachments, rather than binary assets in the source tree.
- Diagnostic applications were removed afterwards. Everyday IME settings and existing prototype applications were not changed.

## CI follow-up

The first CI run found one failure in 1,108 stable-debug unit tests: the new UI hook was missing from the settings-link registry. The control is now registered with its title, summary and Keyboard bar destination. The coverage check remains enabled.

After the fix, all 42 typography, preview, ZIP and settings-link tests passed locally, and `:app:lintStableDebug` passed. The debug capture activity also guards its inset API for Android 10/API 29. These follow-up changes do not change the Android 16 typography rendering shown in the approved recording. The full CI workflow must be rerun on the updated branch.
