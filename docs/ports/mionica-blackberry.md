# BlackBerry improvements ported from mionica/pastiera

Original author: **Mircea Horea IONICĂ ([mionica](https://github.com/mionica))**.
Tracking issue: <https://github.com/pkb-rocks/plektra/issues/107>.

The following contributions were adapted to Plektra's current profiles and event routing:

- [09b1f51ac9](https://github.com/mionica/pastiera/commit/09b1f51ac9): the BlackBerry SYM/emoji row `0ZXCVBNM$`, zero/currency mappings, labels and settings serialization. Plektra retains its existing currency default and gives the close button its own trailing slot. The globe button's long press opens the editor.
- [38be1cfb53](https://github.com/mionica/pastiera/commit/38be1cfb53): shifted symbol mappings, including custom mappings and JSON strings. Plektra merges incomplete uppercase maps over the original mappings, preserves unshifted user text, and uses the same mappings for displayed labels, touch and hardware input. One-shot Shift is consumed after a successful commit.
- [12ce0a35c1](https://github.com/mionica/pastiera/commit/12ce0a35c1): BlackBerry Alt+0 speech input. Plektra retains Alt+Ctrl, honors the existing enable switch, ignores key repeats and Ctrl chords, and leaves disabled Alt+0 to normal routing.
- [3a43af1132](https://github.com/mionica/pastiera/commit/3a43af1132): relevant hardware settings only. Plektra also respects the manually selected device profile.

Source attribution is retained next to adapted code. The shared symbol JSON entries and zero/currency additions to settings maps and labels come from Mircea's first contribution. Authorship trailer for the eventual port commit:

```
Co-authored-by: Mircea Horea IONICĂ <6553639+mionica@users.noreply.github.com>
```

## Direct Boot contribution

The [original Direct Boot contribution](https://github.com/mionica/pastiera/commit/5da24e7634) inspired Plektra's storage audit and `DirectBootKeyboardConfig`. Issue #107 covered that preparation and explicitly deferred full before-first-unlock input. The subsequent implementation applies across supported devices; its architecture, device checks and follow-up work are documented in [Direct Boot](../direct-boot.md).

## Verification and limits

Automated integration tests cover the rendered Q25/Key2 grid and touch commits, symbol case selection and serialization, and existing device routing. Q25 and Key2 hardware are unavailable; their physical scancodes, voice launch and appearance remain unverified.

The speakerphone contribution and Romanian dictionary were not ported.
