#!/usr/bin/env python3
"""Back up, restore and install debuggable Plektra packages.

Examples (device/package default to .env):
  python3 scripts/debug_device.py backup
  python3 scripts/debug_device.py install path/to/app-nightly-debug.apk
  python3 scripts/debug_device.py install path/to/app-nightly-debug.apk --reinstall
  python3 scripts/debug_device.py restore .local-work/device-backups/BACKUP

Only --reinstall uninstalls the existing app. Restore/reinstall require .env
replacement keys for all backed-up speech providers: Android Keystore keys cannot
be restored from app files. Speech import verifies encrypted readback, without
contacting cloud APIs. Backups contain APKs, credential/device app data and runtime
permission/IME metadata; regenerable cache/code_cache directories are excluded.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = re.compile(r'rocks\.pkb\.plektra(?:\.unsovereign)?(?:\.nightly)?')
PROVIDERS = {'OPENAI': 'OPENAI_API_KEY', 'OPENROUTER': 'OPENROUTER_API_KEY'}


def read_env(path):
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:]
        name, sep, value = line.partition('=')
        if not sep or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name.strip()):
            raise ValueError('Expected KEY=value in the env file')
        value = value.strip()
        if value.startswith(('"', "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError('Unterminated env value')
            value = value[1:-1]
        values[name.strip()] = value
    return values


def speech_payload(values):
    payload = {}
    for provider, variable in PROVIDERS.items():
        key = values.get(variable, '')
        if key:
            if any(c.isspace() for c in key):
                raise ValueError('Speech keys must not contain whitespace')
            payload[provider.lower() + '_key'] = key
        model = values.get(provider + '_STT_MODEL')
        if model:
            payload[provider.lower() + '_model'] = model
    if values.get('SPEECH_PROVIDER'):
        if values['SPEECH_PROVIDER'] not in ('OPENAI', 'OPENROUTER', 'SYSTEM', 'FUTO'):
            raise ValueError('Invalid speech provider')
        payload['provider'] = values['SPEECH_PROVIDER']
    if values.get('SPEECH_SILENCE_MS'):
        silence = int(values['SPEECH_SILENCE_MS'])
        if silence not in (0, 1500, 3000, 5000):
            raise ValueError('Invalid speech pause')
        payload['silence_ms'] = silence
    return payload


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def stored_providers(archive):
    with tarfile.open(archive) as tar:
        members = [m for m in tar if m.name == './shared_prefs/speech_credentials.xml']
        if not members:
            return set()
        return {item.get('name') for item in ET.fromstring(tar.extractfile(members[0]).read())}


def require_replacement_keys(archive, values):
    missing = stored_providers(archive) - {
        provider for provider, variable in PROVIDERS.items() if values.get(variable)
    }
    if missing:
        raise ValueError('Reinstallation needs replacement keys in .env for: ' + ', '.join(sorted(missing)))


class Device:
    def __init__(self, serial, package):
        if not serial or not PACKAGES.fullmatch(package):
            raise ValueError('Specify a device and a canonical Plektra package')
        self.serial, self.package = serial, package
        self.adb = [os.environ.get('ADB_PATH') or 'adb', '-s', serial]

    def call(self, *args, data=None, timeout=60):
        result = subprocess.run(self.adb + list(args), input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB operation failed: ' + args[0])
        return result.stdout

    def shell(self, *args, data=None):
        return self.call('shell', shlex.join(args), data=data).decode().strip()

    def debug_check(self):
        self.shell('run-as', self.package, 'true')

    def current_ime(self):
        return self.shell('settings', 'get', 'secure', 'default_input_method')

    @contextmanager
    def stopped(self):
        self.debug_check()
        original = self.current_ime()
        if original.startswith(self.package + '/'):
            enabled = self.shell('ime', 'list', '-s').splitlines()
            fallback = next((ime for ime in enabled if ime and not ime.startswith(self.package + '/')
                             and 'voice' not in ime.lower()), None)
            if not fallback:
                raise ValueError('Enable another keyboard before backing up the active IME')
            self.shell('ime', 'set', fallback)
        try:
            if self.current_ime().startswith(self.package + '/'):
                raise RuntimeError('Refusing to stop the selected IME')
            self.shell('am', 'force-stop', self.package)
            yield original
        finally:
            if original.startswith(self.package + '/'):
                self.shell('ime', 'enable', original)
                self.shell('ime', 'set', original)

    def speech(self, values):
        self.debug_check()
        payload = speech_payload(values)
        if not any(name.endswith('_key') for name in payload):
            raise ValueError('No speech key was supplied')
        pending = 'cache/speech-setup.json'
        try:
            self.shell('run-as', self.package, 'sh', '-c',
                       'umask 077; mkdir -p cache; cat > ' + pending,
                       data=json.dumps(payload).encode())
            result = self.shell('am', 'broadcast', '-n', self.package + '/rocks.pkb.plektra.SpeechSetupReceiver')
            if 'result=0' not in result or 'encrypted keys verified' not in result:
                raise RuntimeError('Device did not confirm encrypted credential import; update the debug APK')
        finally:
            self.shell('run-as', self.package, 'rm', '-f', pending)
        print('Speech keys imported and encrypted readback verified.')

    def backup(self, destination):
        destination.mkdir(mode=0o700, parents=True, exist_ok=False)
        with self.stopped() as original:
            info = self.shell('dumpsys', 'package', self.package)
            permissions = re.findall(r'^\s+(android\.permission\.[\w.]+): granted=true, flags=', info, re.M)
            paths = self.shell('pm', 'path', self.package).splitlines()
            for index, path in enumerate(paths):
                target = destination / ('base.apk' if index == 0 else f'split-{index}.apk')
                self.call('pull', path.removeprefix('package:'), str(target))
                target.chmod(0o600)
            for name, remote in [('credential-data.tar', '.'), ('device-data.tar', '/data/user_de/0/' + self.package)]:
                with (destination / name).open('wb') as stream:
                    result = subprocess.run(self.adb + ['exec-out', 'run-as', self.package, 'tar', '--exclude=./cache', '--exclude=./code_cache', '-C', remote, '-cf', '-', '.'],
                                            stdout=stream, stderr=subprocess.PIPE, timeout=180)
                if result.returncode:
                    raise RuntimeError('Could not back up app data')
                (destination / name).chmod(0o600)
                with tarfile.open(destination / name) as tar:
                    tar.getmembers()
            metadata = {'package': self.package, 'serial': self.serial, 'ime': original,
                        'permissions': sorted(set(permissions)),
                        'sha256': {p.name: digest(p) for p in destination.iterdir()}}
            (destination / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
            (destination / 'metadata.json').chmod(0o600)
        print('Backup verified: ' + str(destination))
        return destination

    def validate_backup(self, backup):
        metadata = json.loads((backup / 'metadata.json').read_text())
        if metadata['package'] != self.package or metadata['serial'] != self.serial:
            raise ValueError('Backup belongs to another package or device')
        if not metadata.get('sha256') or not {'base.apk', 'credential-data.tar', 'device-data.tar'} <= metadata['sha256'].keys():
            raise ValueError('Backup is incomplete')
        for name, expected in metadata['sha256'].items():
            if Path(name).name != name or digest(backup / name) != expected:
                raise ValueError('Backup checksum mismatch')
        for name in ('credential-data.tar', 'device-data.tar'):
            with tarfile.open(backup / name) as tar:
                for member in tar:
                    path = Path(member.name)
                    if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk():
                        raise ValueError('Unsafe archive member')
        return metadata

    def restore(self, backup, values):
        metadata = self.validate_backup(backup)
        speech_payload(values)
        require_replacement_keys(backup / 'credential-data.tar', values)
        with self.stopped():
            for name, remote in [('credential-data.tar', '.'), ('device-data.tar', '/data/user_de/0/' + self.package)]:
                with (backup / name).open('rb') as stream:
                    result = subprocess.run(self.adb + ['exec-in', 'run-as', self.package, 'tar', '--exclude=./cache', '--exclude=./code_cache', '-C', remote, '-xof', '-'],
                                            stdin=stream, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
                if result.returncode:
                    raise RuntimeError('App data restoration failed')
                with tarfile.open(backup / name) as tar:
                    for member in tar:
                        if member.isfile() and Path(member.name).parts[0] not in ('cache', 'code_cache'):
                            expected = hashlib.sha256(tar.extractfile(member).read()).hexdigest()
                            path = member.name if remote == '.' else remote + '/' + member.name
                            actual = self.shell('run-as', self.package, 'sha256sum', path).split()[0]
                            if expected != actual:
                                raise RuntimeError('Restored file checksum mismatch: ' + name + ' ' + member.name)
            for permission in metadata['permissions']:
                self.shell('pm', 'grant', self.package, permission)
            if any(name.endswith('_key') for name in speech_payload(values)):
                self.speech(values)
        if metadata['ime'].startswith(self.package + '/'):
            self.shell('ime', 'enable', metadata['ime'])
            self.shell('ime', 'set', metadata['ime'])
        print('App data hashes, runtime permissions and keyboard selection restored.')

    def install(self, apk, backup, values, reinstall=False):
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
        analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
        def manifest(field):
            return subprocess.check_output([str(analyzer), 'manifest', field, str(apk)], stderr=subprocess.DEVNULL, text=True).strip()
        if manifest('application-id') != self.package or manifest('debuggable') != 'true':
            raise ValueError('Expected a debug APK for the selected package')
        speech_payload(values)  # Validate env fields before modifying device state.
        self.backup(backup)
        self.validate_backup(backup)
        if reinstall:
            require_replacement_keys(backup / 'credential-data.tar', values)
            with self.stopped():
                self.call('uninstall', self.package)
                self.call('install', str(apk), timeout=180)
                self.restore(backup, values)
        else:
            # Replace the APK while another IME is selected. Replacing the selected
            # service can leave Android holding a dead binding after the update.
            with self.stopped():
                self.call('install', '-r', str(apk), timeout=180)
                if any(name.endswith('_key') for name in speech_payload(values)):
                    self.speech(values)
            metadata = json.loads((backup / 'metadata.json').read_text())
            if metadata['ime'].startswith(self.package + '/'):
                self.shell('ime', 'enable', metadata['ime'])
                self.shell('ime', 'set', metadata['ime'])
        print('Debug APK installed. Backup retained at ' + str(backup))


def arguments(description):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--env', type=Path, default=ROOT / '.env')
    parser.add_argument('--device')
    parser.add_argument('--package')
    return parser


def connection(args):
    values = read_env(args.env) if args.env.exists() else {}
    return Device(args.device or values.get('ANDROID_SERIAL'),
                  args.package or values.get('ANDROID_PACKAGE', 'rocks.pkb.plektra.nightly')), values


def main():
    parser = arguments(__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    backup_parser = sub.add_parser('backup')
    backup_parser.add_argument('--output', type=Path)
    restore_parser = sub.add_parser('restore')
    restore_parser.add_argument('backup', type=Path)
    installer = sub.add_parser('install')
    installer.add_argument('apk', type=Path)
    installer.add_argument('--reinstall', action='store_true', help='Explicitly uninstall, install and restore; requires replacement speech keys')
    installer.add_argument('--backup', type=Path)
    args = parser.parse_args()
    device, values = connection(args)
    output = getattr(args, 'output', None) or getattr(args, 'backup', None)
    if args.action in ('backup', 'install') and output is None:
        output = ROOT / '.local-work/device-backups' / (datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%SZ') + '-' + device.package)
    if args.action == 'backup':
        device.backup(output)
    elif args.action == 'restore':
        device.restore(args.backup, values)
    else:
        device.install(args.apk, output, values, args.reinstall)


if __name__ == '__main__':
    try:
        os.umask(0o077)
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError, tarfile.TarError):
        print('Debug device operation failed; device/env/debug APK or backup validation did not pass. Backups are retained.', file=sys.stderr)
        sys.exit(1)
