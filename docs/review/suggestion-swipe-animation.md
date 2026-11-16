# Swipe suggestion flight

## Behavior

An optional setting in Advanced > Trackpad gestures makes the selected suggestion rise above its own cell and fade, instead of flashing its background. The text is committed immediately through the existing swipe path; the animation does not delay insertion. Ordinary taps retain their existing feedback.

The setting is off by default, searchable through the settings index, and included in preference backups. Labels are provided for all eleven resource locales already supported by this checkout.

## Implementation

A non-touchable, non-focusable popup contains only a snapshot of the suggestion text, with a fine contrasting outline. It rises up to 56 dp over 400 ms. The popup has enough vertical headroom to avoid clipping above the suggestion bar, and uses window-relative coordinates so the keyboard's screen offset is not counted twice. A subsequent swipe replaces the previous popup. Detaching the host or finishing the animation releases it.

Disabled system animations, hidden or unmeasured text, and action-icon cells retain the existing color feedback. No new permissions or text logging are introduced.

## Validation

Base: upstream/main 49889809f3d24849500bbb1c1075cf22b99e6ce3.

81 targeted unit tests and stable debug assembly passed on the final AppCompat implementation. Checks cover opt-in preference behavior, snapshot lifetime, coordinate offsets, popup focus/touch behavior, replacement and cleanup, hidden sources, and action icons. The compatible row-height, menu-button, setting-link and backup tests were included. The native-graphics Appearance suite cannot run on this Windows Robolectric runtime (missing native RenderNode implementation); it was not changed or counted as passing.

Lint completed successfully. Comparison with ci/lint-errors.json reports 1058 errors: 1056 in the baseline, plus two FullBackupContent findings in the Direct Boot backup XML already present unchanged at upstream/main 49889809. No finding is introduced by this change; the baseline was not edited. This isolated upstream port has not yet received a fresh device visual check; the animation design was previously tried in the personal Pastiera build.
