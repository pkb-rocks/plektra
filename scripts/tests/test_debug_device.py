import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('debug_device', Path(__file__).resolve().parents[1] / 'debug_device.py')
debug = importlib.util.module_from_spec(spec)
spec.loader.exec_module(debug)


class DebugDeviceTest(unittest.TestCase):
    def test_env_is_data_and_preserves_quoted_characters(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / '.env'
            path.write_text('# comment\nexport OPENAI_API_KEY="literal-$(do-not-run)#value"\n')
            self.assertEqual({'OPENAI_API_KEY': 'literal-$(do-not-run)#value'}, debug.read_env(path))

    def test_partial_key_import_does_not_set_provider_or_models(self):
        self.assertEqual({'openai_key': 'synthetic'}, debug.speech_payload({'OPENAI_API_KEY': 'synthetic'}))

    def test_invalid_key_is_rejected_before_adb(self):
        with self.assertRaises(ValueError):
            debug.speech_payload({'OPENAI_API_KEY': 'has whitespace'})

    def test_reinstall_requires_keys_for_every_stored_provider(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'data.tar'
            data = b'<map><string name="OPENAI">encrypted</string><string name="OPENROUTER">encrypted</string></map>'
            with tarfile.open(path, 'w') as archive:
                member = tarfile.TarInfo('./shared_prefs/speech_credentials.xml')
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            with self.assertRaisesRegex(ValueError, 'OPENROUTER'):
                debug.require_replacement_keys(path, {'OPENAI_API_KEY': 'synthetic'})
            debug.require_replacement_keys(path, {'OPENAI_API_KEY': 'synthetic', 'OPENROUTER_API_KEY': 'synthetic'})

    def test_active_ime_is_never_force_stopped_when_fallback_cannot_be_selected(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        active = device.package + '/KeyboardService'
        calls = []
        def shell(*args, **kwargs):
            calls.append(args)
            if args[:2] == ('ime', 'list'):
                return active + '\nother.keyboard/Service'
            return ''
        with patch.object(device, 'shell', side_effect=shell), patch.object(device, 'current_ime', return_value=active):
            with self.assertRaisesRegex(RuntimeError, 'selected IME'):
                with device.stopped():
                    self.fail('Must not enter stopped scope')
        self.assertNotIn(('am', 'force-stop', device.package), calls)

    def test_update_replaces_apk_and_imports_keys_before_reselecting_ime(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        original = device.package + '/KeyboardService'
        fallback = 'other.keyboard/Service'
        selected = [original]
        calls = []

        def shell(*args, **kwargs):
            calls.append(args)
            if args[:2] == ('ime', 'list'):
                return original + '\n' + fallback
            if args[:2] == ('ime', 'set'):
                selected[0] = args[2]
            if args[:2] == ('am', 'force-stop'):
                self.assertEqual(fallback, selected[0])
            return ''

        def install(*args, **kwargs):
            self.assertEqual(('install', '-r', 'test.apk'), args)
            self.assertEqual(fallback, selected[0])

        def speech(values):
            self.assertEqual(fallback, selected[0])

        with tempfile.TemporaryDirectory() as folder:
            backup = Path(folder)
            (backup / 'metadata.json').write_text(json.dumps({'ime': original}))
            with patch.object(debug.subprocess, 'check_output', side_effect=[device.package, 'true']), \
                 patch.object(device, 'backup'), patch.object(device, 'validate_backup'), \
                 patch.object(device, 'current_ime', side_effect=lambda: selected[0]), \
                 patch.object(device, 'shell', side_effect=shell), \
                 patch.object(device, 'call', side_effect=install) as apk_install, \
                 patch.object(device, 'speech', side_effect=speech) as key_import:
                device.install(Path('test.apk'), backup, {'OPENAI_API_KEY': 'synthetic'})
                apk_install.assert_called_once()
                key_import.assert_called_once()
        self.assertEqual(original, selected[0])
        self.assertIn(('am', 'force-stop', device.package), calls)

    def test_reinstall_missing_key_never_uninstalls_the_app(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        with tempfile.TemporaryDirectory() as folder:
            backup = Path(folder)
            data = b'<map><string name="OPENAI">encrypted</string></map>'
            with tarfile.open(backup / 'credential-data.tar', 'w') as archive:
                member = tarfile.TarInfo('./shared_prefs/speech_credentials.xml')
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            with patch.object(debug.subprocess, 'check_output', side_effect=[device.package, 'true']), \
                 patch.object(device, 'backup'), patch.object(device, 'validate_backup'), \
                 patch.object(device, 'call') as adb:
                with self.assertRaisesRegex(ValueError, 'OPENAI'):
                    device.install(Path('test.apk'), backup, {}, reinstall=True)
                adb.assert_not_called()

    def test_speech_payload_is_stdin_and_pending_file_is_removed_on_failure(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        calls = []
        def shell(*args, **kwargs):
            calls.append((args, kwargs))
            return 'result=1' if args[0] == 'am' else ''
        with patch.object(device, 'shell', side_effect=shell):
            with self.assertRaises(RuntimeError):
                device.speech({'OPENAI_API_KEY': 'synthetic-secret'})
        self.assertTrue(any(b'synthetic-secret' in kwargs.get('data', b'') for _, kwargs in calls))
        self.assertFalse(any('synthetic-secret' in ' '.join(args) for args, _ in calls))
        self.assertEqual(('run-as', device.package, 'rm', '-f', 'cache/speech-setup.json'), calls[-1][0])

    def test_changed_backup_is_rejected_before_restore(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        with tempfile.TemporaryDirectory() as folder:
            backup = Path(folder)
            for name in ('base.apk', 'credential-data.tar', 'device-data.tar'):
                (backup / name).write_bytes(b'original')
            metadata = {'package': device.package, 'serial': device.serial,
                        'sha256': {p.name: debug.digest(p) for p in backup.iterdir()}}
            (backup / 'metadata.json').write_text(json.dumps(metadata))
            (backup / 'credential-data.tar').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                device.validate_backup(backup)

    def test_invalid_apk_never_backs_up_or_uninstalls(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        with patch.object(debug.subprocess, 'check_output', return_value='wrong.package'), patch.object(device, 'backup') as backup:
            with self.assertRaises(ValueError):
                device.install(Path('test.apk'), Path('backup'), {}, reinstall=True)
            backup.assert_not_called()


if __name__ == '__main__':
    unittest.main()
