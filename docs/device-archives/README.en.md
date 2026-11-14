English | [Deutsch](README.md)

# Device Archive

This directory collects observed behavior of physical keyboards on real devices and specific firmware versions.

The snapshots are not runtime assets of the app. They serve as an archive so that later changes to device firmware, Android keymaps or Pastiera routing can be compared against a known state.

## Format

Each snapshot consists of:

- a Markdown file for the human-readable assessment,
- a JSON file with normalized observations,
- optionally a raw dump under `raw/`, if the original debug file should remain permanently traceable.

The JSON files use their own archive schema with `schema_version`. It is deliberately independent of the importable Pastiera layout files under `app/src/main/assets/common/layouts/`.

## Naming convention

Snapshot files follow this pattern:

```text
YYYY-MM-DD-<gerät>-<firmware>-<layout-kontext>.md
YYYY-MM-DD-<gerät>-<firmware>-<layout-kontext>.json
```

The file name should make the comparison context recognizable, but should not contain all metadata. Details such as Android build, app version, input devices and settings are in the snapshot itself.

English versions are named `NAME.en.md` and sit next to the German file; each links the other in its first line.

## Known devices

- [Unihertz Titan 2](unihertz-titan2/README.en.md)
- [Unihertz Titan 2 Elite](unihertz-titan2elite/README.en.md)
