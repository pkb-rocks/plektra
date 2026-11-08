# Android checks

`test-topics.json` assigns each test class to one feature area. It includes JVM,
Robolectric, and instrumented device tests. The source set determines how a test
runs. Names such as `E2E` and `DeviceBehavior` do not imply execution on a device.

Check the catalog after adding, renaming, or removing a test class:

```sh
python3 scripts/ci-tests.py check
```

Run one topic or a complete variant:

```sh
python3 scripts/ci-tests.py run --topic keys-modifiers
python3 scripts/ci-tests.py run --topic ime-integration
python3 scripts/ci-tests.py run --variant StableRelease --fdroid
```

The nine topics cover keys and modifiers, text editing, suggestions, devices and
layouts, UI and settings, data and backup, speech, application behavior and
distribution, and integration through the IME service. Mixed service tests stay
in the integration topic.

## PR checks

Stable Debug runs once across the topic jobs. Separate jobs run the full suites
for the other existing CI variants and both No-Accessibility debug variants.
A failure does not cancel the other topics, variants, or lint jobs.
`gradle-checks` remains the aggregate required check and fails if any job fails
or does not finish successfully.

The runner verifies that every selected class produces a nonempty JUnit report.
It rejects missing, unexpected, empty, fully skipped, or failing reports. It prints skipped test
counts. Reports are retained under `app/build/ci-reports/<variant>/<topic>`.
Each CI selection has its own downloadable artifact.

Instrumented tests are classified but require an emulator or device. PR CI does
not run them. Device acceptance still covers the IME picker, split screen,
physical key sequences, TalkBack, and behavior inside third-party applications.

## Lint

`lint-errors.json` records existing Error and Fatal findings from unmodified
`main` at `189e96dde169a723cb23b70c827dd806e656af62`. CI rejects additional findings.
Line movement does not change a finding's identity. Its rule, message, source
paths, source line, and occurrence count do. Fixed findings can disappear without failure.
Warnings remain visible in the complete HTML and XML reports.

Review baseline changes as code changes. Do not regenerate the baseline to make
a failing feature pass. Fix the new finding or document an intentional exception
at its source.
