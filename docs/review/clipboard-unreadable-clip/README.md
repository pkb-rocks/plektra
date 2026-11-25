# A clip that can't be read is skipped

The clipboard history reads every copy as it happens, in the keyboard's process. A copy whose content can't be read (the app that copied it denies access to its content, or the clipboard service fails) is now skipped and logged instead of ending the keyboard. Ported from Flux Keyboard's stability pass onto upstream `68ace8ab` (10 November 2026).

## Behaviour

- That copy isn't added to the history; the next one is, as usual. Nothing else changes.
- The history's own storage failures were already handled in Plektra (a failed load shows its error state, and each failed save, pin, delete or clear has its own message), so Flux Keyboard's storage part isn't needed here.

## Implementation boundaries

- `ClipboardHistoryManager.fetchPrimaryClip` runs the former body (now `readPrimaryClip`) and catches a runtime failure from reading the clip.
- No setting, string, permission or migration.

## Reproduce automated checks

```sh
LC_ALL=C.UTF-8 ./gradlew :app:testStableDebugUnitTest --tests '*ClipboardUnreadableClipTest'
```

`ClipboardUnreadableClipTest` (in `data-backup`, with the clipboard history tests): a clipboard that fails to read skipped, and a clip whose content fails skipped with the next copy kept.

## Validation

Run on 14 November 2026 against source commit `4370b807` (upstream `68ace8ab` plus this change), with a UTF-8 locale as on CI:

- Full `testStableDebugUnitTest`: 186 classes, 1,419 tests, no failures, errors or skips (upstream alone: 185 classes, 1,417 tests). Of these, 2 are new, in `ClipboardUnreadableClipTest`.
- `python3 scripts/ci-tests.py check` and the Python guard tests pass; the new class is in the `data-backup` topic.
- `lintStableDebug` then `scripts/check-lint.py`: 1,056 errors, no new errors against `ci/lint-errors.json`.

## Device check (still to do)

There is no reliable way to make a copy unreadable on purpose; the check is that copying text from a few apps still adds it to the history as before.
