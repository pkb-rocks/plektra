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


def write_tar(path, entries):
    with tarfile.open(path, 'w') as archive:
        for name, data in entries.items():
            member = tarfile.TarInfo(name)
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))


class FakeApp:
    """Device side of a restore: app files by absolute path, plus a process the test can start."""

    def __init__(self, test, device, on_restored=None):
        self.test, self.device, self.on_restored = test, device, on_restored
        self.files, self.pid, self.calls, self.selected = {}, '', [], 'other.keyboard/Service'
        self.de = '/data/user_de/0/' + device.package

    def absolute(self, path):
        return path if path.startswith('/') else '/data/data/' + self.device.package + '/' + path.removeprefix('./')

    def run(self, args, stdin=None, **kwargs):
        remote = args[args.index('-C') + 1]
        base = self.de if remote.startswith('/') else '/data/data/' + self.device.package
        with tarfile.open(fileobj=stdin) as archive:
            for member in archive:
                if member.isfile():
                    self.files[base + '/' + member.name.removeprefix('./')] = archive.extractfile(member).read()
        if remote.startswith('/') and self.on_restored:
            self.on_restored(self)
        return debug.subprocess.CompletedProcess(args, 0, b'', b'')

    def shell(self, *args, **kwargs):
        self.calls.append(args)
        if args[:2] == ('ime', 'list'):
            return 'other.keyboard/Service'
        if args[:2] == ('ime', 'set'):
            self.selected = args[2]
        if args[:2] == ('am', 'force-stop'):
            self.pid = ''
        if args[0] == 'pidof':
            if not self.pid:
                raise RuntimeError('ADB operation failed: shell')
            return self.pid
        if args[:3] == ('run-as', self.device.package, 'sh') and 'sha256sum' in args[4]:
            data = self.files.get(self.absolute(args[-1]))
            return 'missing' if data is None else debug.hashlib.sha256(data).hexdigest() + '  ' + args[-1]
        if args[:3] == ('run-as', self.device.package, 'sh'):
            return self.files.get(self.absolute(args[-1]), b'').decode()
        return ''

    def restore(self, backup):
        with patch.object(self.device, 'shell', side_effect=self.shell), \
             patch.object(self.device, 'current_ime', side_effect=lambda: self.selected), \
             patch.object(debug.subprocess, 'run', side_effect=self.run), \
             patch.object(debug.time, 'sleep'):
            self.device.restore(backup, {})


def write_backup(device, folder, credential, device_data):
    backup = Path(folder)
    (backup / 'base.apk').write_bytes(b'apk')
    write_tar(backup / debug.CREDENTIAL_DATA, credential)
    write_tar(backup / debug.DEVICE_DATA, device_data)
    metadata = {'package': device.package, 'serial': device.serial, 'ime': device.package + '/.KeyboardService',
                'permissions': ['android.permission.RECORD_AUDIO'],
                'sha256': {p.name: debug.digest(p) for p in backup.iterdir()}}
    (backup / 'metadata.json').write_text(json.dumps(metadata))
    return backup


OLD_GENERATION = './files/direct_boot_files/generation-00000000-0000-0000-0000-000000000000'
NEW_GENERATION = 'generation-11111111-1111-1111-1111-111111111111'


