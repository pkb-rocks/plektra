#!/usr/bin/env python3
"""Check Gradle-generated variant contracts without compiling variant app code."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ANDROID = '{http://schemas.android.com/apk/res/android}'
FLAVORS = ('stable', 'nightly', 'playStable', 'playNightly',
           'stableNoAccessibility', 'nightlyNoAccessibility')


def check_contract(config, manifest, flavor, build_type, fdroid=False):
    nightly = 'nightly' in flavor.lower()
    play = flavor.startswith('play')
    accessibility = 'NoAccessibility' not in flavor
    package = 'rocks.pkb.plektra' + ('.unsovereign' if play else '') + ('.nightly' if nightly else '')
    expected = {
        'APPLICATION_ID': package, 'BUILD_TYPE': build_type, 'FLAVOR': flavor,
        'RELEASE_CHANNEL': 'nightly' if nightly else 'stable',
        'DISTRIBUTION_CHANNEL': 'play' if play else 'direct',
        'IS_FDROID_BUILD': fdroid and not play,
        'ENABLE_GITHUB_UPDATE_CHECKS': not (fdroid or play),
        'ENABLE_ACCESSIBILITY_SERVICE': accessibility,
        'APK_VARIANT': 'full' if accessibility else 'no-accessibility',
        'GITHUB_REPOSITORY': 'pkb-rocks/plektra',
    }
    for key, value in expected.items():
        if config.get(key) != value:
            raise ValueError(f'{flavor}/{build_type}: {key}: expected {value!r}, got {config.get(key)!r}')
    if ('-nightly' in config['VERSION_NAME']) != nightly:
        raise ValueError(f'{flavor}: incorrect version channel')
    if manifest.get('package') != package:
        raise ValueError(f'{flavor}: manifest package differs from BuildConfig')
    for key, field in [('versionName', 'VERSION_NAME'), ('versionCode', 'VERSION_CODE')]:
        if manifest.get(ANDROID + key) != str(config[field]):
            raise ValueError(f'{flavor}: manifest {key} differs from BuildConfig')
    app = manifest.find('application')
    label = 'Plektra Nightly' if nightly else 'Plektra'
    icon = '@mipmap/ic_launcher_nightly' if nightly else '@mipmap/ic_launcher'
    for key, value in [('label', label), ('icon', icon), ('roundIcon', icon + '_round')]:
        if app.get(ANDROID + key) != value:
            raise ValueError(f'{flavor}: incorrect application {key}')
    services = {s.get(ANDROID + 'name'): s for s in app.findall('service')}
    namespace = 'rocks.pkb.plektra.inputmethod.'
    ime = services.get(namespace + 'PhysicalKeyboardInputMethodService')
    if ime is None or ime.get(ANDROID + 'permission') != 'android.permission.BIND_INPUT_METHOD' or ime.get(ANDROID + 'label') != label:
        raise ValueError(f'{flavor}: incorrect IME identity')
    if ((namespace + 'ClicksLauncherButtonAccessibilityService') in services) != accessibility:
        raise ValueError(f'{flavor}: incorrect accessibility service presence')
    aliases = {a.get(ANDROID + 'name').split('.')[-1]: a for a in app.findall('activity-alias')}
    enabled = 'BlueLauncherAlias' if nightly else 'OrangeLauncherAlias'
    for name, alias_icon in [('OrangeLauncherAlias', 'ic_launcher'),
                             ('OpenOrangeLauncherAlias', 'ic_launcher_open'),
                             ('BlueLauncherAlias', 'ic_launcher_nightly'),
                             ('OpenBlueLauncherAlias', 'ic_launcher_nightly_open')]:
        alias = aliases[name]
        for key, value in [('enabled', str(name == enabled).lower()),
                           ('icon', '@mipmap/' + alias_icon),
                           ('roundIcon', '@mipmap/' + alias_icon + '_round'),
                           ('targetActivity', 'rocks.pkb.plektra.MainActivity')]:
            if alias.get(ANDROID + key) != value:
                raise ValueError(f'{flavor}: incorrect launcher alias {name}/{key}')
    for provider in app.findall('provider'):
        authority = provider.get(ANDROID + 'authorities', '')
        if authority.endswith(('.shizuku', '.fileprovider')) and not authority.startswith(package + '.'):
            raise ValueError(f'{flavor}: incorrect provider authority')


def read_config(path):
    # Parse generated Java literal fields, never the Gradle build script.
    fields = re.findall(r'public static final (?:String|boolean|int) (\w+) = ("[^"\n]*"|true|false|\d+);', path.read_text())
    return {key: json.loads(value) for key, value in fields}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fdroid', action='store_true')
    parser.add_argument('--gradle-arg', action='append', default=[])
    args = parser.parse_args()
    variants = [(flavor, kind) for flavor in FLAVORS for kind in ('debug', 'release')]
    tasks = []
    for flavor, kind in variants:
        title = flavor[0].upper() + flavor[1:] + kind.title()
        tasks.extend((f':app:generate{title}BuildConfig', f':app:process{title}MainManifest'))
    command = [str(ROOT / 'gradlew'), *tasks, '--console=plain', '--max-workers=2',
               '-PPLEKTRA_UNSIGNED_RELEASE_BUILD=true', *args.gradle_arg]
    if args.fdroid:
        command.append('-PPLEKTRA_FDROID_BUILD=true')
    subprocess.run(command, cwd=ROOT, check=True)
    for flavor, kind in variants:
        variant = flavor + kind.title()
        config = ROOT / f'app/build/generated/source/buildConfig/{flavor}/{kind}/rocks/pkb/plektra/BuildConfig.java'
        manifest = ROOT / f'app/build/intermediates/merged_manifest/{variant}/process{variant[0].upper() + variant[1:]}MainManifest/AndroidManifest.xml'
        check_contract(read_config(config), ET.parse(manifest).getroot(), flavor, kind, args.fdroid)
        print(f'{variant}: generated BuildConfig and merged manifest verified', flush=True)


if __name__ == '__main__':
    main()
