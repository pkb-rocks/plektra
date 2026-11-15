#!/usr/bin/env python3
"""Drive Plektra with virtual hardware keys and real touch input in the Notes editor.

Examples:
  python3 scripts/input_flow.py --device SERIAL devices
  python3 scripts/input_flow.py --device SERIAL plan scripts/input-flows/alt-lock.flow
  python3 scripts/input_flow.py --device SERIAL run scripts/input-flows/alt-lock.flow

Hardware steps go through a uinput clone of the built-in keyboard: same name,
vendor/product, key bits and therefore the same key layout and key character
map files. Screen steps touch the software keyboard through a virtual uinput
touchscreen at the bounds of Plektra's accessibility key nodes or emoji picker
cells. Every run opens
the real NotesActivity, creates a new note and checks the editor text through
the UI hierarchy. See docs/testing/input-flows.md.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
from typing import NamedTuple
import xml.etree.ElementTree as ET

EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
BTN_TOUCH = 330
ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID = 47, 53, 54, 57
INPUT_PROP_DIRECT = 1
KEYBOARD_ID, TOUCH_ID = 1, 2
TOUCH_NAME = 'plektra-input-flow-touch'
ROOT = Path(__file__).resolve().parents[1]
RECEIVER = 'rocks.pkb.plektra.KeyboardBarTestReceiver'
NOTES_ALIAS = 'rocks.pkb.plektra.NotesEditorAlias'
SURFACES = ('hw', 'screen')
# Software keys by accessibility id, the bar's emoji button, picker cells and skin-tone variants by text.
SCREEN_TARGET_PREFIXES = ('plektra_key_', 'bar:', 'emoji:', 'variant:')
ACTIONS = ('type', 'press', 'hold', 'release')
# Mirrors AospKeyboardView.accessibilityTextToken for characters typed on screen.
SCREEN_TOKENS = {
    ',': 'comma', '.': 'period', ';': 'semicolon', ':': 'colon', '!': 'exclamation',
    '?': 'question', "'": 'apostrophe', '"': 'quote', '/': 'slash', '\\': 'backslash',
    '+': 'plus', '-': 'minus', '=': 'equals', '[': 'left_bracket', ']': 'right_bracket',
    '(': 'left_parenthesis', ')': 'right_parenthesis',
}


MODIFIER_KEYS = {'SHIFT_LEFT', 'SHIFT_RIGHT', 'ALT_LEFT', 'ALT_RIGHT', 'CTRL_LEFT', 'CTRL_RIGHT'}
REPEAT_MARGIN_MS = 100
class FlowError(Exception):
    pass


class Step(NamedTuple):
    line: int
    action: str
    surface: str | None = None
    value: str = ''


def parse_text(raw, line):
    raw = raw.strip()
    if raw.startswith('"'):
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as error:
            raise FlowError(f'line {line}: invalid quoted text: {error.msg}') from None
        if not isinstance(value, str):
            raise FlowError(f'line {line}: quoted text must be a JSON string')
        return value
    return raw


def parse_flow(text):
    """Parse the line-based flow format documented in docs/testing/input-flows.md."""
    steps = []
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        head, _, rest = line.partition(' ')
        if head in SURFACES:
            action, _, value = rest.strip().partition(' ')
            if action not in ACTIONS:
                raise FlowError(f'line {number}: expected {"/".join(ACTIONS)} after {head}')
            value = parse_text(value, number) if action == 'type' else value.strip()
            if not value or (action != 'type' and ' ' in value):
                raise FlowError(f'line {number}: {head} {action} needs exactly one '
                                f'{"text" if action == "type" else "key"}')
            steps.append(Step(number, action, head, value))
        elif head == 'wait':
            if not re.fullmatch(r'\d+', rest.strip()):
                raise FlowError(f'line {number}: wait needs milliseconds')
            steps.append(Step(number, 'wait', value=rest.strip()))
        elif head == 'expect':
            steps.append(Step(number, 'expect', value=parse_text(rest, number)))
        elif head == 'require':
            name, _, value = rest.strip().partition(' ')
            if name not in ('layout', 'profile') or not value.strip():
                raise FlowError(f'line {number}: use "require layout NAME" or "require profile NAME"')
            steps.append(Step(number, f'require-{name}', value=value.strip()))
        else:
            raise FlowError(f'line {number}: unknown step {head!r}')
    if not any(step.action == 'expect' for step in steps):
        raise FlowError('flow has no expect step')
    return steps


def parse_key_layout(text):
    """Return {Android key label: Linux key code} from a .kl file."""
    keys = {}
    for line in text.splitlines():
        parts = line.split('#', 1)[0].split()
        if len(parts) >= 3 and parts[0] == 'key' and parts[1].isdigit():
            keys.setdefault(parts[2], int(parts[1]))
    return keys


def hardware_inventory(keymap, labels):
    """Map text to the key chord that Plektra resolves to it, preferring fewer modifiers."""
    inventory = {}

    def add(text, chord):
        if text and chord[-1] in labels and all(m in labels for m in chord[:-1]):
            inventory.setdefault(text, chord)

    shift = next((key for key in ('SHIFT_LEFT', 'SHIFT_RIGHT') if key in labels), None)
    alt = next((key for key in ('ALT_LEFT', 'ALT_RIGHT') if key in labels), None)
    add(' ', ('SPACE',))
    add('\n', ('ENTER',))
    layout = {code.removeprefix('KEYCODE_'): mapping for code, mapping in keymap['layout'].items()}
    for label, mapping in layout.items():
        add(mapping['lowercase'], (label,))
    for label, mapping in layout.items():
        if shift and not mapping.get('multiTap'):
            add(mapping['uppercase'], (shift, label))
    for code, text in keymap['alt'].items():
        if alt:
            add(text, (alt, code.removeprefix('KEYCODE_')))
    return inventory


def screen_chords(char):
    if char == ' ':
        return [('plektra_key_space',)]
    if char == '\n':
        return [('plektra_key_enter',)]
    lower = char.lower()
    if len(lower) == 1 and ('a' <= lower <= 'z' or '0' <= lower <= '9'):
        key = f'plektra_key_{lower}'
        return [('plektra_key_shift',), (key,)] if char != lower else [(key,)]
    token = SCREEN_TOKENS.get(char) or '_'.join(f'u{ord(c):04x}' for c in lower)
    return [(f'plektra_key_{token}',)]


def plan(steps, keymap=None, key_codes=None, press_ms=40, gap_ms=40):
    """Expand steps into the intermediate operation list that `run` executes."""
    ops, held = [], {}
    clock, last_down, last_character_key = 0, {}, None

    def wait(line, ms):
        nonlocal clock
        if ms:
            ops.append({'line': line, 'op': 'wait', 'ms': ms})
            clock += ms

    def edge(line, surface, key, down):
        nonlocal last_character_key
        if surface == 'hw':
            if key not in key_codes:
                raise FlowError(f'line {line}: the hardware keyboard has no key {key!r}')
            ops.append({'line': line, 'op': 'key', 'down': down, 'key': key, 'scan': key_codes[key]})
            if down:
                last_down[key] = clock
                if key not in MODIFIER_KEYS:
                    last_character_key = key
        else:
            if not key.startswith(SCREEN_TARGET_PREFIXES):
                raise FlowError(f'line {line}: screen targets are accessibility ids such as plektra_key_q, '
                                'bar:emoji, emoji:TEXT or variant:TEXT')
            ops.append({'line': line, 'op': 'touch', 'down': down, 'target': key})

    def separate_repeat(line, keys):
        """Keep `type` from merging repeated keys into a multi-tap cycle or a bounce.

        Every key of the chord, modifiers included, goes down `gap_ms` after the previous one and must
        reach Plektra after its own Bounce Keys delay and, for the character key, the multi-tap timeout."""
        bounce = keymap.get('bounceKeysDelayMs') or {}
        delays = [0]
        for index, key in enumerate(keys):
            if key not in last_down:
                continue
            needed = bounce.get(f'KEYCODE_{key}', 0)
            if key == keys[-1] and key == last_character_key and key in multi_tap_keys:
                needed = max(needed, keymap['multiTapTimeoutMs'])
            if needed:
                delays.append(last_down[key] + needed + REPEAT_MARGIN_MS - (clock + gap_ms * index))
        wait(line, max(delays))

    def chord(line, surface, keys):
        for key in keys:
            if (surface, key) in held:
                raise FlowError(f'line {line}: {key} is held since line {held[surface, key]}')
            edge(line, surface, key, True)
            wait(line, press_ms if key == keys[-1] else gap_ms)
        for key in reversed(keys):
            edge(line, surface, key, False)
        wait(line, gap_ms)

    inventory = None
    multi_tap_keys = {code.removeprefix('KEYCODE_') for code, mapping in (keymap or {}).get('layout', {}).items()
                      if mapping.get('multiTap')}
    for step in steps:
        if step.action == 'wait':
            wait(step.line, int(step.value))
        elif step.action == 'expect':
            ops.append({'line': step.line, 'op': 'expect', 'text': step.value})
        elif step.action.startswith('require-'):
            ops.append({'line': step.line, 'op': step.action, 'value': step.value})
        elif step.action == 'press':
            chord(step.line, step.surface, (step.value,))
        elif step.action == 'hold':
            if (step.surface, step.value) in held:
                raise FlowError(f'line {step.line}: {step.value} is already held')
            edge(step.line, step.surface, step.value, True)
            held[step.surface, step.value] = step.line
            wait(step.line, gap_ms)
        elif step.action == 'release':
            if held.pop((step.surface, step.value), None) is None:
                raise FlowError(f'line {step.line}: {step.value} is not held')
            edge(step.line, step.surface, step.value, False)
            wait(step.line, gap_ms)
        elif step.surface == 'screen':
            for char in step.value:
                for keys in screen_chords(char):
                    chord(step.line, 'screen', keys)
        else:
            if inventory is None:
                inventory = hardware_inventory(keymap, key_codes)
            text, index = step.value, 0
            while index < len(text):
                match = max((t for t in inventory if text.startswith(t, index)), key=len, default=None)
                if match is None:
                    char = text[index]
                    raise FlowError(f'line {step.line}: no hardware key produces {char!r} '
                                    f'(U+{ord(char):04X}) in the active layout and Alt binding')
                separate_repeat(step.line, inventory[match])
                chord(step.line, 'hw', inventory[match])
                index += len(match)
    if held:
        surface, key = next(iter(held))
        raise FlowError(f'{surface} key {key} held on line {held[surface, key]} is never released')
    return ops


class Adb:
    def __init__(self, serial, package):
        self.serial, self.package = serial, package

    def shell(self, command, check=True, timeout=30):
        result = subprocess.run(['adb', '-s', self.serial, 'shell', command],
                                capture_output=True, text=True, timeout=timeout)
        if check and result.returncode:
            raise FlowError(f'adb shell {command!r} failed: {(result.stderr or result.stdout).strip()}')
        return result.stdout

    def broadcast(self, extra):
        output = self.shell(shlex.join(['am', 'broadcast', '-n', f'{self.package}/{RECEIVER}',
                                        '--ez', extra, 'true']))
        match = re.search(r'result=(-?\d+), data="(.*)"\s*$', output, re.S)
        if not match or match.group(1) != '0':
            raise FlowError(f'{extra} request failed: {output.strip()}')
        return match.group(2)

    def keyboard_devices(self):
        return json.loads(self.broadcast('devices'))

    def ui_nodes(self):
        for attempt in range(3):
            self.shell('uiautomator dump /data/local/tmp/input-flow-ui.xml', check=False)
            xml = self.shell('cat /data/local/tmp/input-flow-ui.xml; rm -f /data/local/tmp/input-flow-ui.xml',
                             check=False)
            if '<hierarchy' in xml:
                return list(ET.fromstring(xml[xml.index('<'):]).iter('node'))
            time.sleep(0.3)
        raise FlowError('uiautomator could not dump the UI hierarchy')

    def node(self, resource_id):
        full = f'{self.package}:id/{resource_id}'
        return next((n for n in self.ui_nodes() if n.get('resource-id') == full), None)


def center(bounds):
    left, top, right, bottom = map(int, re.findall(r'-?\d+', bounds))
    return (left + right) // 2, (top + bottom) // 2


class Uinput:
    """One `uinput` shell process hosting the virtual keyboard and touchscreen."""

    def __init__(self, adb):
        self.log = tempfile.TemporaryFile()
        self.process = subprocess.Popen(['adb', '-s', adb.serial, 'shell', '-T', 'uinput', '-'],
                                        stdin=subprocess.PIPE, stdout=self.log, stderr=subprocess.STDOUT)
        self.tracking_id, self.slots, self.transform, self.keys_down = 0, {}, None, set()

    def send(self, payload):
        if self.process.poll() is not None:
            self.log.seek(0)
            raise FlowError(f'uinput exited: {self.log.read().decode(errors="replace").strip()}')
        try:
            self.process.stdin.write((json.dumps(payload) + '\n').encode())
            self.process.stdin.flush()
        except OSError as error:
            raise FlowError(f'uinput input closed: {error}') from None

    def register_keyboard(self, reference, key_bits):
        self.send({'id': KEYBOARD_ID, 'command': 'register', 'name': reference['name'],
                   'vid': reference['vendorId'], 'pid': reference['productId'], 'bus': 'HOST',
                   'configuration': [{'type': 'UI_SET_EVBIT', 'data': [EV_KEY]},
                                     {'type': 'UI_SET_KEYBIT', 'data': key_bits}]})

    def register_touch(self, viewport):
        width, height = viewport['device']
        self.transform = viewport
        axis = lambda code, maximum: {'code': code, 'info': {'value': 0, 'minimum': 0, 'maximum': maximum,
                                                            'fuzz': 0, 'flat': 0, 'resolution': 0}}
        self.send({'id': TOUCH_ID, 'command': 'register', 'name': TOUCH_NAME, 'vid': 0, 'pid': 0,
                   'bus': 'VIRTUAL',
                   'configuration': [{'type': 'UI_SET_EVBIT', 'data': [EV_KEY, EV_ABS]},
                                     {'type': 'UI_SET_KEYBIT', 'data': [BTN_TOUCH]},
                                     {'type': 'UI_SET_ABSBIT', 'data': [ABS_MT_SLOT, ABS_MT_POSITION_X,
                                                                       ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID]},
                                     {'type': 'UI_SET_PROPBIT', 'data': [INPUT_PROP_DIRECT]}],
                   'abs_info': [axis(ABS_MT_SLOT, 9), axis(ABS_MT_POSITION_X, width - 1),
                                axis(ABS_MT_POSITION_Y, height - 1), axis(ABS_MT_TRACKING_ID, 65535)]})

    def key(self, scan, down):
        (self.keys_down.add if down else self.keys_down.discard)(scan)
        self.send({'id': KEYBOARD_ID, 'command': 'inject', 'events': [EV_KEY, scan, int(down), EV_SYN, 0, 0]})

    def touch(self, target, point, down):
        if down:
            slot = next(s for s in range(10) if s not in self.slots.values())
            self.slots[target] = slot
            self.tracking_id += 1
            x, y = self.raw(point)
            events = [EV_ABS, ABS_MT_SLOT, slot, EV_ABS, ABS_MT_TRACKING_ID, self.tracking_id,
                      EV_ABS, ABS_MT_POSITION_X, x, EV_ABS, ABS_MT_POSITION_Y, y]
            if len(self.slots) == 1:
                events += [EV_KEY, BTN_TOUCH, 1]
        else:
            slot = self.slots.pop(target)
            events = [EV_ABS, ABS_MT_SLOT, slot, EV_ABS, ABS_MT_TRACKING_ID, -1]
            if not self.slots:
                events += [EV_KEY, BTN_TOUCH, 0]
        self.send({'id': TOUCH_ID, 'command': 'inject', 'events': events + [EV_SYN, 0, 0]})

    def raw(self, point):
        logical, physical = self.transform['logical'], self.transform['physical']
        return tuple(round(physical[i] + (point[i] - logical[i]) * (physical[i + 2] - physical[i]) /
                           (logical[i + 2] - logical[i])) for i in (0, 1))

    def close(self):
        """End the shell command, which removes its virtual devices."""
        try:
            if self.process.stdin and not self.process.stdin.closed:
                self.process.stdin.close()
        except OSError:
            pass  # The command already exited; waiting below still reaps it.
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=10)


def layout_mismatches(keymap, name):
    """Compare the IME's loaded layout with a bundled layout file."""
    path = ROOT / 'app/src/main/assets/common/layouts' / f'{name}.json'
    if not path.is_file():
        raise FlowError(f'unknown layout {name!r}: {path} does not exist')
    expected = json.loads(path.read_text(encoding='utf-8'))['mappings']
    mismatches = []
    for code, mapping in expected.items():
        live = keymap['layout'].get(code, {})
        for case in ('lowercase', 'uppercase'):
            if case in mapping and live.get(case) != mapping[case]:
                mismatches.append(f'{code}.{case}={live.get(case)!r}')
    return mismatches


