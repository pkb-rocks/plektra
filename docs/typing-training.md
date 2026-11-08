# Typing training

Typing training (Advanced → Typing training) records fast, uncorrected typing so that key detection and [key correction](https://pkb.rocks/settings/advanced.key_correction) can be calibrated from real input. This page documents where the bundled practice texts come from and how a build enables session sharing.

## Bundled content

Everything lives in `app/src/main/assets/typing-training/`.

| Files | Content | Origin |
|---|---|---|
| `<lang>-1k.txt`, `<lang>-10k.txt` | The 1,000 and 10,000 most frequent words, one per line, by descending frequency. | Derived from Plektra's dictionaries by `scripts/generate-typing-training-wordlists.py`. |
| `content/phrases-<lang>.json` | Short everyday sentences. | Generated with language models. |
| `content/chats-<lang>[-n].json` | Two-person chats; the participant types one side. | Generated with language models. |
| `content/tokens.json` | Names, codes and other non-words. | Generated with a language model. |

Scottish Gaelic (`gd`) has fewer than 10,000 usable dictionary words, so `gd-10k.txt` is shorter.

### Word lists

The dictionaries are listed in `app/src/main/assets/common/dictionary_catalog.json` with their download URLs and SHA-256 hashes. Their corpus sources, credits and licences are documented in [pkb-rocks/plektra-dict](https://github.com/pkb-rocks/plektra-dict#corpus-sources-and-credits). To regenerate the lists, download every catalogued `<lang>_base.dict` into one directory and run:

```sh
uvx --with cbor2 python -I scripts/generate-typing-training-wordlists.py <dict-dir>
```

Without a directory argument the script reads only the dictionaries bundled in the APK. Each language is restricted to its own alphabet; German and English keep their stricter letter sets.

### Generated texts

Every phrase, chat and token carries a `source` field naming the model that wrote it (`claude-haiku`, `claude-opus` or `gpt-6.1-terra`). The texts were checked for the language's script, and typographic punctuation was normalised to what a keyboard types. They are distributed under the project's licence.

## Session sharing

Sharing is opt-in and needs explicit consent in the app. Sessions are encrypted on the device to an [age](https://age-encryption.org) X25519 recipient and uploaded to a [plektra-datadrop](https://github.com/pkb-rocks/plektra-datadrop) server, which stores only ciphertext.

Builds without an upload URL disable sharing, the weekly practice reminder and its What's new card. Saving and exporting sessions keep working. To enable sharing, pass the server's base URL as a Gradle property:

```sh
./gradlew assembleNightlyRelease -Pplektra.typingTrainingUploadUrl=https://example.org
```

The public recipient key is set in `TYPING_TRAINING_AGE_RECIPIENT` in `app/build.gradle.kts`.

## Build provenance

Each session records the build's `GIT_COMMIT` and `GIT_DIRTY`, read from the checkout at build time. `GIT_DIRTY` is `true` as soon as any file differs from the commit, including untracked files. Builds outside a git checkout, or reproducible builds that must not depend on the checkout state, set both explicitly:

```sh
./gradlew assembleNightlyRelease -PPLEKTRA_GIT_COMMIT=<commit> -PPLEKTRA_GIT_DIRTY=false
```
