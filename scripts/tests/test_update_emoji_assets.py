import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    'update_emoji_assets', Path(__file__).resolve().parents[1] / 'update_emoji_assets.py'
)
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)

# emoji-test.txt excerpt: a new hand after an existing couple, like Unicode 18 added 🫹 after 💑.
EMOJI_TEST = """# Version: 18.0
# group: People & Body
# subgroup: hand-fingers-open
1F44B                                                  ; fully-qualified     # 👋 E0.6 waving hand
1F44B 1F3FD                                            ; fully-qualified     # 👋🏽 E1.0 waving hand: medium skin tone
1F91D                                                  ; fully-qualified     # 🤝 E3.0 handshake
1FAF1 1F3FB 200D 1FAF2 1F3FC                           ; fully-qualified     # 🫱🏻‍🫲🏼 E14.0 handshake: light skin tone, medium-light skin tone
# subgroup: family
1F491                                                  ; fully-qualified     # 💑 E0.6 couple with heart
1F491 1F3FB                                            ; fully-qualified     # 💑🏻 E13.1 couple with heart: light skin tone
1F9D1 1F3FB 200D 2764 FE0F 200D 1F9D1 1F3FC            ; fully-qualified     # 🧑🏻‍❤️‍🧑🏼 E13.1 couple with heart: person, person, light skin tone, medium-light skin tone
1FAF9                                                  ; fully-qualified     # 🫹 E18.0 new hand
1FAF9 1F3FB                                            ; fully-qualified     # 🫹🏻 E18.0 new hand: light skin tone
1FAF9 1F3FF                                            ; fully-qualified     # 🫹🏿 E18.0 new hand: dark skin tone
1F9D1 200D 1FA70                                       ; fully-qualified     # 🧑‍🩰 E18.0 ballet dancer
1F9D1 1F3FD 200D 1FA70                                 ; fully-qualified     # 🧑🏽‍🩰 E18.0 ballet dancer: medium skin tone
# group: Component
1F3FB                                                  ; component           # 🏻 E1.0 light skin tone
"""


class CategoryLinesTest(unittest.TestCase):
    def setUp(self):
        _, self.records = assets.parse_emoji_test(EMOJI_TEST)

    def test_every_variant_is_on_its_own_base_line(self):
        lines = assets.build_category_lines(self.records)['PEOPLE_AND_BODY.txt']
        self.assertEqual(
            [
                ['👋', '👋🏽'],
                ['🤝', '🫱🏻‍🫲🏼'],
                ['💑', '💑🏻', '🧑🏻‍❤️‍🧑🏼'],
                ['🫹', '🫹🏻', '🫹🏿'],
                ['🧑‍🩰', '🧑🏽‍🩰'],
            ],
            lines,
        )

    def test_update_keeps_curated_levels_and_gates_new_emoji(self):
        with tempfile.TemporaryDirectory() as folder:
            emoji_dir = Path(folder)
            for file_name in assets.GROUP_TO_FILE.values():
                (emoji_dir / file_name).write_text('', encoding='utf-8')
            # 💑 was already offered (with a misplaced line), its level curated by hand
            (emoji_dir / 'PEOPLE_AND_BODY.txt').write_text('👋 👋🏽\n🤝\n💑 💑🏻 🫹🏻\n', encoding='utf-8')
            (emoji_dir / 'minApi.txt').write_text('23 💑\n30 💑🏻 🫹🏻\n', encoding='utf-8')
            assets.update_categories(emoji_dir, self.records)
            self.assertEqual(
                '👋 👋🏽\n🤝 🫱🏻‍🫲🏼\n💑 💑🏻 🧑🏻‍❤️‍🧑🏼\n🫹 🫹🏻 🫹🏿\n🧑‍🩰 🧑🏽‍🩰\n',
                (emoji_dir / 'PEOPLE_AND_BODY.txt').read_text(encoding='utf-8'),
            )
            self.assertEqual(
                '0 🫹 🫹🏿 🧑‍🩰 🧑🏽‍🩰\n23 💑\n30 💑🏻 🫹🏻\n31 🧑🏻‍❤️‍🧑🏼\n32 🫱🏻‍🫲🏼\n',
                (emoji_dir / 'minApi.txt').read_text(encoding='utf-8'),
            )


if __name__ == '__main__':
    unittest.main()