def built_in_keyboard(devices):
    candidates = [d for d in devices if not d['isExternal'] and not d['isVirtual'] and d['keyboardType'] == 2]
    if len(candidates) != 1:
        raise FlowError(f'expected one built-in alphabetic keyboard, found {[d["name"] for d in candidates]}')
    return candidates[0]


def input_dump_block(dump, name, virtual):
    """Return the EventHub block of a device; uinput devices report a uinput location."""
    for block in re.split(r'\n(?=\s{4}-?\d+: )', dump):
        header = block.strip().splitlines()[0] if block.strip() else ''
        location = re.search(r'Location: (.*)', block)
        if re.match(rf'-?\d+: {re.escape(name)}( |$)', header) and location and \
                location.group(1).strip().startswith('uinput') == virtual:
            return block
    return None


def event_hub_fields(block):
    fields = {}
    for key in ('Classes', 'Identifier', 'KeyLayoutFile', 'KeyCharacterMapFile', 'ConfigurationFile', 'Path'):
        match = re.search(rf'{key}: (.*)', block)
        fields[key] = match.group(1).strip() if match else ''
    return fields


def key_bits(adb, path):
    output = adb.shell(f'getevent -p {shlex.quote(path)}')
    section = output.split('KEY (0001):', 1)[1].split('input props:', 1)[0] if 'KEY (0001):' in output else ''
    return sorted(int(code, 16) for code in re.findall(r'\b[0-9a-f]{4}\b', section))


