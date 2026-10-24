# Literal-entry editors: reviewer test plan

## Problem and scope

An original spelling entered while defining a correction is deliberately not necessarily a dictionary word. Typing assistance must not turn `teh` into `the` inside the original field before that rule is saved. The same applies to personal dictionary words and snippet definitions.

Only six input fields are opted in: the personal dictionary add/edit word; autocorrection original and replacement; substitution-sheet trigger; snippet shortcut and body. Search, notes and ordinary message editors retain their previous policy. Existing trimming, validation and snippet-key normalization on save are unchanged.

## Mechanism

Shared Compose keyboard options request no automatic capitalization or autocorrection. The private IME option `rocks.pkb.plektra.literal_entry` carries the field-scoped request into InputContextState. The matching token is exact and comma-separated. Existing password, email and URL classification takes priority, and non-text fields are not reclassified. Existing restricted-field gates disable dictionary suggestions, replacements, double-space punctuation and text expansion. Explicit Shift and character variants remain available.

The user's opt-in for capitalization in restricted fields must not override this deliberate field opt-out. Returning to an ordinary field restores the user's usual capitalization policy.

No preference, route, permission, resource string, locale file or ZIP schema is introduced or changed. No settings or user data migration. No launcher or learned-multiword dependency. Other keyboards receive Android's standard hints but may choose how to honor them.

## Automated validation

77 targeted tests passed, with zero failures, errors or skips. Targeted classes cover field classification, spelling-related editor stores, expansion refusal and actual IME field lifecycle/modifier behaviour. The new service regression exercises restricted-field capitalization enabled, deliberate Shift and return to an ordinary field. It failed before the priority fix and passes afterwards. Existing password and email tests remain in scope. Android lint and debug APK assembly completed. The full test suite has not been run locally.

The lint task completing does not mean the repository is lint-clean: upstream has `abortOnError = false`. Its report contains 2,038 findings, with no additional canonical finding compared with the independently extracted PR160 branch (which has one extra warning in its statistics store). Lint configuration was not relaxed or changed by this branch.

## Device evidence

Tested on a Titan 2 running Android 16/API 36 in the separate `rocks.pkb.plektra.review.literalentry` installation. Synthetic key events were delivered through the actual keyboard service into the application's dictionary editor:

- The focused editor exposes `inputType=0x80001` and the exact literal-entry private IME option.
- `teh`, two spaces and `test` remain lowercase, with no double-space full stop; deliberate Shift then T adds the final capital, giving `teh  testT`.
- A separate `teh` entry saves unchanged in the review application's dictionary store.
- Moving to the ordinary dictionary search field removes the literal option and typing `test` produces `Test`, confirming normal automatic capitalization resumes.

The device demonstration checks dictionary-entry capitalization, double-space behaviour and manual Shift. It does not claim a manual pass over every edited field or a blocked replacement-rule demonstration: automatic replacements remained at their default disabled setting in this review installation. The unit suite covers the literal correction/suggestion policy and the existing replacement stores and expansion paths.

The 16-second capture is cropped to the dictionary dialog, with no audio, status bar, navigation or inherited media metadata. All 96 exported frames were visually inspected. A matching still is available. The user approved keeping the capture with English captions; the captioned version was shown before publication. Captions explain the lowercase spelling, preserved double space and deliberate Shift. The underlying device capture is unchanged.

The exact original default keyboard and enabled-keyboard list were restored and verified. Everyday installations and their preferences were not instrumented or replaced.

## Durability

This change does not alter saving or backup logic; ordinary dictionary/replacement/snippet persistence remains unchanged. It only changes assistance while composing those entries. Installation-local typing statistics remain a separate PR and are not included here.
