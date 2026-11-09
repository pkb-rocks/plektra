import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('input_flow', Path(__file__).resolve().parents[1] / 'input_flow.py')
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)

# Like the Titan 2 Elite: the kernel device reports only the right Shift key.
KEY_CODES = {'Q': 16, 'W': 17, 'A': 30, 'N': 49, 'SPACE': 57, 'ENTER': 28, 'SHIFT_RIGHT': 54, 'ALT_LEFT': 56}
KEYMAP = {
    'layoutSetting': 'qwerty',
    'layout': {
        'KEYCODE_Q': {'lowercase': 'q', 'uppercase': 'Q', 'multiTap': False},
        'KEYCODE_W': {'lowercase': 'w', 'uppercase': 'W', 'multiTap': False},
        'KEYCODE_A': {'lowercase': 'a', 'uppercase': 'A', 'multiTap': False},
        'KEYCODE_N': {'lowercase': 'n', 'uppercase': 'N', 'multiTap': False},
    },
    'alt': {'KEYCODE_Q': '0', 'KEYCODE_W': '1', 'KEYCODE_N': ',', 'KEYCODE_A': 'a'},
}


def edges(ops):
    return [(op.get('key') or op.get('target'), op['down']) for op in ops if op['op'] in ('key', 'touch')]


class ParseTest(unittest.TestCase):
    def test_parses_steps_and_quoted_text(self):
        steps = flow.parse_flow('# c\nhw type Qa 1\nhw hold ALT_LEFT\nwait 20\nscreen press plektra_key_q\n'
                                'expect "Qa 1\\n"\nrequire layout qwerty\n')
        self.assertEqual([('type', 'hw', 'Qa 1'), ('hold', 'hw', 'ALT_LEFT'), ('wait', None, '20'),
                          ('press', 'screen', 'plektra_key_q'), ('expect', None, 'Qa 1\n'),
                          ('require-layout', None, 'qwerty')],
                         [(s.action, s.surface, s.value) for s in steps])

    def test_rejects_unknown_steps_and_missing_expectation(self):
        for text in ('hw tap Q\nexpect x', 'hw press Q W\nexpect x', 'click Q\nexpect x', 'wait soon\nexpect x',
                     'hw press Q'):
            with self.assertRaises(flow.FlowError, msg=text):
                flow.parse_flow(text)

    def test_reads_key_layout(self):
        self.assertEqual({'Q': 16, 'HOME': 102}, flow.parse_key_layout('# x\nkey 16    Q\nkey 102 HOME # c\n'))


class PlanTest(unittest.TestCase):
    def plan(self, text):
        return flow.plan(flow.parse_flow(text + '\nexpect x'), KEYMAP, KEY_CODES, press_ms=0, gap_ms=0)

    def test_type_uses_fewest_modifiers_and_real_scan_codes(self):
        ops = self.plan('hw type Qa0,\n')
        self.assertEqual([('SHIFT_RIGHT', True), ('Q', True), ('Q', False), ('SHIFT_RIGHT', False),
                          ('A', True), ('A', False),
                          ('ALT_LEFT', True), ('Q', True), ('Q', False), ('ALT_LEFT', False),
                          ('ALT_LEFT', True), ('N', True), ('N', False), ('ALT_LEFT', False)], edges(ops))
        self.assertEqual(16, ops[1]['scan'])

    def test_unreachable_character_fails_before_injection(self):
        with self.assertRaisesRegex(flow.FlowError, r"line 1: no hardware key produces 'ß' \(U\+00DF\)"):
            self.plan('hw type aß')

    def test_hold_and_release_must_pair(self):
        ops = self.plan('hw hold ALT_LEFT\nhw press Q\nhw release ALT_LEFT')
        self.assertEqual([('ALT_LEFT', True), ('Q', True), ('Q', False), ('ALT_LEFT', False)], edges(ops))
        for text in ('hw release Q', 'hw hold Q\nhw hold Q', 'hw hold Q', 'hw hold Q\nhw type q'):
            with self.assertRaises(flow.FlowError, msg=text):
                self.plan(text)

    def test_unknown_hardware_key_is_reported(self):
        with self.assertRaisesRegex(flow.FlowError, "no key 'TAB'"):
            self.plan('hw press TAB')

    def test_screen_type_uses_accessibility_ids_and_shift(self):
        ops = self.plan('screen type Hi, 2')
        self.assertEqual(['plektra_key_shift', 'plektra_key_h', 'plektra_key_i', 'plektra_key_comma',
                          'plektra_key_space', 'plektra_key_2'],
                         [target for target, down in edges(ops) if down])
        with self.assertRaises(flow.FlowError):
            self.plan('screen press Q')

    def test_screen_chord_keeps_modifier_down(self):
        ops = self.plan('screen hold plektra_key_shift\nscreen press plektra_key_a\nscreen release plektra_key_shift')
        self.assertEqual([('plektra_key_shift', True), ('plektra_key_a', True), ('plektra_key_a', False),
                          ('plektra_key_shift', False)], edges(ops))


class DeviceHelpersTest(unittest.TestCase):
    def test_touch_coordinates_follow_viewport(self):
        uinput = flow.Uinput.__new__(flow.Uinput)
        uinput.transform = {'logical': [0, 0, 1076, 1200], 'physical': [2, 0, 1078, 1200], 'device': [1080, 1200]}
        self.assertEqual((2, 0), uinput.raw((0, 0)))
        self.assertEqual((540, 600), uinput.raw((538, 600)))

    def test_event_hub_block_distinguishes_clone(self):
        dump = ('Event Hub State:\n  Devices:\n    10: TitanKey\n      Classes: KEYBOARD | ALPHAKEY | BATTERY\n'
                '      Path: /dev/input/event8\n      Location: uinput:1:9523:9523\n'
                '    3: TitanKey (aka device 0 - built-in keyboard)\n      Classes: KEYBOARD | ALPHAKEY\n'
                '      Path: /dev/input/event5\n      Location: \n')
        self.assertEqual('/dev/input/event8', flow.event_hub_fields(flow.input_dump_block(dump, 'TitanKey', True))['Path'])
        self.assertEqual('/dev/input/event5', flow.event_hub_fields(flow.input_dump_block(dump, 'TitanKey', False))['Path'])


if __name__ == '__main__':
    unittest.main()
