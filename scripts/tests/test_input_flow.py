import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('input_flow', Path(__file__).resolve().parents[1] / 'input_flow.py')
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)

# Like the Titan 2 Elite: the kernel device reports only the right Shift key.
KEY_CODES = {'Q': 16, 'W': 17, 'E': 18, 'A': 30, 'N': 49, 'SPACE': 57, 'ENTER': 28, 'SHIFT_RIGHT': 54, 'ALT_LEFT': 56}
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

    def test_screen_targets_include_the_emoji_picker(self):
        ops = self.plan('screen press bar:emoji\nscreen hold emoji:👋\nwait 1000\nscreen release emoji:👋\n'
                        'screen press variant:👋🏽\nscreen press picker:search')
        self.assertEqual([('bar:emoji', True), ('bar:emoji', False), ('emoji:👋', True), ('emoji:👋', False),
                          ('variant:👋🏽', True), ('variant:👋🏽', False),
                          ('picker:search', True), ('picker:search', False)], edges(ops))

    def test_screen_only_flows_need_no_hardware_keyboard(self):
        self.assertFalse(flow.needs_hardware(flow.parse_flow('screen press bar:emoji\nexpect x')))
        self.assertTrue(flow.needs_hardware(flow.parse_flow('screen press bar:emoji\nhw press Q\nexpect x')))
        self.assertTrue(flow.needs_hardware(flow.parse_flow('require profile p\nexpect x')))

    def test_preserve_keyboard_mode_does_not_force_software_keyboard(self):
        self.assertTrue(flow.forces_software_keyboard(flow.parse_flow('screen press bar:emoji\nexpect x')))
        self.assertFalse(flow.forces_software_keyboard(flow.parse_flow(
            'preserve keyboard mode\nhw press AGUI_SYM\nscreen press emoji:😀\nexpect x')))
        self.assertFalse(flow.forces_software_keyboard(flow.parse_flow('hw press Q\nexpect x')))
        self.assertFalse(flow.needs_hardware(flow.parse_flow('preserve keyboard mode\nexpect x')))
        with self.assertRaises(flow.FlowError):
            flow.parse_flow('require keyboard hardware\nexpect x')
        with self.assertRaises(flow.FlowError):
            flow.parse_flow('preserve keyboard hardware\nexpect x')


class RepeatedKeyTest(unittest.TestCase):
    def down_times(self, ops, key):
        clock, times = 0, []
        for op in ops:
            if op['op'] == 'wait':
                clock += op['ms']
            elif op['op'] == 'key' and op['down'] and op['key'] == key:
                times.append(clock)
        return times

    def test_type_waits_out_multi_tap_cycle(self):
        greek = {'layoutSetting': 'greek', 'multiTapTimeoutMs': 400, 'alt': {},
                 'layout': {'KEYCODE_E': {'lowercase': 'ε', 'uppercase': 'Ε', 'multiTap': True},
                            'KEYCODE_Q': {'lowercase': ';', 'uppercase': ':', 'multiTap': False}}}
        ops = flow.plan(flow.parse_flow('hw type εε;;\nexpect x'), greek, KEY_CODES)
        first, second = self.down_times(ops, 'E')
        self.assertGreater(second - first, 400)
        first, second = self.down_times(ops, 'Q')
        self.assertLess(second - first, 400)

    def test_explicit_presses_keep_multi_tap_timing(self):
        greek = {'multiTapTimeoutMs': 400, 'alt': {},
                 'layout': {'KEYCODE_E': {'lowercase': 'ε', 'uppercase': 'Ε', 'multiTap': True}}}
        ops = flow.plan(flow.parse_flow('hw press E\nhw press E\nexpect έ'), greek, KEY_CODES)
        first, second = self.down_times(ops, 'E')
        self.assertLess(second - first, 400)

    def bounce_plan(self, text, keys):
        keymap = dict(KEYMAP, bounceKeysDelayMs={f'KEYCODE_{key}': 300 for key in keys})
        return flow.plan(flow.parse_flow(f'hw type {text}\nexpect x'), keymap, KEY_CODES)

    def gaps(self, ops, key):
        times = self.down_times(ops, key)
        return [later - earlier for earlier, later in zip(times, times[1:])]

    def test_type_waits_out_bounce_keys(self):
        self.assertTrue(all(gap > 300 for gap in self.gaps(self.bounce_plan('aa', ['A']), 'A')))

    def test_modifier_bounce_spaces_repeated_alt_chords(self):
        # Alt+Q, Alt+W = "01": the second Alt down must not fall inside the modifier delay.
        ops = self.bounce_plan('01', ['ALT_LEFT'])
        self.assertEqual([('ALT_LEFT', True), ('Q', True), ('Q', False), ('ALT_LEFT', False),
                          ('ALT_LEFT', True), ('W', True), ('W', False), ('ALT_LEFT', False)], edges(ops))
        self.assertTrue(all(gap > 300 for gap in self.gaps(ops, 'ALT_LEFT')))
        # Character keys differ, so only the modifier delay applies.
        self.assertLess(self.gaps(ops, 'ALT_LEFT')[0], 300 + 100 + 80)

    def test_space_and_enter_bounce_have_their_own_categories(self):
        ops = self.bounce_plan('a  \n\n', ['SPACE', 'ENTER'])
        self.assertTrue(all(gap > 300 for gap in self.gaps(ops, 'SPACE')))
        self.assertTrue(all(gap > 300 for gap in self.gaps(ops, 'ENTER')))

    def test_disabled_categories_keep_fast_typing(self):
        # Only Space bounces: repeated Alt chords and letters keep the 80 ms rhythm.
        ops = self.bounce_plan('01aa', ['SPACE'])
        self.assertEqual([120], self.gaps(ops, 'ALT_LEFT'))  # down, 40 ms, Q, 40 ms press, ups, 40 ms
        self.assertEqual([80], self.gaps(ops, 'A'))
        self.assertEqual([], self.gaps(self.bounce_plan('0a1', []), 'Q'))

    def test_bounce_and_multi_tap_combine(self):
        greek = {'multiTapTimeoutMs': 400, 'alt': {}, 'bounceKeysDelayMs': {'KEYCODE_E': 600},
                 'layout': {'KEYCODE_E': {'lowercase': 'ε', 'uppercase': 'Ε', 'multiTap': True}}}
        ops = flow.plan(flow.parse_flow('hw type εε\nexpect x'), greek, KEY_CODES)
        self.assertGreater(self.gaps(ops, 'E')[0], 600)

    def test_explicit_presses_keep_their_timing_with_bounce(self):
        keymap = dict(KEYMAP, bounceKeysDelayMs={'KEYCODE_ALT_LEFT': 300})
        ops = flow.plan(flow.parse_flow('hw press ALT_LEFT\nhw press ALT_LEFT\nexpect x'), keymap, KEY_CODES)
        self.assertEqual([80], self.gaps(ops, 'ALT_LEFT'))


