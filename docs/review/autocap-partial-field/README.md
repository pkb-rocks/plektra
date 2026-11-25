# Automatic Shift in long fields an app hands over in part

An app may hand the keyboard only part of a long field: `ExtractedText.startOffset` is then above zero and the selection is relative to that part. When the part starts at the cursor (`selectionStart == 0`), the empty text before it was read as the start of the field, so Shift was armed in the middle of a sentence. Ported from Flux Keyboard onto upstream `68ace8ab` (10 November 2026).

## Behaviour

In that case `AutoCapitalizeHelper.readContext()` now asks for the text before the cursor (`getTextBeforeCursor`), as it already does when no extract is available. Mid-sentence there is no capital; after a full stop there still is. A whole field with the cursor at its start is unchanged.

## Implementation boundaries

- One condition in `readContext()`, the shared reader behind every automatic Shift decision. No change for apps that hand over the whole field.
- No preference, string or resource changes.

## Reproduce automated checks

```sh
LC_ALL=C.UTF-8 ./gradlew :app:testStableDebugUnitTest --tests '*AutoCapitalizeHelperPartialFieldTest'
```

`AutoCapitalizeHelperPartialFieldTest` gives `shouldAutoCapitalizeAtCursor` an editor whose extract starts at the cursor 5,000 characters into the field: mid-sentence (no capital), after a full stop (capital), and the whole-field control (capital).

## Validation

Run on 11 November 2026 against source commit `4968cd83` (upstream `68ace8ab` plus this change), with a UTF-8 locale as on CI:

- Full `testStableDebugUnitTest`: 186 classes, 1,420 tests, no failures, errors or skips (upstream alone: 185 classes, 1,417 tests). New: 3 in `AutoCapitalizeHelperPartialFieldTest`.
- The new tests on upstream alone, without the fix: `partOfALongFieldStartingAtTheCursorIsNotTheStartOfTheField` fails; the other two, which check what is unchanged, pass on both.
- `python3 scripts/ci-tests.py check` and the Python guard tests pass; the new class is in the `editing` topic.
- `lintStableDebug` then `scripts/check-lint.py`: 1,056 errors, no new errors against `ci/lint-errors.json`.

## Device check (still to do)

Not yet run on a device. Apps known to hand over long fields in part include some note and code editors. Suggested check: in such an app, in a note of several thousand characters, place the cursor mid-sentence near the end and type a letter; it should stay lowercase.
