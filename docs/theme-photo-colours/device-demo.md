# Synthetic on-device colour-sampling check

Code tested: `c8b7b1f`, isolated `rocks.pkb.plektra.review.photo` APK. The application label and APK checksum are recorded in validation.json.

A generated 800×500 PNG contains four solid panels: blue `#1F5C8F`, orange `#CE7146`, green `#657D4F` and yellow `#E3C67A`. It was selected through Android’s photo picker. No personal photo was selected.

The recording starts after image selection. ADB touch events sample orange, then blue, confirm the colour and apply it in the existing theme picker. UI labels show both expected hexadecimal colours; a read-only check of the review package’s theme preferences confirms background `0xFF1F5C8F`. The final view shows the blue keyboard-theme preview. This is a device UI test, not a physical-key test.

The current/default keyboard and enabled keyboard/accessibility settings were unchanged. Only the isolated review theme was edited.

The English recording removes the Android status bar, audio and source metadata. All 116 native frames were reviewed in four contact sheets. It contains only the synthetic image and review settings. A three-second hold repeats the final verified preview for readability. The full-system photo-picker screen is excluded.

The exact video checksum and approval status are recorded in validation.json. Do not upload the raw device recording. The user explicitly approved this exact sanitized recording for GitHub publication. Upload is pending.
