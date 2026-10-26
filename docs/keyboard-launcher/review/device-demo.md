# Device demonstration

This documentation-only fixture is not compiled into Plektra or shipped in its APK. It requires no network permission. Use an isolated review build and fictional content; do not import a personal backup.

## Setup

- Review application: `rocks.pkb.plektra.review.launcher`, built from commit `893b21d`.
- Enable its held-key launcher, choose Left Shift and a 400 ms threshold.
- Assign A short to `app:org.example.plektra.launcher.sampleone`, A long to `app:org.example.plektra.launcher.sampletwo`, and G short to `settings.android.display`.
- Select the review IME. For operation outside text fields, enable only its `Plektra Launcher / Clicks` key filter while testing, then restore the original IME/service configuration. Complete Android's restricted-settings confirmation where required; a backup cannot grant it.
- Open Sample One and focus its editor before recording. Sample Two deliberately does not claim a universal auto-focus feature.

## Throwaway apps and event driver

[DemoActivity.java](fixtures/DemoActivity.java) is a native Activity used in two tiny APKs, with application IDs `org.example.plektra.launcher.sampleone` and `org.example.plektra.launcher.sampletwo`. Each manifest declares only the exported `org.example.plektra.launcher.fixture.DemoActivity` with MAIN/LAUNCHER, `Theme.Material.Light.NoActionBar`, and `allowBackup=false`; neither requests permissions. Label them Sample One and Sample Two. The Activity has no intent, network or messaging code. Build using a local Android SDK/debug signer; no fixture keystore is supplied.

[DemoKeys.java](fixtures/DemoKeys.java) is a separate instrumentation-only APK: manifest package `local.plektra.launcherdemo`, instrumentation `local.plektra.launcherdemo.DemoKeys`, target package `local.plektra.launcherdemo`, min SDK 26. It uses `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` so Plektra's actual filter remains active. Compile with `javac`/`d8` and the Android SDK, package/sign as a standalone debug APK. It does not instrument Plektra, read UI text, call launcher methods, launch intents or inject Enter. It refuses to start outside the isolated review app and the two samples. Remove it after testing.

For a short press it injects Shift down, A down, A up, then Shift up. For long it holds A for 650 ms. Correct release order matters: a modifier released first cancels a pending short action by design.

```sh
adb shell am instrument -w -e kind long local.plektra.launcherdemo/local.plektra.launcherdemo.DemoKeys
adb shell am instrument -w -e kind short local.plektra.launcherdemo/local.plektra.launcherdemo.DemoKeys
adb shell am instrument -w -e kind display local.plektra.launcherdemo/local.plektra.launcherdemo.DemoKeys
```

## Recorded sequence and observations

1. Type the fictional word `hello` with Shift released; Sample One displays `Hello`.
2. Hold Left Shift + A for 650 ms; Sample Two opens.
3. Hold Left Shift + tap A; Sample One returns with `Hello` and its existing editor focus retained.
4. Release Shift and type ` there`; the field displays `Hello there`.
5. Hold Left Shift + G; Android Display settings opens. No setting is changed.

The uncut on-device capture lasts about 32 seconds. The final video adds English captions, crops the status bar, omits audio and discards metadata/data streams. All 641 original cropped frames were inspected using contact sheets. No unrelated foreground app was allowed during the recording. The device owner approved the final video before upload. The original keyboard and accessibility settings were restored and compared with their original values.

This validates the real Android IME/filter/command path for those injected sequences. It does not replace testing physical keys, other devices, the full Fn matrix, or long-running sessions. No privileged brightness change, Android tile automation, third-party creator catalogue or automatic text-field focusing is part of this recording.