class Session:
    def __init__(self, adb, verbose=False):
        self.adb, self.verbose = adb, verbose
        self.uinput = None
        self.reference = None
        self.key_layout = {}
        self.forced_software = False
        self.timeout = 8

    def log(self, message):
        print(message, flush=True)

    def identity(self):
        props = {name: self.adb.shell(f'getprop {name}').strip() for name in
                 ('ro.product.model', 'ro.product.board', 'ro.build.display.id', 'ro.build.version.release')}
        self.log('device: ' + ', '.join(f'{k}={v}' for k, v in props.items()))
        return props

    def preflight(self):
        self.identity()
        ime = self.adb.shell('settings get secure default_input_method').strip()
        if not ime.startswith(self.adb.package + '/'):
            raise FlowError(f'{self.adb.package} is not the selected input method ({ime})')
        window = self.adb.shell('dumpsys window')
        if 'isKeyguardShowing=true' in window or 'mDreamingLockscreen=true' in window:
            raise FlowError('the device is locked; unlock it before running input flows')
        if 'mWakefulness=Awake' not in self.adb.shell('dumpsys power'):
            raise FlowError('the screen is off')

    def load_reference(self):
        """Read the built-in keyboard's Plektra view, EventHub entry and key layout file."""
        devices = self.adb.keyboard_devices()
        self.reference = built_in_keyboard(devices)
        block = input_dump_block(self.adb.shell('dumpsys input'), self.reference['name'], False)
        if not block:
            raise FlowError(f'no EventHub entry for {self.reference["name"]}')
        self.reference_hub = event_hub_fields(block)
        self.key_bits = key_bits(self.adb, self.reference_hub['Path'])
        # Only scan codes the kernel device reports can reach Android, whatever the .kl file lists.
        layout = parse_key_layout(self.adb.shell(f'cat {shlex.quote(self.reference_hub["KeyLayoutFile"])}'))
        self.key_layout = {label: scan for label, scan in layout.items() if scan in self.key_bits}
        return devices

    def start_hardware(self):
        devices = self.load_reference()
        self.ensure_uinput()
        self.uinput.register_keyboard(self.reference, self.key_bits)
        before = {d['id'] for d in devices}
        self.virtual_keyboard = self.wait_for(lambda: next(
            (d for d in self.adb.keyboard_devices() if d['id'] not in before and d['name'] == self.reference['name']),
            None), 'virtual keyboard registration')
        return self.key_layout

    def start_touch(self):
        viewport = self.viewport()
        self.ensure_uinput()
        self.uinput.register_touch(viewport)
        self.wait_for(lambda: input_dump_block(self.adb.shell('dumpsys input'), TOUCH_NAME, True),
                      'virtual touchscreen registration')

    def viewport(self):
        dump = self.adb.shell('dumpsys input')
        match = re.search(r'Viewport INTERNAL: displayId=0,.*?logicalFrame=\[(\d+), (\d+), (\d+), (\d+)\], '
                          r'physicalFrame=\[(\d+), (\d+), (\d+), (\d+)\], deviceSize=\[(\d+), (\d+)\]', dump)
        if not match:
            raise FlowError('no internal display viewport in dumpsys input')
        values = list(map(int, match.groups()))
        return {'logical': values[0:4], 'physical': values[4:8], 'device': values[8:10]}

    def ensure_uinput(self):
        if self.uinput is None:
            self.uinput = Uinput(self.adb)

    def wait_for(self, probe, what, timeout=None):
        deadline = time.monotonic() + (self.timeout if timeout is None else timeout)
        while time.monotonic() < deadline:
            value = probe()
            if value:
                return value
            time.sleep(0.3)
        raise FlowError(f'timed out waiting for {what}')

    def open_new_note(self, software):
        if software:
            self.adb.broadcast('forceSoftwareKeyboard')
            self.forced_software = True
        self.adb.shell(f'am start -W -n {self.adb.package}/{NOTES_ALIAS}')
        # The window carries the launched component's name: the alias when the activity starts fresh.
        focus = self.wait_for(lambda: re.search(r'mCurrentFocus=.*(NotesActivity|NotesEditorAlias)',
                                                self.adb.shell('dumpsys window')), 'NotesActivity focus')
        if self.verbose:
            self.log(focus.group(0))
        # An element without children is falsy; wrap it so wait_for sees the found button.
        new, = self.wait_for(lambda: (node,) if (node := self.adb.node('notes_new')) is not None else None,
                             'new-note button')
        existing = set(json.loads(self.adb.broadcast('notes'))['ids'])
        self.adb.shell('input tap %d %d' % center(new.get('bounds')))
        # Never type into a note that existed before the run.
        self.wait_for(lambda: (notes := json.loads(self.adb.broadcast('notes')))['active'] and
                      notes['active'] not in existing and len(notes['ids']) == len(existing) + 1, 'new note')
        if self.note_body()['text']:
            raise FlowError('the new note is not empty')
        self.adb.shell('input tap %d %d' % center(self.adb.node('notes_body').get('bounds')))
        self.wait_for(lambda: self.note_body()['focused'], 'focused note body')
        if software:
            self.wait_for(self.software_keys, 'visible software keyboard')
        self.log('opened a new note in NotesActivity')

    def software_keys(self):
        return {target: point for target, point in self.screen_targets().items()
                if target.startswith('plektra_key_')}

    def screen_targets(self):
        """Software keys, the bar's emoji button, picker cells and the skin-tone popup with screen centers."""
        data = self.adb.broadcast('inspect')
        targets = {}
        for match in re.finditer(r'SoftwareKey: id=([^,]+), label=.*?, screen=(-?\d+),(-?\d+)', data):
            targets.setdefault(match.group(1), (int(match.group(2)), int(match.group(3))))
        for match in re.finditer(r'BarButton: id=(\w+), screen=(-?\d+),(-?\d+)', data):
            targets.setdefault(f'bar:{match.group(1)}', (int(match.group(2)), int(match.group(3))))
        for match in re.finditer(r'EmojiTarget: kind=(cell|variant), text=(.*?), screen=(-?\d+),(-?\d+)', data):
            prefix = 'emoji' if match.group(1) == 'cell' else 'variant'
            targets.setdefault(f'{prefix}:{match.group(2)}', (int(match.group(3)), int(match.group(4))))
        return targets

    def note_body(self):
        """Text and focus of the real editor, read in the app; the UI dump shows the hint when empty."""
        try:
            return json.loads(self.adb.broadcast('noteBody'))
        except FlowError as error:
            raise FlowError(f'the note body is not visible: {error}') from None

    def body_text(self):
        return self.note_body()['text']

    def execute(self, ops):
        screen_keys = {}
        for op in ops:
            kind = op['op']
            if self.verbose and kind != 'wait':
                self.log('  ' + json.dumps(op, ensure_ascii=False))
            if kind == 'wait':
                time.sleep(op['ms'] / 1000)
            elif kind == 'key':
                self.uinput.key(op['scan'], op['down'])
            elif kind == 'touch':
                if op['down']:
                    screen_keys = self.screen_targets()
                    if op['target'] not in screen_keys:
                        raise FlowError(f'line {op["line"]}: {op["target"]} is not on screen '
                                        f'(visible: {", ".join(sorted(screen_keys)) or "none"})')
                self.uinput.touch(op['target'], screen_keys.get(op['target']), op['down'])
            elif kind == 'expect':
                deadline, text = time.monotonic() + 3, None
                while time.monotonic() < deadline:
                    text = self.body_text()
                    if text == op['text']:
                        break
                    time.sleep(0.3)
                if text != op['text']:
                    raise FlowError(f'line {op["line"]}: expected {op["text"]!r}, editor has {text!r}')
                self.log(f'line {op["line"]}: editor text matches {text!r}')

    def check_requirements(self, ops, keymap):
        for op in ops:
            if op['op'] == 'require-layout':
                mismatches = layout_mismatches(keymap, op['value'])
                if mismatches:
                    raise FlowError(f'line {op["line"]}: the active layout is not {op["value"]!r} '
                                    f'({", ".join(mismatches[:4])}); switch it in Plektra, '
                                    'the runner does not change settings')
            elif op['op'] == 'require-profile' and self.virtual_keyboard['profile'] != op['value']:
                raise FlowError(f'line {op["line"]}: Plektra resolves profile '
                                f'{self.virtual_keyboard["profile"]!r}, flow needs {op["value"]!r}')

    def release_all(self):
        """Lift every finger and key left down by a failed flow before the devices disappear."""
        if self.uinput is None or self.uinput.process.poll() is not None:
            return
        for target in list(self.uinput.slots):
            self.uinput.touch(target, None, False)
        for scan in list(self.uinput.keys_down):
            self.uinput.key(scan, False)

    def close(self):
        """Run every cleanup step even if an earlier one fails; return the failures."""
        steps = [('release held input', self.release_all)]
        if self.uinput:
            steps.append(('remove virtual devices', self.uinput.close))
        if self.forced_software:
            steps.append(('clear temporary software-keyboard mode', self.clear_software_keyboard))
        errors = []
        for name, action in steps:
            try:
                action()
            except Exception as error:  # noqa: BLE001 - every cleanup step must still run
                errors.append(f'{name}: {error}')
                print(f'cleanup failed: {name}: {error}', file=sys.stderr)
        return errors

    def clear_software_keyboard(self):
        self.adb.broadcast('clearSoftwareOverride')
        self.forced_software = False


