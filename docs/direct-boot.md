# Direct Boot

Direct Boot is the period after an Android restart and before the user's first unlock (BFU). After that first unlock (AFU), credential-protected storage is available. Locking the screen again does not return the user to BFU.

## Keyboard and storage

Plektra's IME service declares `android:directBootAware="true"`. Android controls whether it offers and binds Plektra before unlock. When bound, the regular hardware and on-screen input pipeline uses the saved layout and configured input styles, including custom layouts. Custom layouts are mirrored with their validated functional mappings rather than replaced with QWERTY.

`DirectBootKeyboardConfig` publishes a device-protected snapshot of the required layout and input settings and a mirror of custom layouts, variations, navigation mappings and locale-to-layout mappings. Layout descriptions, unknown fields and invalid files are excluded. The snapshot and mirror are excluded from backup and transfer. Dictionaries, learned words, text replacements, snippets, clipboard history, app lists, launcher data, speech credentials and themes remain credential-protected.

The Application retains its original Android base context and delegates its storage methods to `DirectBootStorageContext`; the IME uses that context as its base. Keeping the Application base intact is required for Android's manifest-receiver dispatch. Before unlock, preferences read the snapshot with private features disabled. Other preference writes remain in memory. File reads use the published mirror; database access and writes through `openFileOutput` are rejected while locked. Navigation defaults are read from assets without creating a file in the published generation.

Each snapshot rebuild stages a complete generation and atomically replaces a small current-generation pointer. Critical layout and mapping changes publish synchronously; other allowlisted settings are refreshed on a serial background executor with a short delay. Failed publication retains the previous generation and schedules one retry. A successful CE change can therefore precede the corresponding DE update when publication fails. Restore captures its rollback state, applies files and preferences, and publishes or rolls back within one source transaction. Failure to repair the snapshot after rollback retains recovery data and reports incomplete recovery.

## Unlock handoff

Storage switches to credential-protected storage on the thread that first observes the OS unlock. It does not wait for `ACTION_USER_UNLOCKED`, because Android may initialize providers and their background work before delivering that broadcast. Existing preference holders and editors follow the switch. Deferred application and IME initialization runs on the main thread, with failed unlock listeners eligible for a later retry.

The service reloads layout, mappings, variations, settings and surface mode, then enables dictionaries, suggestions, text replacements, clipboard history, speech, Shizuku trackpad detection, subtype registration and external integrations. Normal operation is intended to continue without a process restart or manual IME switch.

The current implementation carries an explicitly written `keyboard_layout` value from BFU into credential-protected preferences at unlock. Other preference changes made BFU are discarded. The effective mapping is then resolved from the current Android subtype: an explicit `KeyboardLayoutSet` takes priority, followed by locale mapping in automatic mode, with the preference used in manual mode or without a subtype. The stored layout preference therefore need not determine the effective mapping after unlock.

The EN/DE label comes from Android's current subtype, not from `keyboard_layout`. If two input styles resolve to the same layout ID, carrying that value over is not observable in their character mapping. The Pixel's exact configured IDs were not recorded, so this remains conditional. Plektra does not explicitly reset the selected subtype at unlock; whether Android's own handling or the app's subtype re-registration causes the observed return to EN remains unverified. The maintainer has chosen to retain the current layout carry-over behavior for this Nightly. The observed language-label change does not establish a security defect and is not a release blocker. Private data and deferred features remain subject to the storage and unlock rules above.

## Verification on 15 November 2026

The full Nightly Debug unit run passed 1544 tests before the final migration fast-path guard. The 23 configuration tests passed again with that guard. Robolectric checks the service lifecycle under simulated locked storage, custom layouts and cycling, hardware and on-screen input, boundary keys without dictionary loads, snapshot projections and failure handling, restore transactions, preference/editor handoff, variations, and access between OS unlock and its broadcast. These checks do not reproduce Android's full cold-boot sequence.

Both devices received the debug build with verified backups. Manifest-receiver dispatch and encrypted speech-key import succeeded after correcting the Application base-context wrapper. Unlocked Notes checks passed for hardware digits, deletion and Enter on the Titan 2 Elite and on-screen space, deletion and Enter on the Pixel 7a. The T2E hardware-device comparison reported no unexplained differences. Tests used new synthetic notes; the on-screen smoke check on the T2E could not run because its software keys were not displayed in the current hardware mode.

### Pixel 7a, Android 17

The maintainer observed Plektra in the alphanumeric unlock field after a real restart. Cycling configured input styles worked, and Plektra continued to work after unlocking. The displayed style began as EN QWERTZ, was left on DE QWERTZ before unlock, and returned to EN QWERTZ afterwards. The exact subtype/layout transition remains under investigation; the user reported no major input problem.

### Titan 2 Elite, Android 16

Firmware: `Titan 2 Elite_EEA_V02.00.02`, board `G72BoardV1`.

After the same restart test, Kika appeared in the alphanumeric unlock field. Package Manager resolved the installed Plektra service with `directBootAware=true`; the input-method service's locked-user list omitted Plektra and logged »Default IME is uninstalled. Choose new default IME.« The stored default still named Plektra. This discrepancy occurs before binding the Plektra service. Its cause is unresolved; an OEM policy has not been established.

## Follow-up for the next release

These follow-ups are not blockers for the current Nightly. Release notes must describe the observed device coverage and the unresolved T2E limitation.

- **T2E discovery:** investigate the difference between Package Manager's service resolution and the IME service's boot-time list. Preserve locked-user logs and compare the firmware path with AOSP. Inspect firmware or an OTA with Mythos and Daybreak if ordinary diagnostics do not explain it. Acceptance: a supported way for Android to discover and bind Plektra BFU, or a demonstrated platform limitation.
- **Subtype and persistence:** The independent code check separates the OS subtype from the carried-over preference but does not establish the cause of the Pixel's EN/DE transition. The current carry-over behavior is accepted for this Nightly; no behavior change is planned as part of this release. Acceptance for the investigation: record subtype ID, locale, layout ID and resulting mapping before cycling, before unlock and after unlock, then identify what causes the label to return to EN.
- **Cold-boot coverage:** test a custom layout with a visibly different mapping, hardware modifiers where available and the transition to ordinary private features. The unlocked input-flow runner is not a BFU/password test. Q25 and Key2 hardware remain unavailable.
- **Latency:** measure layout-switch and settings-screen latency with representative custom-layout collections. Synchronous snapshot publication performs file I/O; optimize only if device evidence shows a problem while retaining a consistent published snapshot.

Reusable unlocked smoke flows are [hardware](../scripts/input-flows/t2e-direct-boot-unlock-hardware-smoke.flow) and [on-screen](../scripts/input-flows/direct-boot-unlock-screen-smoke.flow). Use the procedure and device-equivalence requirements in [Input flows](testing/input-flows.md).

## Origin and platform reference

Mircea Horea IONICĂ's [original contribution](https://github.com/mionica/pastiera/commit/5da24e7634) inspired the storage audit; attribution for the earlier port is in [the BlackBerry port notes](ports/mionica-blackberry.md). See [Android's Direct Boot guidance](https://developer.android.com/privacy-and-security/direct-boot) for the device-protected and credential-protected storage model.
