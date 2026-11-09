#!/usr/bin/env python3
"""Update Plektra's generated emoji, symbol and emoji font assets from pinned sources.

The generator intentionally pins Unicode Emoji, the Unicode Character Database,
CLDR and the bundled Noto Color Emoji font. ``--check`` runs the complete
generation in a temporary tree and fails when committed assets are stale,
without modifying the checkout. ``scripts/verify_emoji_font_coverage.py``
verifies that the bundled font draws every emoji the picker offers.
"""

from __future__ import annotations

import argparse
import filecmp
import hashlib
import json
import re
import shutil
import sys
import tempfile
import unicodedata
import urllib.request
from collections import OrderedDict
from pathlib import Path


UNICODE_EMOJI_VERSION = "18.0"
CLDR_JSON_VERSION = "49.0.0"
EMOJI_TEST_URL = (
    f"https://www.unicode.org/Public/{UNICODE_EMOJI_VERSION}.0/emoji/emoji-test.txt"
)
UNICODE_DATA_URL = f"https://www.unicode.org/Public/{UNICODE_EMOJI_VERSION}.0/ucd/UnicodeData.txt"
EMOJI_DATA_URL = f"https://www.unicode.org/Public/{UNICODE_EMOJI_VERSION}.0/ucd/emoji/emoji-data.txt"
# Noto Color Emoji 2.057 (Unicode 18.0), CBDT bitmaps with EmojiCompat metadata: CBDT renders on
# every supported Android version (COLRv1 would need Android 12).
NOTO_EMOJI_TAG = "v2026-09-24-unicode18_0"
NOTO_EMOJI_FONT_URL = (
    "https://raw.githubusercontent.com/googlefonts/noto-emoji/"
    f"{NOTO_EMOJI_TAG}/2D/fonts/NotoColorEmoji-emojicompat.ttf"
)
NOTO_EMOJI_FONT_SHA256 = "c7e2931d6bd9e0a78e3f7e50d6a9dad2217f4bb4b83b4ef2c0d34aeb88bbecf0"
NOTO_EMOJI_FONT_ASSET = "app/src/main/assets/fonts/NotoColorEmoji-emojicompat.ttf"
# Symbols offered by the symbol search: symbols, punctuation and numeric symbols.
SYMBOL_CATEGORIES = {"Sm", "Sc", "Sk", "So", "Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po", "No"}
CLDR_ANNOTATIONS_BASE_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/"
    f"{CLDR_JSON_VERSION}/cldr-json/cldr-annotations-full/annotations"
)
SEARCH_LOCALES = ("en", "de", "es", "fr", "hy", "it", "pl", "ru", "uk")

GROUP_TO_FILE = {
    "Smileys & Emotion": "SMILEYS_AND_EMOTION.txt",
    "People & Body": "PEOPLE_AND_BODY.txt",
    "Animals & Nature": "ANIMALS_AND_NATURE.txt",
    "Food & Drink": "FOOD_AND_DRINK.txt",
    "Travel & Places": "TRAVEL_AND_PLACES.txt",
    "Activities": "ACTIVITIES.txt",
    "Objects": "OBJECTS.txt",
    "Symbols": "SYMBOLS.txt",
    "Flags": "FLAGS.txt",
}

VERSION_TO_MIN_API = {
    "E4.0": 24,
    "E5.0": 26,
    "E11.0": 28,
    "E12.0": 29,
    "E12.1": 29,
    "E13.0": 30,
    "E13.1": 31,
    "E14.0": 32,
    "E15.0": 33,
    "E15.1": 34,
    "E16.0": 35,
}
# Emoji versions newer than the last known system font mapping: runtime-gated.
RUNTIME_GATED_VERSIONS = {"E17.0", "E18.0"}

SKIN_TONES = {chr(codepoint) for codepoint in range(0x1F3FB, 0x1F400)}


def repo_root_from_script() -> Path:
    return Path(__file__).resolve().parents[1]