def finish(session, error):
    """Clean up; a cleanup failure fails the command only when nothing failed before."""
    failures = session.close()
    if failures and error is None:
        raise FlowError('cleanup failed: ' + '; '.join(failures))


def needs_hardware(steps):
    """Screen-only flows also run on devices without a built-in keyboard."""
    return any(step.surface == 'hw' or step.action == 'require-profile' for step in steps)


def command_plan(session, path):
    """Print the intermediate operations without injecting input.

    The mapping comes from the IME's currently loaded layout; focus a text field first."""
    steps = parse_flow(Path(path).read_text(encoding='utf-8'))
    if needs_hardware(steps):
        session.load_reference()
    keymap = json.loads(session.adb.broadcast('keymap'))
    for op in plan(steps, keymap, session.key_layout):
        print(json.dumps(op, ensure_ascii=False))


def command_run(session, path):
    steps = parse_flow(Path(path).read_text(encoding='utf-8'))
    surfaces = {step.surface for step in steps if step.surface}
    session.preflight()
    error = None
    try:
        if needs_hardware(steps):
            session.start_hardware()
        if 'screen' in surfaces:
            session.start_touch()
        session.open_new_note('screen' in surfaces)
        # The IME loads its layout for the focused editor; plan and check before the first key.
        keymap = json.loads(session.adb.broadcast('keymap'))
        ops = plan(steps, keymap, session.key_layout)
        session.check_requirements(ops, keymap)
        session.execute(ops)
        session.log(f'PASS {path}')
    except BaseException as caught:
        error = caught
        raise
    finally:
        finish(session, error)