REAL = {'id': 3, 'name': 'TitanKey', 'vendorId': 9523, 'productId': 9523, 'isExternal': False,
        'keys': {'KEYCODE_Q': {'base': 'q'}}}
HUB = {'Classes': 'KEYBOARD | ALPHAKEY',
       'Identifier': 'bus=0x0000, vendor=0x2533, product=0x2533, version=0x0000, bluetoothAddress=<not set>',
       'KeyLayoutFile': '/system/usr/keylayout/TitanKey.kl', 'Path': '/dev/input/event5'}


class CompareKeyboardsTest(unittest.TestCase):
    def compare(self, clone_hub=None, clone=None, bits=(16, 54)):
        hub = dict(HUB, Classes='KEYBOARD | ALPHAKEY | BATTERY',
                   Identifier=HUB['Identifier'].replace('bus=0x0000', 'bus=0x0019'), Path='/dev/input/event8')
        hub.update(clone_hub or {})
        return flow.compare_keyboards(REAL, dict(REAL, id=10, **(clone or {})), HUB, hub, [16, 54], list(bits))

    def test_accepts_exactly_the_known_uinput_differences(self):
        lines, unexplained = self.compare()
        self.assertEqual(0, unexplained)
        self.assertEqual(2, sum(line.startswith('~') for line in lines))

    def test_rejects_other_class_and_identifier_differences(self):
        cases = [{'Classes': 'KEYBOARD | BATTERY'},
                 {'Classes': 'KEYBOARD | ALPHAKEY | BATTERY | SENSOR'},
                 {'Identifier': HUB['Identifier'].replace('bus=0x0000', 'bus=0x0003')},
                 {'Identifier': HUB['Identifier'].replace('bus=0x0000', 'bus=0x0019').replace('version=0x0000', 'version=0x0001')},
                 {'Identifier': HUB['Identifier'].replace('bus=0x0000', 'bus=0x0019').replace('product=0x2533', 'product=0x2534')},
                 {'KeyLayoutFile': '/system/usr/keylayout/Generic.kl'}]
        for case in cases:
            self.assertGreater(self.compare(clone_hub=case)[1], 0, case)
        self.assertGreater(self.compare(clone={'isExternal': True})[1], 0)
        self.assertGreater(self.compare(clone={'keys': {'KEYCODE_Q': {'base': 'Q'}}})[1], 0)
        self.assertGreater(self.compare(bits=(16, 42))[1], 0)