def fetch_text(url: str) -> str:
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_annotations(locale: str) -> dict[str, dict]:
    url = f"{CLDR_ANNOTATIONS_BASE_URL}/{locale}/annotations.json"
    payload = json.loads(fetch_text(url))
    return payload["annotations"]["annotations"]


def codepoints_to_emoji(codepoints: str) -> str:
    return "".join(chr(int(codepoint, 16)) for codepoint in codepoints.split())


def strip_skin_tones(emoji: str) -> str:
    return "".join(character for character in emoji if character not in SKIN_TONES)


def normalize_field(value: str) -> str:
    return " ".join(value.replace("\t", " ").replace("\n", " ").split())


def normalize_shortcode(value: str) -> str:
    ascii_value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]+", "_", ascii_value.lower()).strip("_")


def parse_emoji_test(text: str) -> tuple[str, list[dict[str, str]]]:
    version = "unknown"
    group = ""
    subgroup = ""
    records: list[dict[str, str]] = []

    for line in text.splitlines():
        if line.startswith("# Version:"):
            version = line.split(":", 1)[1].strip()
        elif line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
        elif line.startswith("# subgroup:"):
            subgroup = line.split(":", 1)[1].strip()

        if "; fully-qualified" not in line:
            continue
        codepoints, rest = line.split(";", 1)
        if rest.split("#", 1)[0].strip() != "fully-qualified":
            continue
        comment = line.split("#", 1)[1].strip()
        comment_parts = comment.split(" ", 2)
        records.append(
            {
                "emoji": codepoints_to_emoji(codepoints.strip()),
                "version": comment_parts[1] if len(comment_parts) > 1 else "",
                "name": comment_parts[2] if len(comment_parts) > 2 else comment,
                "group": group,
                "subgroup": subgroup,
            }
        )
    return version, records


def load_category_files(emoji_dir: Path) -> dict[str, list[list[str]]]:
    files: dict[str, list[list[str]]] = OrderedDict()
    for file_name in GROUP_TO_FILE.values():
        files[file_name] = [
            tokens
            for line in (emoji_dir / file_name).read_text(encoding="utf-8").splitlines()
            if (tokens := [token for token in line.split(" ") if token])
        ]
    return files


def load_min_api(path: Path) -> OrderedDict[int, list[str]]:
    result: OrderedDict[int, list[str]] = OrderedDict()
    for line in path.read_text(encoding="utf-8").splitlines():
        tokens = [token for token in line.split(" ") if token]
        if tokens:
            result[int(tokens[0])] = tokens[1:]
    return result


def min_api_for_version(version: str) -> int | None:
    # Android system emoji fonts can update independently of the OS. Unknown
    # versions are therefore runtime-gated with Paint.hasGlyph instead of being
    # exposed through an invented API level.
    if version in RUNTIME_GATED_VERSIONS:
        return 0
    return VERSION_TO_MIN_API.get(version)


def base_of(record: dict[str, str], qualified: set[str], untoned_by_name: dict[str, str]) -> str:
    """The emoji whose line [record] belongs on: itself, or the untoned emoji it tones.

    Skin-toned sequences belong to the sequence without their tones (👋🏽 to 👋, 👨🏽‍🦰 to 👨‍🦰).
    Multi-person sequences whose untoned form is not an emoji of its own (🫱🏻‍🫲🏼, 🧑🏻‍❤️‍💋‍🧑🏼)
    belong to the emoji that shares their name before the colon (🤝, 💏).
    """
    emoji = record["emoji"]
    untoned = strip_skin_tones(emoji)
    if untoned == emoji:
        return emoji
    if untoned in qualified:
        return untoned
    return untoned_by_name.get(record["name"].split(":", 1)[0], emoji)


