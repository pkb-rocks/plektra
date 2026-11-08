# Personal word ranking

With suggestions enabled, completing a typed word at a word boundary records a per-language usage count. Repeated words receive a small ranking bonus when they are already dictionary candidates. Corpus frequencies remain unchanged, and the personal bonus is capped at1.0 after source weighting, including for user-dictionary entries.

This is the first independent smart-suggestions contribution. It covers completed typed words, single-word boundary replacements, and accepted suggestions that insert a space. Phrase/multiword prediction, correction-pair learning, advanced phrase/undo acceptance tracking, numeric tokenization and automatic retention policies remain separate contributions. Existing next-word prediction is retained.

## Settings and privacy

The existing suggestion settings and input-context eligibility guard control new learning. Password and otherwise excluded fields do not add personal word counts. Exact text replacements still work when suggestions are disabled.

The Auto-Correction menu includes **Reset learned word ranking**, next to the experimental-suggestions controls. It clears this dataset after confirmation. Dictionary entries, text replacements and next-word predictions remain intact. Learning is paused during export, import and reset; ordinary text entry continues.

Counts are stored in the application private SQLite database. This feature makes no network requests. Backups contain learned words and their counts; use synthetic vocabulary when sharing a test backup or recording.

## Persistence and ZIP format

One lazy ordered writer/cache is shared across IME instances. Normal IME shutdown does not cancel already accepted word updates. Export/reset/import fence new learning admission and drain registered writers, including when no IME controller is active. This does not promise durability before an abrupt operating-system process kill or power loss.

A ZIP contains:

- `learning/word_usage.json`: normalized word counts.
- `learning/exact_word_usage.json`: exact spelling counts, preserving accent distinctions.

Both use `schemaVersion:1` and named table rows, matching the existing prototype format. Import validates bounded row counts and file sizes, types, keys and counters before preferences/files are changed. Restoring through the live SQLite connection is transactional and invalidates positive and negative caches. Later import failures roll back both the counts and existing preference/file state.

Older backups containing neither file preserve current word counts. An explicit pair of empty files clears them. A partial pair is rejected. Digit-bearing keys from earlier prototypes are retained without changing current tokenization. Forgetting one accent variant can leave independent exact-form data; that data remains portable.

Other prototype learning datasets are reported as skipped and require their own later contributions. This PR does not add typing statistics to the ZIP.

## Automated verification

On the official base3ead470,140 focused tests passed with zero failures, errors or skipped tests. These include existing engine, next-word controller, exact-replacement-readiness and backup contract tests, plus:

- Personal ranking, bounded bonus and unchanged corpus frequencies.
- Language separation, negative-cache reuse, count/schema migration and deletion.
- Ordered writes, retry without duplicate success, and draining during closure.
- Completed-word counting, accepted-word/stale-prefix deduplication, truncated input, disabled-input guards and fallback when personalization storage is unavailable.
- Actual ZIP export/import, live-cache refresh, old/empty backups, invalid counts, failed pending writers and rollback after a later restore failure.
- Prototype numeric rows, accent-variant deletion and skipped unrelated datasets.

The isolated debug review APK is named **Plektra Ranking Test**, with an external local suffix `.review.wordranking`. Its suffix, SDK/cache paths, debug signing key and runtime helpers are outside the contribution. No production app was installed or replaced for these checks.

## On-device verification

A separate **Plektra Ranking Test** install on Titan2 verified the ranking change after typed-word learning, unchanged fixture dictionary frequencies and reset through the actual settings UI. The daily keyboard configuration was restored exactly. See [device-demo.md](device-demo.md) for the synthetic setup, injected-event limitations and privacy review. The user approved the sanitized recording for publication with this contribution. [The approved recording is attached to PR167](https://github.com/pkb-rocks/plektra/pull/167).

## CI memory follow-up

The initial full Linux CI run completed1183tests but reported five heap-exhaustion failures. Unused controllers now defer creation of the persistent learning store, with a regression test for disabled/unused suggestions. Unit-test workers are renewed after each class to bound Robolectric sandbox/resource retention; their existing2GB heap limit is unchanged. On Windows,90 focused tests covering the affected IME/text-navigation/keyboard classes and141 ranking/controller/backup tests pass in separate checks. Full multi-variant Linux CI must still be rerun; these focused results do not claim that full CI is green.

The recording demonstrates application source f9c2abd; the follow-up changes deferred store allocation and test isolation, not the recorded ranking/backup behaviour.