class FakeAdb:
    package = 'rocks.pkb.plektra.nightly'

    def __init__(self, responses=None):
        self.calls, self.responses = [], responses or {}

    def broadcast(self, extra):
        self.calls.append(extra)
        response = self.responses.get(extra, '')
        return response.pop(0) if isinstance(response, list) else response

    def shell(self, command, check=True, timeout=30):
        self.calls.append(command)
        return ''

    def node(self, resource_id):
        return {'bounds': '[0,0][10,10]'}


class BrokenPipe:
    closed = False

    def write(self, data):
        raise BrokenPipeError(32, 'Broken pipe')

    def flush(self):
        pass

    def close(self):
        raise BrokenPipeError(32, 'Broken pipe')


class FinishedProcess:
    stdin = BrokenPipe()

    def poll(self):
        return None

    def wait(self, timeout=None):
        return 0


class CleanupTest(unittest.TestCase):
    def session_with_broken_pipe(self):
        session = flow.Session(FakeAdb())
        session.uinput = flow.Uinput.__new__(flow.Uinput)
        session.uinput.process = FinishedProcess()
        session.uinput.slots, session.uinput.keys_down = {'plektra_key_shift': 0}, {42}
        session.forced_software = True
        return session

    def test_broken_pipe_still_clears_software_mode(self):
        session = self.session_with_broken_pipe()
        errors = session.close()
        self.assertIn('clearSoftwareOverride', session.adb.calls)
        self.assertFalse(session.forced_software)
        self.assertEqual(1, len(errors))
        self.assertIn('release held input', errors[0])

    def test_cleanup_failure_does_not_hide_the_flow_error(self):
        flow.finish(self.session_with_broken_pipe(), RuntimeError('flow failed'))
        with self.assertRaisesRegex(flow.FlowError, 'cleanup failed: release held input'):
            flow.finish(self.session_with_broken_pipe(), None)


class NoteBodyTest(unittest.TestCase):
    def test_reads_real_text_even_if_it_equals_the_hint(self):
        session = flow.Session(FakeAdb({'noteBody': '{"text":"Write a note…","focused":true}'}))
        self.assertEqual('Write a note…', session.body_text())
        session = flow.Session(FakeAdb({'noteBody': '{"text":"","focused":true}'}))
        self.assertEqual('', session.body_text())

    def test_refuses_to_type_into_an_existing_note(self):
        # The new-note tap was lost: the previously active note stays selected.
        notes = '{"ids":["old"],"active":"old"}'
        session = flow.Session(FakeAdb({'notes': [notes] * 20, 'noteBody': '{"text":"","focused":true}'}))
        session.timeout = 0.5
        session.adb.shell = lambda command, check=True, timeout=30: (
            'mCurrentFocus=Window{1 u0 rocks.pkb.plektra.nightly/rocks.pkb.plektra.NotesActivity}')
        with self.assertRaisesRegex(flow.FlowError, 'new note'):
            session.open_new_note(False)

    def test_finds_a_new_note_button_without_child_nodes(self):
        # A parsed element without children is falsy; the Fold reports the alias as focused window.
        notes = ['{"ids":["old"],"active":"old"}', '{"ids":["old"],"active":"old"}',
                 '{"ids":["old","new"],"active":"new"}']
        session = flow.Session(FakeAdb({'notes': notes, 'noteBody': '{"text":"","focused":true}'}))
        session.timeout = 0.5
        session.adb.node = lambda resource_id: ET.fromstring('<node bounds="[0,0][10,10]"/>')
        session.adb.shell = lambda command, check=True, timeout=30: (
            'mCurrentFocus=Window{1 u0 rocks.pkb.plektra.nightly/rocks.pkb.plektra.NotesEditorAlias}')
        session.open_new_note(False)


class DeviceHelpersTest(unittest.TestCase):
    def test_screen_targets_read_keys_bar_button_cells_and_variants(self):
        inspect = ('SoftwareKey: id=plektra_key_q, label=q, screen=40,1700 | BarButton: id=emoji, screen=826,1507 | '
                   'EmojiTarget: kind=cell, text=👋, screen=80,1660 | EmojiTarget: kind=cell, text=👋, screen=700,1660 | '
                   'EmojiTarget: kind=variant, text=👋🏽, screen=540,1590')
        session = flow.Session(FakeAdb({'inspect': inspect}))
        self.assertEqual({'plektra_key_q': (40, 1700), 'bar:emoji': (826, 1507), 'emoji:👋': (80, 1660),
                          'variant:👋🏽': (540, 1590)}, session.screen_targets())
        self.assertEqual({'plektra_key_q': (40, 1700)}, session.software_keys())

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