def compare_keyboards(real, clone, real_hub, clone_hub, real_bits, clone_bits):
    """Return report lines and the number of differences without an exact known cause."""
    rows = [(f'plektra.{k}', real[k], clone.get(k)) for k in real if k not in ('id', 'keys')]
    rows += [(f'eventhub.{k}', real_hub[k], clone_hub[k]) for k in real_hub if k != 'Path']
    rows.append(('kernel.keyBits', real_bits, clone_bits))
    key_diff = [code for code in sorted(set(real['keys']) | set(clone['keys']))
                if real['keys'].get(code) != clone['keys'].get(code)]
    rows.append(('android.keyCharacterMap', f'{len(real["keys"])} keys',
                  f'{len(real["keys"])} keys' if not key_diff else f'differs: {", ".join(key_diff)}'))
    lines, unexplained = [], 0
    for name, left, right in rows:
        note = None if left == right else explain_clone_difference(name, left, right)
        unexplained += left != right and note is None
        mark = '=' if left == right else '~' if note else '≠'
        lines.append(f'{mark} {name}: built-in={left!r} clone={right!r}' + (f'  [{note}]' if note else ''))
    lines.append(f'{unexplained} unexplained differences')
    return lines, unexplained


def explain_clone_difference(name, real, clone):
    """Accept only the exact differences uinput cannot avoid; anything else stays a failure."""
    if name == 'eventhub.Classes':
        real_classes = {c.strip() for c in real.split('|')}
        if 'BATTERY' not in real_classes and {c.strip() for c in clone.split('|')} == real_classes | {'BATTERY'}:
            return 'additional BATTERY class of the uinput node; battery state is not part of key mapping'
    if name == 'eventhub.Identifier':
        pairs = lambda text: dict(part.strip().split('=', 1) for part in text.split(',') if '=' in part)
        real_ids, clone_ids = pairs(real), pairs(clone)
        if real_ids.keys() == clone_ids.keys() and \
                {k for k in real_ids if real_ids[k] != clone_ids[k]} == {'bus'} and \
                real_ids['bus'] == '0x0000' and clone_ids['bus'] == '0x0019':
            return 'uinput accepts only named buses; HOST (0x19) keeps the device internal'
    return None


