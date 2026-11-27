# Build and release scripts

Run scripts from the repository root.

## Custom dictionaries

[Build and import a Romanian dictionary](../docs/romanian-dictionary-import.md) with
`python3 convert_dictionaries.py INPUT OUTPUT --scale-frequencies` and
`python3 scripts/preprocess_dictionaries.py INPUT --output-dir DIRECTORY`.
Both tools retain their existing batch behavior when run without arguments.

## Nightly

Create CalVer metadata:

```sh
./scripts/nightly-version.sh 26.09
```

Build or install the Nightly debug variant:

```sh
./scripts/build-nightly-debug.sh 26.09
./scripts/build-nightly-debug.sh 26.09 --install --device <adb-serial>
```

Build an unsigned canonical Nightly APK for separate PIV signing:

```sh
./scripts/build-nightly-release-unsigned.sh 26.09 output.apk direct
```

Use `fdroid` as the third argument for the F-Droid build path.

## Stable

The first planned stable version is `26.10`. Build signed local artifacts only with an explicitly configured keystore:

```sh
./scripts/build-release.sh 26.10 26100000
```

Build an unsigned stable artifact for PIV or F-Droid processing:

```sh
./scripts/build-stable-release-unsigned.sh 26.10 26100000
./scripts/build-fdroid.sh 26.10 26100000
```

The PIV signing and verification scripts are:

- `sign-plektra-stable-apk-piv.sh`
- `verify-plektra-stable-rotation-apk.sh`
- `sign-plektra-nightly-apk-piv.sh`
- `verify-plektra-nightly-rotation-apk.sh`

Signing remains an interactive maintainer action.

## Google Play

Google Play variants use `playStable` and `playNightly`. The local upload key is configured with `playUpload*` properties in ignored `release/keystore.properties`. Build AABs with:

```sh
./gradlew :app:bundlePlayStableRelease :app:bundlePlayNightlyRelease \
  -PPLEKTRA_VERSION_NAME=26.10 \
  -PPLEKTRA_VERSION_CODE=26100000
```