def build_category_lines(records: list[dict[str, str]]) -> OrderedDict[str, list[list[str]]]:
    """The picker's category files from emoji-test.txt: one line per emoji, its skin tones after it.

    Files, lines and variants follow the order of emoji-test.txt.
    """
    qualified = {record["emoji"] for record in records}
    untoned_by_name = {
        record["name"]: record["emoji"]
        for record in records
        if strip_skin_tones(record["emoji"]) == record["emoji"]
    }
    files: OrderedDict[str, list[list[str]]] = OrderedDict(
        (file_name, []) for file_name in GROUP_TO_FILE.values()
    )
    lines_by_base: dict[str, list[str]] = {}
    for record in records:
        file_name = GROUP_TO_FILE.get(record["group"])
        if not file_name:
            continue
        emoji = record["emoji"]
        base = base_of(record, qualified, untoned_by_name)
        line = lines_by_base.get(base)
        if line is None:
            line = [base]
            lines_by_base[base] = line
            files[file_name].append(line)
        if emoji not in line:
            line.append(emoji)
    return files


def update_categories(emoji_dir: Path, records: list[dict[str, str]]) -> int:
    previous_tokens = load_project_emojis(emoji_dir)
    files = build_category_lines(records)
    for file_name, lines in files.items():
        (emoji_dir / file_name).write_text(
            "".join(" ".join(tokens) + "\n" for tokens in lines),
            encoding="utf-8",
        )

    # minApi.txt keeps its curated levels; emoji new to the picker get the level of their version.
    offered = {emoji for lines in files.values() for line in lines for emoji in line}
    min_api_path = emoji_dir / "minApi.txt"
    min_api = OrderedDict(
        (api, [emoji for emoji in emojis if emoji in offered])
        for api, emojis in load_min_api(min_api_path).items()
    )
    known_gated = {emoji for emojis in min_api.values() for emoji in emojis}
    added = 0
    for record in records:
        emoji = record["emoji"]
        if emoji not in offered or emoji in previous_tokens:
            continue
        added += 1
        api = min_api_for_version(record["version"])
        if api is None or emoji in known_gated:
            continue
        min_api.setdefault(api, []).append(emoji)
        known_gated.add(emoji)
    min_api_path.write_text(
        "".join(
            f"{api} {' '.join(emojis)}\n" for api, emojis in sorted(min_api.items()) if emojis
        ),
        encoding="utf-8",
    )
    return added


def load_project_emojis(emoji_dir: Path) -> set[str]:
    emojis: set[str] = set()
    for path in sorted(emoji_dir.glob("*.txt")):
        if path.name == "minApi.txt":
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            emojis.update(token for token in line.split(" ") if token)
    return emojis


def build_search_rows(
    annotations: dict[str, dict], allowed_emojis: set[str]
) -> list[tuple[str, str, list[str]]]:
    rows: list[tuple[str, str, list[str]]] = []
    for emoji, payload in annotations.items():
        if emoji not in allowed_emojis or not isinstance(payload, dict):
            continue
        tts_values = payload.get("tts") or []
        keywords = payload.get("default") or []
        if not isinstance(tts_values, list):
            tts_values = [str(tts_values)]
        if not isinstance(keywords, list):
            keywords = [str(keywords)]
        name = normalize_field(str(tts_values[0])) if tts_values else ""
        normalized_keywords = list(
            OrderedDict.fromkeys(
                normalize_field(str(keyword)) for keyword in keywords if str(keyword).strip()
            )
        )
        if name or normalized_keywords:
            rows.append((emoji, name, normalized_keywords))
    return sorted(rows, key=lambda row: row[0])


