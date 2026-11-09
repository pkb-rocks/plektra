#!/usr/bin/env python3
"""Verify that the bundled Noto Color Emoji font draws every emoji the picker offers.

Shapes each emoji from assets/common/emoji/*.txt with HarfBuzz and fails when one does not
become a single glyph of the bundled font. Run with:

    uvx --with uharfbuzz python -I scripts/verify_emoji_font_coverage.py
"""

from __future__ import annotations

import sys
from pathlib import Path

import uharfbuzz as hb

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / "app/src/main/assets/fonts/NotoColorEmoji-emojicompat.ttf"
EMOJI_DIR = ROOT / "app/src/main/assets/common/emoji"


def picker_emoji() -> list[str]:
    emoji: list[str] = []
    for path in sorted(EMOJI_DIR.glob("*.txt")):
        if path.name == "minApi.txt":
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            emoji.extend(token for token in line.split(" ") if token)
    return emoji


def main() -> int:
    font = hb.Font(hb.Face(hb.Blob.from_file_path(str(FONT))))
    emoji = picker_emoji()
    missing = []
    for sequence in emoji:
        buffer = hb.Buffer()
        buffer.add_str(sequence)
        buffer.guess_segment_properties()
        hb.shape(font, buffer)
        glyphs = [info.codepoint for info in buffer.glyph_infos]
        if len(glyphs) != 1 or glyphs[0] == 0:
            missing.append(sequence)
    if missing:
        print(f"{len(missing)} of {len(emoji)} picker emoji are not drawn by {FONT.name}:", file=sys.stderr)
        for sequence in missing[:50]:
            print("  " + " ".join(f"U+{ord(ch):04X}" for ch in sequence), file=sys.stderr)
        return 1
    print(f"{FONT.name} draws all {len(emoji)} picker emoji")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