def command_devices(session, output=None):
    """Compare the built-in keyboard with its uinput clone as Android and Plektra see them."""
    props = session.identity()
    error = None
    try:
        session.start_hardware()
        real, clone = session.reference, session.virtual_keyboard
        dump = session.adb.shell('dumpsys input')
        real_hub = event_hub_fields(input_dump_block(dump, real['name'], False) or '')
        clone_hub = event_hub_fields(input_dump_block(dump, real['name'], True) or '')
        lines, unexplained = compare_keyboards(real, clone, real_hub, clone_hub,
                                               key_bits(session.adb, real_hub['Path']),
                                               key_bits(session.adb, clone_hub['Path']))
        print('\n'.join(lines))
        if output:
            header = [f'# input_flow.py devices, {time.strftime("%Y-%m-%d %H:%M %Z")}',
                      '# ' + ', '.join(f'{k}={v}' for k, v in props.items()),
                      f'# package={session.adb.package}']
            keys = [f'{code}: {json.dumps(real["keys"][code], ensure_ascii=False, sort_keys=True)}'
                    for code in sorted(real['keys'])]
            Path(output).write_text('\n'.join(header + lines + ['', '# built-in key character map'] + keys) + '\n',
                                    encoding='utf-8')
        if unexplained:
            raise FlowError('the virtual keyboard differs from the built-in keyboard')
    except BaseException as caught:
        error = caught
        raise
    finally:
        finish(session, error)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--device', required=True, help='adb serial')
    parser.add_argument('--package', default='rocks.pkb.plektra.nightly')
    parser.add_argument('--verbose', action='store_true', help='print each executed operation')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('devices', help='compare the built-in keyboard with the virtual clone').add_argument(
        '--output', help='also write the comparison and the key character map to this file')
    for name in ('plan', 'run'):
        commands.add_parser(name).add_argument('flow')
    args = parser.parse_args(argv)
    session = Session(Adb(args.device, args.package), args.verbose)
    try:
        if args.command == 'devices':
            command_devices(session, args.output)
        elif args.command == 'plan':
            command_plan(session, args.flow)
        else:
            command_run(session, args.flow)
    except FlowError as error:
        print(f'FAIL: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
