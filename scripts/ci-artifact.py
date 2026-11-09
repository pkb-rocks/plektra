#!/usr/bin/env python3
"""Verify package and version from the actual APK or App Bundle."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path)
    parser.add_argument('--package', required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', required=True)
    parser.add_argument('--bundletool', type=Path)
    args = parser.parse_args()
    if args.artifact.suffix == '.apk':
        sdk = Path(os.environ['ANDROID_HOME'])
        tools = sorted((sdk / 'build-tools').glob('*/aapt'))
        if not tools:
            parser.error('Android SDK aapt not found')
        output = subprocess.check_output([str(tools[-1]), 'dump', 'badging', str(args.artifact)], text=True)
        package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output, re.M)
        if not package:
            raise ValueError('APK package metadata missing')
        actual = package.group(1), package.group(3), package.group(2)
        if 'application-debuggable' in output:
            raise ValueError('Distribution APK is debuggable')
    elif args.artifact.suffix == '.aab' and args.bundletool:
        output = subprocess.check_output(['java', '-jar', str(args.bundletool), 'dump', 'manifest',
                                          '--bundle=' + str(args.artifact)], text=True)
        manifest = ET.fromstring(output)
        android = '{http://schemas.android.com/apk/res/android}'
        actual = manifest.get('package'), manifest.get(android + 'versionName'), manifest.get(android + 'versionCode')
        if manifest.find('application').get(android + 'debuggable', 'false') != 'false':
            raise ValueError('Distribution bundle is debuggable')
        subprocess.run(['java', '-jar', str(args.bundletool), 'validate', '--bundle=' + str(args.artifact)], check=True)
    else:
        parser.error('Expected APK, or AAB with --bundletool')
    expected = args.package, args.version_name, args.version_code
    if actual != expected:
        raise ValueError(f'{args.artifact}: expected {expected}, got {actual}')
    print(f'{args.artifact}: package={actual[0]}, version={actual[1]}, code={actual[2]} verified')


if __name__ == '__main__':
    main()