def rebuild_snapshot(app):
    """What DirectBootKeyboardConfig.refresh does after a start: publish a new generation, delete the rest."""
    root = app.de + '/files/direct_boot_files/'
    for path in [path for path in app.files if path.startswith(root)]:
        del app.files[path]
    app.files[root + NEW_GENERATION + '/settings.json'] = b'{}'
    app.files[root + 'current'] = NEW_GENERATION.encode()


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

    def test_restore_survives_a_direct_boot_generation_change_and_restores_the_rest(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        def start_app(app):
            app.pid = '4242'
            rebuild_snapshot(app)
        with tempfile.TemporaryDirectory() as folder:
            backup = write_backup(device, folder, {'./shared_prefs/plektra_prefs.xml': b'<map/>',
                                                   './cache/tmp': b'regenerable'},
                                  {'./files/direct_boot_files/current': OLD_GENERATION.rsplit('/', 1)[1].encode(),
                                   OLD_GENERATION + '/settings.json': b'{"old": 1}',
                                   OLD_GENERATION + '/files/ctrl_key_mappings.json': b'{}',
                                   './shared_prefs/direct_boot_keyboard_config.xml': b'<map/>',
                                   './shared_prefs/de_user_choice.xml': b'<map/>'})
            app = FakeApp(self, device, on_restored=start_app)
            app.restore(backup)
        self.assertEqual(b'<map/>', app.files['/data/data/rocks.pkb.plektra.nightly/shared_prefs/plektra_prefs.xml'])
        self.assertIn(app.de + '/shared_prefs/de_user_choice.xml', app.files)
        self.assertFalse(any('generation-0000' in path or 'direct_boot_keyboard_config' in path or '/cache/' in path
                             for path in app.files))
        self.assertIn(('am', 'force-stop', device.package), app.calls[app.calls.index(('pidof', device.package)):])
        self.assertIn(('pm', 'grant', device.package, 'android.permission.RECORD_AUDIO'), app.calls)
        self.assertEqual(device.package + '/.KeyboardService', app.selected)

    def test_missing_user_file_is_named_and_permissions_and_ime_are_still_restored(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        def lose_settings(app):
            del app.files['/data/data/rocks.pkb.plektra.nightly/shared_prefs/plektra_prefs.xml']
        with tempfile.TemporaryDirectory() as folder:
            backup = write_backup(device, folder, {'./shared_prefs/plektra_prefs.xml': b'<map/>'}, {})
            app = FakeApp(self, device, on_restored=lose_settings)
            with self.assertRaisesRegex(RuntimeError, 'missing: credential-data.tar ./shared_prefs/plektra_prefs.xml'):
                app.restore(backup)
        self.assertIn(('pm', 'grant', device.package, 'android.permission.RECORD_AUDIO'), app.calls)
        self.assertEqual(device.package + '/.KeyboardService', app.selected)

    def test_changed_user_file_is_a_checksum_failure(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        def overwrite(app):
            app.files[app.de + '/files/user.json'] = b'changed'
        with tempfile.TemporaryDirectory() as folder:
            backup = write_backup(device, folder, {}, {'./files/user.json': b'original'})
            with self.assertRaisesRegex(RuntimeError, 'checksum mismatch: device-data.tar'):
                FakeApp(self, device, on_restored=overwrite).restore(backup)

    def test_an_app_that_keeps_starting_fails_the_restore(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        app = FakeApp(self, device)
        original = app.shell
        def shell(*args, **kwargs):
            if args[0] == 'pidof':
                app.pid = '7'
            return original(*args, **kwargs)
        app.shell = shell
        with tempfile.TemporaryDirectory() as folder:
            backup = write_backup(device, folder, {'./shared_prefs/plektra_prefs.xml': b'<map/>'}, {})
            with self.assertRaisesRegex(RuntimeError, 'kept starting'):
                app.restore(backup)
        self.assertEqual(device.package + '/.KeyboardService', app.selected)

    def test_every_recoverable_data_failure_still_restores_permissions_and_backup_ime(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        failures = [RuntimeError('data'), debug.subprocess.TimeoutExpired(['adb'], 180), OSError('disk'),
                    debug.tarfile.TarError('archive')]
        for failure in failures:
            with self.subTest(type(failure).__name__), tempfile.TemporaryDirectory() as folder:
                backup = write_backup(device, folder, {'./shared_prefs/plektra_prefs.xml': b'<map/>'}, {})
                app = FakeApp(self, device)
                with patch.object(device, 'restore_data', side_effect=failure):
                    with self.assertRaises(type(failure)) as raised:
                        app.restore(backup)
                self.assertIs(failure, raised.exception)
                self.assertIn(('pm', 'grant', device.package, 'android.permission.RECORD_AUDIO'), app.calls)
                self.assertEqual(device.package + '/.KeyboardService', app.selected)

    def test_failed_grant_neither_blocks_the_ime_nor_hides_the_data_failure(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        app = FakeApp(self, device)
        original = app.shell
        def shell(*args, **kwargs):
            if args[:2] == ('pm', 'grant'):
                raise RuntimeError('ADB operation failed: shell')
            return original(*args, **kwargs)
        app.shell = shell
        data_failure = OSError('disk')
        with tempfile.TemporaryDirectory() as folder:
            backup = write_backup(device, folder, {'./shared_prefs/plektra_prefs.xml': b'<map/>'}, {})
            stderr = io.StringIO()
            with patch.object(device, 'restore_data', side_effect=data_failure), patch.object(debug.sys, 'stderr', stderr):
                with self.assertRaises(OSError) as raised:
                    app.restore(backup)
        self.assertIs(data_failure, raised.exception)
        self.assertEqual(device.package + '/.KeyboardService', app.selected)
        self.assertIn('Not restored: runtime permission android.permission.RECORD_AUDIO', stderr.getvalue())

    def test_failed_finishing_step_after_good_data_is_reported_as_failure(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        app = FakeApp(self, device)
        original = app.shell
        def shell(*args, **kwargs):
            if args[:2] == ('ime', 'set'):
                raise RuntimeError('ADB operation failed: shell')
            return original(*args, **kwargs)
        app.shell = shell
        with tempfile.TemporaryDirectory() as folder:
            backup = write_backup(device, folder, {'./shared_prefs/plektra_prefs.xml': b'<map/>'}, {})
            with patch.object(debug.sys, 'stderr', io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, 'finishing step'):
                    app.restore(backup)
        self.assertIn(('pm', 'grant', device.package, 'android.permission.RECORD_AUDIO'), app.calls)

    def test_backup_never_archives_regenerable_data(self):
        self.assertIn('--exclude=./files/direct_boot_files', debug.tar_excludes(debug.DEVICE_DATA))
        self.assertTrue(debug.regenerable(debug.DEVICE_DATA, './files/direct_boot_files/current'))
        self.assertFalse(debug.regenerable(debug.DEVICE_DATA, './files/direct_boot_files_user.json'))
        self.assertFalse(debug.regenerable(debug.CREDENTIAL_DATA, './files/direct_boot_files/current'))

    def test_invalid_apk_never_backs_up_or_uninstalls(self):
        device = debug.Device('synthetic-device', 'rocks.pkb.plektra.nightly')
        with patch.object(debug.subprocess, 'check_output', return_value='wrong.package'), patch.object(device, 'backup') as backup:
            with self.assertRaises(ValueError):
                device.install(Path('test.apk'), Path('backup'), {}, reinstall=True)
            backup.assert_not_called()


if __name__ == '__main__':
    unittest.main()
