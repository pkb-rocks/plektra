# Daily rotation of saved keyboard themes

Choose **Daily rotation** in a keyboard target’s theme-assignment settings, select saved themes in the desired order and press **Save**. The first theme is used on that local calendar day; subsequent days advance through the sequence. Selection edits are drafts until Save. Saving a changed selection restarts its cycle today; changing between Fixed/System/Daily alone preserves the stored cycle.

Hardware and software targets have independent sequences and anchors. One selected theme stays unchanged; removed themes are skipped and an empty selection falls back to the target’s fixed theme. Existing language/layout overrides retain priority over the global cycle. This PR uses existing solid-theme support and is independent of photo backgrounds. Scoped cycles and text-style controls are separate contributions.

## Runtime and backup

The cycle is calculated from local epoch-day dates, not application launches or elapsed 24-hour intervals. Its next refresh is the next local midnight, including DST, and date/time/timezone broadcasts reschedule an active session. Hardware-only input sessions are covered. Finishing input cancels the callback; destroying the service unregisters the receiver. No alarm, background worker, wake lock or new permission is introduced. Applied theme preferences invalidate the keyboard’s render snapshot; draft preferences remain excluded.

ZIP preserves the existing saved-theme library and assignment mode plus four typed, prototype-compatible preferences:

- `keyboard_theme_rotation_names_hardware` and `keyboard_theme_rotation_names_software`: JSON string sequences.
- `keyboard_theme_rotation_anchor_hardware` and `keyboard_theme_rotation_anchor_software`: long calendar-day anchors.

Names are canonicalized against the saved library, duplicate slots are removed, malformed/oversized selections are bounded, and arithmetic remains safe for extreme imported anchors. Restore preserves both order and anchor. No images or learning datasets are added by this feature.

## Validation

75 distinct targeted checks pass: 13 cycle/persistence tests, 2 scheduler lifecycle tests, 19 existing assignment contracts and 41 existing backup/restore contracts. Coverage includes deleted themes, independent targets, malformed data, name aliases, leap days, year changes, extreme anchors, override priority, both DST transitions and an actual ZIP export/restore. The assignment suite was rerun after preserving its draft-is-not-active contract; the other passing suites were unchanged. `assembleStableDebug`, final `lintStableDebug`, signature verification and whitespace checks succeed.

Reproduction:

```sh
./gradlew :app:testStableDebugUnitTest --tests '*DailyThemeRotationTest' --tests '*DailyThemeRotationSchedulerTest' --tests '*SettingsManagerKeyboardThemeAssignmentTest' --tests '*BackupPreferenceContractIntegrationTest' --tests '*RestoreManagerAndBackupContractTest' :app:lintStableDebug :app:assembleStableDebug
```

On Titan2/Android16, an isolated review application (`rocks.pkb.plektra.review.cycle`) and Sample One verified real UI selection, Save feedback, persisted order **Ocean → Clay → Moss**, and application of the first color. A review-only anchor shift of −1 day then showed the next color through the real keyboard after process restart. The phone’s date/time was never changed; this is an explicitly labeled next-day fixture, not a recording of a real midnight transition. No physical-key matrix behavior is claimed.

The original six IME/accessibility settings and heads-up presentation were restored exactly. The 26.33-second English recording contains only synthetic settings and the empty fictional editor, with its status bar cropped, no audio and metadata removed. Every one of its 1,081 native source frames was compared; all 41 representative visual states were visually reviewed. The user approved this exact sanitized recording for GitHub on 2026-10-29. Its SHA-256 is `2da352e609e00518d65760e6ae3ebba941b5fc87077470371743ea33a93c89fd`.
