# Typing statistics

This contribution adds an independently searchable Typing statistics screen to the official Plektra base. It does not require the integration launcher, app colours, typography PR or learned multiword engine.

## Behaviour

The screen displays manually entered words, words accepted through suggestions, and their total. A partial typed prefix replaced by a suggestion does not contribute another manual word. A multiword suggestion contributes its words rather than one gesture. Taps and swipes notify accounting after a successful editor commit. Completed words are cumulative activity counters; deleting a previously completed word does not subtract it, and totals are not final-document word counts.

Statistics use aggregate counters. The tracker holds only a pending word/context in memory; no text history is persisted as statistics. Ordinary character events introduce no extra InputConnection reads for statistics. Field entry checks the adjacent characters once, and suggestion-enabled cursor observations reuse reads already performed by the controller. Replacing accented/multitap characters updates the pending prefix rather than appending a second character.

The displayed installation date is Android's firstInstallTime, preserved across APK updates. It describes application installation, not necessarily the date this feature began counting. Historical text and typing before this feature are not reconstructed.

## Persistence and backup

Counters remain local to the installation. ZIP export omits them; restore ignores counter values in older archives and preserves current totals, including a pending local write. Ordinary portable keyboard preferences still restore. An archive does not replace Android's installation date.

Writes are batched over approximately one second and flushed when an input session ends or the controller is destroyed. Abrupt process termination may lose the unflushed aggregate. Negative, malformed and oversized stored values are handled defensively rather than crashing the screen or producing negative totals.

## Validation

211 targeted tests pass, including the complete existing input-router, alternate-character manager and device-routing test classes, plus the new accounting cases. Android stable debug lint and APK assembly pass. These are targeted Windows checks, not a claim that every test/variant was run locally. Official Android CI remains to run after submission.

The tests exercise ordinary hardware-key routing with suggestions disabled, editing an existing field word, successful/failed suggestion acceptance, multiword prefix consumption, accented and composing replacements, bounded cursor observations, batched writes, corrupt/oversized counters, and real ZIP export/import preserving local totals while restoring ordinary settings. They use synthetic data.

Relevant checks:

```sh
./gradlew :app:testStableDebugUnitTest --tests '*TypingStatistics*Test' --tests '*SettingLink*Test' --tests '*InputEventRouter*Test' --tests '*BackupPreferenceContractIntegrationTest' --tests '*PhysicalKeyboardInputMethodServiceDeviceBehaviorTest' --tests '*AlternateCharacterManagerTest'
./gradlew :app:lintStableDebug :app:assembleStableDebug
```

## Device evidence

Tested on a Titan 2 running Android 16 with a separately installed review APK. The recording uses the app's existing Notes editor with synthetic text only. After entering `Hello` manually, typing `wo` and tapping the `work` suggestion, the statistics screen reports 3 manual words and 1 suggested word (4 total). Two manual words were already present from exploratory testing before recording, so this take adds exactly one manual word and one suggested word. No counters were seeded for the demonstration.

The phone's original default IME and exact enabled-IME list were restored and checked after recording. Status/navigation areas and audio are omitted; every exported frame was inspected for personal content before publication. Device evidence covers ordinary manual entry, successful suggestion acceptance and viewing totals after leaving the editor. Failed commits, multiword acceptance and ZIP restoration are covered by automated tests rather than this video.
