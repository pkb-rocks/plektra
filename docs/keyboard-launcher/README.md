# Plektra keyboard launcher: setup and contribution map

The keyboard launcher opens apps and registered actions with a held hardware modifier and an assigned letter. It complements an Android home-screen launcher; it does not become the default HOME application.

## Available in this contribution

- An optional **Plektra Launcher** settings screen, disabled by default.
- Left Shift, Right Shift or Fn as the held launcher key.
- Separate short and long letter assignments, using the existing installed-app and command catalogue.
- A configurable 250–800 ms hold threshold, activation vibration, explicit current assignment, search, save, cancel and removal.
- Stable command references and ZIP export/restore of enablement, trigger, threshold and assignments.
- Optional outside-editor key filtering through Plektra's existing accessibility service. No second competing key-filter service or screen-content retrieval capability is introduced.

The official Nav configuration remains unchanged. Its file is not rewritten or migrated. Existing QuickLauncher/starter features remain available while the larger launcher series is reviewed.

## Activate only what you use

1. Enable Plektra as a keyboard in Android settings and select it as the current input method.
2. Open **Plektra Launcher**, enable its switch and choose the held key.
3. Open a letter, choose **Short press** or **Long press**, select an app/action, then choose **Save**. Both assignments are saved together; Cancel leaves them unchanged.
4. Keep the chosen key held while pressing the assigned letter. With a dedicated Shift, the letter chord can activate immediately. That Shift is reserved for the launcher while enabled; use the other Shift for capitals. With Fn, wait for activation before choosing a letter.
5. Release the launcher key to resume ordinary typing. Releasing it cancels a pending long action. Escape also cancels. A held letter must not repeatedly launch the destination.

### How Nav Mode relates

The launcher has its own enable switch, assignments and threshold. **Nav Mode is not required merely to enable launcher shortcuts.** Enable Nav Mode separately if you want its Fn navigation functions. With Fn chosen for the launcher, quick Fn chords and two quick Fn taps retain their existing navigation/latching route; a deliberate hold selects launcher shortcuts. Navigation commands remain on the Nav screen rather than being offered as launcher destinations.

### Shortcuts outside text fields

In a full build, open the launcher's accessibility button, then enable **Plektra Launcher / Clicks** in Android Accessibility settings. This is the existing optional Clicks key-filter service with an expanded, accurately labelled purpose; its component identity is retained. It filters only the explicitly enabled launcher chords for the currently selected Plektra keyboard, while preserving existing supported Clicks button mappings.

This first contribution does **not** request screen-content retrieval. It does not inspect conversations, type a search, send a message or grant other Android permissions. Builds marked **no-accessibility** retain editor-context launcher handling but do not provide this global filtering service. If the keyboard process/service is disconnected, the filter fails open instead of swallowing native keys; reconnect/select the intended keyboard before testing again.

If Android blocks accessibility with a restricted-setting message, follow Android's documented procedure **for the Plektra build you installed**:

1. Android **Settings → Apps → Plektra → App info**.
2. Open the menu and choose **Allow restricted settings**, if Android offers it, then complete its confirmation.
3. Return to **Accessibility → Installed/Downloaded apps** and enable **Plektra Launcher / Clicks**.

Names vary by device and Android version. This Android 13+ restriction applies where Android imposes it; it is not a requirement to grant every permission. See [Android Help: restricted settings](https://support.google.com/android/answer/12623953?hl=en). A ZIP restore cannot enable an Android permission or accessibility service on another installation.

## Related contributions, not silently bundled here

| Part | Planned addition | Permission or dependency to explain |
| --- | --- | --- |
| Launcher core — this PR | Held modifier, short/long assignments, existing commands, independent settings and ZIP | Optional existing key-filter service for outside-editor use; Nav switch remains separate |
| Public app actions and saved destinations | Declared actions, legacy shortcut creators, stable saved IDs, editing and correct settings-app ownership | Public interfaces only; Android does not expose every private submenu |
| Conversations and explicit calls | Supported messaging routes, contact selection, private Telegram link guidance and direct calls | Contact-picker grants where applicable; Phone permission for an explicitly configured direct call; no private messaging-contact directory access |
| Android tiles and ready-to-type destinations | Discover visible system tiles, activate the selected tile, focus compatible writing fields | Optional accessibility screen-content access and user-initiated actions; Android may still require confirmation; no unrestricted system-toggle permission |
| Nav outside editors and screen-text selector | Navigation key bridge/repeat and accessible text selection | Optional accessibility; lifecycle tests and closing the selector before a launcher command opens another screen |
| Optional plain letters on HOME | Reuse assignments without a modifier on a positively recognised home screen | Separate opt-in setting and verified launcher/subscreen recognition; Ripe prototype work does not establish universal home-screen support |

The full integration prototype contains these additional features, but they are not all supplied by this first reviewable contribution. In particular, Ripe-specific send compatibility, editor send policies, theme photos, appearance colours, typing statistics and learned suggestions are not part of this launcher-core diff.

## Backup and keyboard layouts

The ZIP includes the launcher's four typed preferences and its existing command references. It does not grant permissions, install missing destination apps, or guarantee that a destination remains available after that app changes. Missing references remain stored and can be reassigned. Changing a software typing layout must not move launcher assignments. Physical keyboard normalization follows current upstream device-profile handling; conversion between different physical QWERTY/AZERTY devices is separate work and is not promised here.

## Validation and recording

The [validation record](review/validation.json) separates 174 focused tests, build/lint results and the on-device demonstration. Lint completed with findings; this is not a lint-clean or full-unit-suite claim.

The [approved 32-second recording](https://github.com/user-attachments/assets/65bf22c5-6eb2-42ff-b368-2d665ca975bb) uses an isolated build of commit `893b21d` on a Titan 2 (Android 16), two fictional apps with no network permissions, and Android's Display settings. It shows short/long Left Shift + A assignments, ordinary typing after releasing Shift, returning to the retained editor, and Left Shift + G opening a settings submenu. The status bar and source metadata were removed; there is no audio or personal conversation/contact content. The device owner reviewed and approved this video before upload, and the original IME/accessibility configuration was restored.

**Evidence limits:** the key events were injected through Android input dispatch using ADB; this is not a filmed physical-key or endurance test. The sample editor was focused before recording, so its retained focus does not establish automatic focus in other apps. The Display command only opens settings: it does not change brightness or establish Shizuku/system-toggle behavior. Fn double taps, opposite Shift, cancellation and locked-screen rejection have automated coverage, but are not all shown in the video. Other hardware profiles/app versions need their own checks.

See [device-demo.md](review/device-demo.md) for the exact sequence and the small throwaway fixture sources. No personal backup or production data is a public test fixture.
