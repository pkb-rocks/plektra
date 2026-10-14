# Third-party Notices

Pastiera is licensed under the GNU General Public License version 3. Some components are derived from third-party open source projects and retain their original attribution requirements.

## Android Open Source Project LatinIME

- Project: Android Open Source Project, LatinIME (`platform/packages/inputmethods/LatinIME`)
- Source repository: https://android.googlesource.com/platform/packages/inputmethods/LatinIME
- Pinned source revision used as reference: `127336e9f29d69607eab55982324b210279ae8c5`
- License: Apache License, Version 2.0
- License text: `third_party/licenses/Apache-2.0.txt`

### Derived scope

Pastiera's full virtual keyboard mode uses AOSP LatinIME as a reference for keyboard geometry and visual assets:

- `app/src/main/java/rocks/pkb/plektra/inputmethod/aospkeyboard/AospKeyboardView.kt`
- AOSP-derived keyboard background, key, spacebar, preview, popup and selected-popup `.9.png` resources under:
  - `app/src/main/res/drawable-mdpi/`
  - `app/src/main/res/drawable-hdpi/`
  - `app/src/main/res/drawable-xhdpi/`
  - `app/src/main/res/drawable-xxhdpi/`
  - `app/src/main/res/drawable-xxxhdpi/`

### Non-derived scope

Pastiera does not import AOSP dictionaries. Suggestions, status bars, prediction bars, IME lifecycle, settings, PKB behavior and custom dictionaries remain Pastiera-specific unless separately documented.

## OpenGameArt Typing Sounds

Pastiera includes short typing sound samples derived from CC0 sound effects.

### Keyboard Soundpack #1 [Typing and Single Keystrokes]

- Author: unicaegames
- Source: https://opengameart.org/content/keyboard-soundpack-1-typing-and-single-keystrokes
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)
- Derived files:
  - `app/src/main/res/raw/typing_click_1.ogg`
  - `app/src/main/res/raw/typing_click_2.ogg`
  - `app/src/main/res/raw/typing_click_3.ogg`
  - `app/src/main/res/raw/typing_click_4.ogg`

### Typewriter sounds

- Author: Cassie-OrbitGames
- Source: https://opengameart.org/content/typewriter-sounds
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)

### Mechanical Sounds

- Author: BMacZero
- Source: https://opengameart.org/content/mechanical-sounds
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)

Typing sound derivatives are included under `app/src/main/res/raw/typing_*.ogg`.

## Unicode CLDR Emoji Annotations

- Project: Unicode Common Locale Data Repository (CLDR)
- Source repository: https://github.com/unicode-org/cldr-json
- Source path used by generator: `cldr-json/cldr-annotations-full/annotations`
- License: Unicode Data Files and Software License / Unicode License

Pastiera's local emoji search assets under `app/src/main/assets/common/emoji_search/*.tsv`
are generated from Unicode CLDR annotation data by `scripts/generate_emoji_search_assets.py`.
The generated TSV files are filtered to the emoji set bundled with Pastiera and normalized
for compact local lookup.

## Leipzig Corpora Collection / Wortschatz Leipzig

- Project: Leipzig Corpora Collection / Wortschatz Leipzig
- Project pages: https://corpora.uni-leipzig.de/ and https://wortschatz.uni-leipzig.de/
- Companion asset repository: https://github.com/pkb-rocks/plektra-dict
- License: Creative Commons attribution terms for the downloaded corpus/frequency-list data;
  the Leipzig frequency dictionary word lists are documented as CC-BY 3.0, while current
  downloadable corpus data may carry corpus-specific terms.

Pastiera's bundled base dictionaries under `app/src/main/assets/common/dictionaries/*_base.json`
and the generated serialized dictionaries under `app/src/main/assets/common/dictionaries_serialized/*_base.dict`
are frequency-list derivatives built mainly from Leipzig Corpora Collection / Wortschatz Leipzig
word-frequency data, with project-maintained filtering, truncation, normalization, and additional
entries. The companion `pastiera-dict` repository distributes larger downloadable `.dict` assets
and their manifests. Conversion and serialization scripts live under `scripts/`.

Provenance note: the maintained dictionary pipeline and current maintainer-provided sources point
mainly to Leipzig/Wortschatz frequency data. Some older bundled entries predate the current
documentation trail, so their exact upstream corpus IDs are not fully reconstructed here.

<!-- vendored wireless-ADB -->
## Shizuku wireless-ADB implementation (vendored)

- Project: Shizuku, by RikkaApps
- Source repository: https://github.com/RikkaApps/Shizuku
- License: Apache License, Version 2.0
- License text: `third_party/licenses/Apache-2.0.txt`
- Vendored source: `app/src/main/java/moe/shizuku/manager/adb/` (AdbClient, AdbKey, AdbMdns, AdbMessage, AdbPairingClient, AdbPairingService, AdbProtocol, AdbException; see the NOTICE file there)

Plektra's embedded broker pairs with the device's own Wireless debugging using Shizuku's ADB pairing and connection classes, vendored with the original `moe.shizuku.manager.adb` package name. This is separate from the optional Shizuku integration.

The bundled prebuilt native library `libadb.so` (`app/src/main/jniLibs/arm64-v8a/libadb.so`, arm64-v8a only) is built from the Shizuku project's native `adb` module, which incorporates BoringSSL for the SPAKE2 pairing handshake and TLS. BoringSSL is distributed under the OpenSSL License / ISC-style terms documented in its source (https://boringssl.googlesource.com/boringssl). The JNI entry class name `moe/shizuku/manager/adb/PairingContext` is hardcoded in the binary and is therefore preserved in the vendored Kotlin sources.

Provenance (verified 2026-10-06): `libadb.so` is byte-identical to `lib/arm64-v8a/libadb.so` in the official Shizuku v13.6.0 release APK (https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0, `shizuku-v13.6.0.r1086.2650830c-release.apk`, signed CN=Rikka), unmodified: sha256 `a4257bc05ace812afa60a81e656724a07691edd08868dc90caab3bc6d0a49207`, BuildID `dfca36a85f1a4828b17a181e6ad4235dff758df2`.
