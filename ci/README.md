# Android checks

`test-topics.json` groups JVM, Robolectric and instrumented test classes by topic.
New classes do not need a catalog entry: unassigned unit tests run automatically
in the final `unsorted` step, labelled »Aufräumbedarf«. The catalog check lists
unassigned classes and their count so maintainers can sort them later. Test
failures still fail CI. Duplicate and stale assignments remain errors; CI must
have exactly one step per curated topic and one final `unsorted` test step.
The source set determines execution; names such as `E2E` or `DeviceBehavior` do
not imply a device test.

```sh
python3 scripts/ci-tests.py check
python3 -m unittest discover -s scripts/tests
python3 scripts/ci-tests.py run --topic keys-modifiers
```

## PR checks

One Android job checks the catalog and Python guards, then generates and verifies
BuildConfig and merged manifests for all six flavors in Debug and Release, with
and without the F-Droid flag. These tasks do not compile variant Kotlin or Java.
Contracts cover package/channel identity, update flags, version channel, icons,
launcher aliases, provider authorities, IME identity and accessibility-service
presence. Existing `FlavorBuildConfigTest`, `ImeIdentityTest`,
`UpdateCheckerFlavorLogicTest` and `SettingLinkCoverageTest` continue to exercise
runtime behavior in the canonical suite.

CI compiles Stable Debug app and unit-test sources once. Nine sequential steps
run the curated unit-test topics against those outputs without cleaning. A final
`unsorted` step runs all remaining classes, or succeeds without invoking Gradle
when there are none. Gradle rechecks dependencies but reuses the compiled classes. A failed test step leaves the job
failed and later topics still run. Compilation failure skips the dependent test
steps; cancellation stops further work. Each topic retains distinct XML and HTML
reports under `app/build/ci-reports/StableDebug/<topic>`. The runner rejects missing,
unexpected, duplicate, empty, fully skipped and failing suites, and prints class,
test and skipped counts. Reports from failed tests are uploaded too.

One Stable Debug lint analysis enforces the existing no-new-error baseline.
Changes to Release sources, R8 rules, dependency/build configuration, Gradle
wrapper or CI machinery additionally build a representative unsigned minified
Stable Release APK. This covers the Release source set and R8 path without a
second full unit-test suite. Release smoke selection is intentionally broader
than just `app/src/release`.

Only clearly documentation-only changes (Markdown or reStructuredText at the
repository root or under `docs/`) skip Android work. Unknown paths run the complete
canonical suite. Rename detection is disabled when selecting paths, so removed
release inputs remain visible. There is no feature-based omission of runtime tests.
`gradle-checks` remains the aggregate required check and rejects any failed or
incomplete Android job.

## Distribution builds

Each manually dispatched distribution workflow runs one complete Stable Debug
suite for its checked-out revision, so an arbitrary dispatch ref cannot rely on
unrelated PR results. Generated contracts check variant configuration without
repeating full suites for Nightly, Play or F-Droid. Existing packaging, signing,
secret handling and publishing commands remain in place.

After building, APK checks inspect the actual package, version and debuggability
with SDK `aapt`. Play checks inspect the actual AAB manifest and validate each
bundle with checksum-pinned Google bundletool. Generated contract checks and
final artifact checks have different purposes: the former verify flavor wiring;
the latter verify what the workflow is about to distribute.

## Lint baseline and verification limits

`lint-errors.json` records 1,058 existing Error/Fatal findings from main at
`189e96dde169a723cb23b70c827dd806e656af62`. CI rejects additional findings. Line
movement does not change a finding's identity; its rule, message, source paths,
source line and occurrence count do. Fixed findings may disappear. Warnings stay
visible in HTML and XML reports. Review baseline changes as code; fix new findings
instead of regenerating the baseline to pass a feature.

Instrumented tests may be classified but require a device or emulator and do not run
in this job. Device acceptance covers IME selection, split screen, physical key
sequences, TalkBack and third-party applications. There is no measured line-coverage
claim. Generated contracts do not prove runtime behavior on every flavor, and a
representative R8 build does not replace testing a published package on a device.
