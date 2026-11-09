#!/usr/bin/env python3
"""Conservatively select Android and minified release checks from changed paths."""
import argparse
from pathlib import PurePosixPath
import subprocess


def selection(paths):
    paths = list(paths)
    def docs(path):
        p = PurePosixPath(path)
        return p.suffix.lower() in ('.md', '.rst') and (
            len(p.parts) == 1 or p.parts[0] == 'docs')
    android = not paths or not all(docs(path) for path in paths)
    def release(path):
        p = PurePosixPath(path)
        return (path.startswith(('app/src/release/', 'buildSrc/', 'build-logic/', 'gradle/'))
                or p.name in ('build.gradle.kts', 'build.gradle', 'settings.gradle.kts',
                              'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat')
                or 'proguard' in p.name.lower() or p.suffix == '.pro'
                or path.startswith(('.github/workflows/', 'scripts/ci-')))
    return android, android and (not paths or any(release(path) for path in paths))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base')
    args = parser.parse_args()
    paths = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', args.base, 'HEAD'], text=True).split('\0')
    android, release = selection(p for p in paths if p)
    print(f'android={str(android).lower()}\nrelease={str(release).lower()}')


if __name__ == '__main__':
    main()
