#!/usr/bin/env python3
"""Derive typing-training word lists from Plektra's dictionaries.

Usage: uvx --with cbor2 python -I scripts/generate-typing-training-wordlists.py [DICT_DIR]

DICT_DIR holds <lang>_base.dict for every language of common/dictionary_catalog.json
(the bundled ones plus the downloadable releases); it defaults to the bundled assets.
Writes app/src/main/assets/typing-training/<lang>-<size>.txt, one word per line,
ordered by descending dictionary frequency.
"""
import json
import pathlib
import re
import sys
import unicodedata

import cbor2

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUNDLED = ROOT / "app/src/main/assets/common/dictionaries_serialized"
CATALOG = ROOT / "app/src/main/assets/common/dictionary_catalog.json"
TARGET = ROOT / "app/src/main/assets/typing-training"
SIZES = {"1k": 1_000, "10k": 10_000}
# German and English keep their original, stricter alphabets.
LETTERS = {
    "de": re.compile(r"^[a-zA-ZäöüÄÖÜß]+$"),
    "en": re.compile(r"^[a-zA-Z]+$"),
}
SCRIPTS = {"el": "GREEK", "ru": "CYRILLIC", "uk": "CYRILLIC"}
SINGLE_LETTER_WORDS = {"en": {"a", "I"}, "de": set()}


def in_alphabet(language: str, word: str) -> bool:
    if language in LETTERS:
        return bool(LETTERS[language].match(word))
    script = SCRIPTS.get(language, "LATIN")
    return all(char.isalpha() and unicodedata.name(char, "").startswith(script) for char in word)


def ranked_words(source: pathlib.Path, language: str) -> list[str]:
    raw = (source / f"{language}_base.dict").read_bytes()
    # Most releases are CBOR; some (gd) are plain JSON with the same structure.
    index = (json.loads(raw) if raw[:1] == b"{" else cbor2.loads(raw))["normalizedIndex"]
    frequencies: dict[str, int] = {}
    for entries in index.values():
        for entry in entries:
            word = unicodedata.normalize("NFC", entry["word"])
            frequency = entry["frequency"]
            if frequency > frequencies.get(word, -1):
                frequencies[word] = frequency
    words = [
        word
        for word in frequencies
        if in_alphabet(language, word)
        and (len(word) > 1 or word in SINGLE_LETTER_WORDS.get(language, {word}))
        # Abbreviations such as "km" or "USA" are not useful practice words.
        and not (len(word) > 1 and word.isupper())
    ]
    return sorted(words, key=lambda word: (-frequencies[word], word))


def main() -> int:
    source = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else BUNDLED
    languages = sorted(item["id"].removesuffix("_base") for item in json.loads(CATALOG.read_text())["items"])
    TARGET.mkdir(parents=True, exist_ok=True)
    for language in languages:
        if not (source / f"{language}_base.dict").exists():
            print(f"{language}: no dictionary in {source}, skipped", file=sys.stderr)
            continue
        words = ranked_words(source, language)
        if len(words) < min(SIZES.values()):
            print(f"{language}: only {len(words)} words", file=sys.stderr)
            return 1
        for label, size in SIZES.items():
            if len(words) < size:
                # Small dictionaries (gd) get a shorter "10k" list rather than none.
                print(f"{language}-{label}: only {len(words)} words", file=sys.stderr)
            (TARGET / f"{language}-{label}.txt").write_text("\n".join(words[:size]) + "\n", encoding="utf-8")
        print(language, len(words))
    return 0


if __name__ == "__main__":
    sys.exit(main())