def write_search_tsv(path: Path, rows: list[tuple[str, str, list[str]]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(f"{emoji}\t{name}\t{'|'.join(keywords)}\n" for emoji, name, keywords in rows),
        encoding="utf-8",
    )


def write_shortcode_index(
    path: Path,
    english_annotations: dict[str, dict],
    allowed_emojis: set[str],
    categories: dict[str, str],
) -> None:
    index: dict[str, list[str]] = {}
    for emoji, payload in english_annotations.items():
        if emoji not in allowed_emojis or not isinstance(payload, dict):
            continue
        values = list(payload.get("tts") or []) + list(payload.get("default") or [])
        for value in values:
            shortcode = normalize_shortcode(str(value))
            if not shortcode:
                continue
            matches = index.setdefault(shortcode, [])
            if emoji not in matches:
                matches.append(emoji)
    path.write_text(
        json.dumps(
            {
                "provider": "unicode",
                "categories": dict(sorted(categories.items())),
                "shortcodes": dict(sorted(index.items())),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ) + "\n",
        encoding="utf-8",
    )


def emoji_categories(emoji_dir: Path) -> dict[str, str]:
    files = load_category_files(emoji_dir)
    return {
        emoji: file_name.removesuffix(".txt")
        for file_name, lines in files.items()
        for line in lines
        for emoji in line
    }


def rebuild_shortcodes_from_committed_search(root: Path) -> None:
    search_path = root / "app/src/main/assets/common/emoji_search/en.tsv"
    annotations: dict[str, dict[str, list[str]]] = {}
    for line in search_path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        emoji = parts[0]
        name = parts[1]
        keywords = parts[2].split("|") if len(parts) > 2 and parts[2] else []
        annotations[emoji] = {"tts": [name] if name else [], "default": keywords}
    emoji_dir = root / "app/src/main/assets/common/emoji"
    write_shortcode_index(
        root / "app/src/main/assets/common/emoji_shortcodes.json",
        annotations,
        load_project_emojis(emoji_dir),
        emoji_categories(emoji_dir),
    )


def parse_emoji_presentation(text: str) -> set[int]:
    codepoints: set[int] = set()
    for line in text.splitlines():
        data = line.split("#", 1)[0].strip()
        if not data or "Emoji_Presentation" not in data.split(";", 1)[1]:
            continue
        span = data.split(";", 1)[0].strip()
        start, _, end = span.partition("..")
        codepoints.update(range(int(start, 16), int(end or start, 16) + 1))
    return codepoints


def build_symbol_rows(unicode_data: str, emoji_presentation: set[int]) -> list[tuple[int, str]]:
    rows: list[tuple[int, str]] = []
    for line in unicode_data.splitlines():
        fields = line.split(";")
        if len(fields) < 3 or fields[2] not in SYMBOL_CATEGORIES:
            continue
        codepoint = int(fields[0], 16)
        name = fields[1]
        if name.startswith("<") or codepoint in emoji_presentation:
            continue
        # Regional indicators and skin tone modifiers only form emoji.
        if 0x1F1E6 <= codepoint <= 0x1F1FF or 0x1F3FB <= codepoint <= 0x1F3FF:
            continue
        rows.append((codepoint, name.lower()))
    return rows


def write_symbol_tsv(path: Path, rows: list[tuple[int, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"# Plektra symbol search: code point (hex) and Unicode name. Unicode {UNICODE_EMOJI_VERSION}.0.\n"
        "# Symbols, punctuation and numeric symbols, except those with emoji presentation.\n"
        + "".join(f"{codepoint:X}\t{name}\n" for codepoint, name in rows),
        encoding="utf-8",
    )


def build_symbol_search_rows(
    annotations: dict[str, dict], symbols: set[str]
) -> list[tuple[str, str, list[str]]]:
    # CLDR annotates many text symbols too (→, €, ±): their localized names make the symbol
    # search work in every search language.
    return build_search_rows(annotations, symbols)


def ensure_noto_font(root: Path) -> None:
    path = root / NOTO_EMOJI_FONT_ASSET
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == NOTO_EMOJI_FONT_SHA256:
        return
    with urllib.request.urlopen(NOTO_EMOJI_FONT_URL, timeout=120) as response:
        payload = response.read()
    digest = hashlib.sha256(payload).hexdigest()
    if digest != NOTO_EMOJI_FONT_SHA256:
        raise SystemExit(f"Noto Color Emoji checksum mismatch: {digest}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def generate(root: Path) -> tuple[str, int]:
    emoji_dir = root / "app/src/main/assets/common/emoji"
    search_dir = root / "app/src/main/assets/common/emoji_search"
    shortcode_path = root / "app/src/main/assets/common/emoji_shortcodes.json"
    symbols_path = root / "app/src/main/assets/common/symbols/symbols.tsv"
    symbol_search_dir = root / "app/src/main/assets/common/symbol_search"
    emoji_version, records = parse_emoji_test(fetch_text(EMOJI_TEST_URL))
    added_count = update_categories(emoji_dir, records)
    allowed_emojis = load_project_emojis(emoji_dir)
    annotations_by_locale = {locale: fetch_annotations(locale) for locale in SEARCH_LOCALES}
    for locale, annotations in annotations_by_locale.items():
        write_search_tsv(search_dir / f"{locale}.tsv", build_search_rows(annotations, allowed_emojis))
    write_shortcode_index(
        shortcode_path,
        annotations_by_locale["en"],
        allowed_emojis,
        emoji_categories(emoji_dir),
    )
    symbol_rows = build_symbol_rows(
        fetch_text(UNICODE_DATA_URL), parse_emoji_presentation(fetch_text(EMOJI_DATA_URL))
    )
    write_symbol_tsv(symbols_path, symbol_rows)
    symbol_set = {chr(codepoint) for codepoint, _ in symbol_rows} - allowed_emojis
    for locale, annotations in annotations_by_locale.items():
        write_search_tsv(
            symbol_search_dir / f"{locale}.tsv", build_symbol_search_rows(annotations, symbol_set)
        )
    ensure_noto_font(root)
    return emoji_version, added_count


def generated_files(root: Path) -> list[Path]:
    common = root / "app/src/main/assets/common"
    return sorted((common / "emoji").glob("*.txt")) + sorted(
        (common / "emoji_search").glob("*.tsv")
    ) + sorted((common / "symbol_search").glob("*.tsv")) + [
        common / "emoji_shortcodes.json",
        common / "symbols" / "symbols.tsv",
        root / NOTO_EMOJI_FONT_ASSET,
    ]


def check(root: Path) -> int:
    with tempfile.TemporaryDirectory(prefix="plektra-emoji-check-") as temp_dir:
        temp_root = Path(temp_dir)
        source_common = root / "app/src/main/assets/common"
        target_common = temp_root / "app/src/main/assets/common"
        shutil.copytree(source_common / "emoji", target_common / "emoji")
        shutil.copytree(source_common / "emoji_search", target_common / "emoji_search")
        shortcode_source = source_common / "emoji_shortcodes.json"
        if shortcode_source.exists():
            shutil.copy2(shortcode_source, target_common / shortcode_source.name)
        font_source = root / NOTO_EMOJI_FONT_ASSET
        if font_source.exists():
            (temp_root / NOTO_EMOJI_FONT_ASSET).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(font_source, temp_root / NOTO_EMOJI_FONT_ASSET)
        generate(temp_root)
        stale: list[str] = []
        for generated in generated_files(temp_root):
            relative = generated.relative_to(temp_root)
            committed = root / relative
            if not committed.exists() or not filecmp.cmp(generated, committed, shallow=False):
                stale.append(str(relative))
        if stale:
            print("Generated emoji assets are stale:", file=sys.stderr)
            for path in stale:
                print(f"  {path}", file=sys.stderr)
            return 1
    print("Generated emoji assets are current")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify assets without modifying the checkout")
    parser.add_argument(
        "--rebuild-shortcodes-from-search",
        action="store_true",
        help="rebuild only the compact index from the committed English search asset",
    )
    args = parser.parse_args()
    root = repo_root_from_script()
    if args.rebuild_shortcodes_from_search:
        rebuild_shortcodes_from_committed_search(root)
        return 0
    if args.check:
        return check(root)
    emoji_version, added_count = generate(root)
    print(f"Unicode Emoji {emoji_version}; added picker entries: {added_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
