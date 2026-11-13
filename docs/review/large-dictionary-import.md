# Stream dictionary imports without expanding their indexes

Importing a large custom `.dict` previously read the complete file into a byte array and decoded the complete `DictionaryIndex` to validate it, on the caller's UI coroutine. It then reopened the provider to copy the file. A large frequency list and its prefix/SymSpell indexes can therefore cause a large transient heap allocation before the dictionary is even enabled.

This contribution stages one provider stream on the app's internal filesystem, validates the legacy JSON/CBOR structure incrementally on `Dispatchers.IO`, and atomically replaces the destination only after successful validation. An invalid, truncated, interrupted or cancelled import leaves the existing dictionary intact and removes its staging file. Cancellation is checked during copying and validation and is not converted to an error toast.

The file naming rules, import UI, persisted settings and backup format stay compatible with upstream. No new translations or permissions are needed. This PR introduces no dictionary assets or external library.

## What this does and does not establish

The original device report was that enabling more than two large dictionaries could crash the app. That is a user-observed scenario, not a universal numeric limit. This patch addresses the import-time allocation/UI blockage. It does not change `DictionaryRepository`'s runtime index loading, and does not guarantee that any number of large dictionaries can be enabled simultaneously. Runtime loading and compact/disk-backed dictionaries should be reviewed as separate small contributions.

In particular, the compact `PST3`/`P31D` formats used by the integration prototype require their corresponding readers; this importer targets the JSON/CBOR format already supported by official Plektra. It must not accept an unsupported compact file merely because it ends in `.dict`.

## Dictionary sources and attribution

- Official Plektra dictionary distribution: https://github.com/pkb-rocks/plektra-dict . Consult that repository's manifest and notices for the selected asset.
- Word-frequency source used by the extended-dictionary generator: Leipzig Corpora Collection / Wortschatz Leipzig, https://corpora.uni-leipzig.de/ and https://wortschatz.uni-leipzig.de/ . The integration script `scripts/build_extended_dictionary.py` starts from an existing base dictionary, adds entries from a Leipzig `*-words.txt` archive in decreasing frequency order, filters obvious noise, preserves original spelling, and builds normalized lookup keys separately.
- Upstream Plektra attribution: https://github.com/pkb-rocks/plektra/blob/68ace8abc5356b885e65f61a29fa6255a82178b4/THIRD_PARTY_NOTICES.md#leipzig-corpora-collection--wortschatz-leipzig . This records the Leipzig frequency-list attribution and notes that individual downloadable corpora may have their own terms.

The generic generator example `fra_news_2023_1M.tar.gz` is not evidence that this exact corpus produced every locally named `fr_extended_150k.dict` or `it_extended_150k.dict`. Exact original archive IDs and checksums for those two historical files have not yet been recovered. They are therefore not redistributed or claimed reproducible by this PR. No personal vocabulary, learned words or user backup is included. All automated fixtures are synthetic.

## Verification

Prepared regression coverage: legacy JSON and CBOR; a synthetic 150,000-entry stream; truncated/trailing data; malformed/duplicate JSON fields; replacement after successful validation; preservation of the old file and staging cleanup after invalid input, interrupted copy and cancellation.

Validation on official main 68ace8abc5356b885e65f61a29fa6255a82178b4: 15 targeted tests passed (12 importer tests and 3 DictionaryRepository tests), stable-debug APK assembled, and the official lint-baseline checker reported 0 new errors (1,056 existing Error/Fatal findings). On the Titan 2 / Android 16, the user imported a synthetic 150,000-entry file in an isolated review package. After the truncated-replacement test sequence, ADB verified that the installed file still had the exact valid-file SHA-256 fd78c4b93d89e6ec41f3200db227f008bf4732189441ebe2228fe13b03619705. Keyboard/accessibility configuration was preserved. The user confirmed the invalid-format message and responsive interface. A 20-second recording of these two operations was reviewed and approved for this PR, with English captions, no audio, and the status bar removed. Only the isolated review application and synthetic fixture folders are shown; the recording will be attached to the PR.

Device review scenario: use a separate review package, import a synthetic large valid dictionary and verify responsiveness and successful installation; attempt to replace it with a truncated copy and verify the working file remains. Record only synthetic filenames/text, and obtain the user's approval before publishing any capture.
