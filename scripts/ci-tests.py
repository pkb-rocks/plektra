#!/usr/bin/env python3
"""Run the test catalog and retain one report directory per selection."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / 'ci/test-topics.json'
SOURCES = {'unit': ('test', 'testDebug'), 'instrumented': ('androidTest',)}
VARIANTS = ('StableDebug', 'StableRelease', 'NightlyDebug', 'NightlyRelease',
            'PlayStableDebug', 'PlayNightlyDebug',
            'StableNoAccessibilityDebug', 'NightlyNoAccessibilityDebug')


def discover(root=ROOT):
    found = {kind: {} for kind in SOURCES}
    for kind, source_sets in SOURCES.items():
        for source_set in source_sets:
            for path in (root / 'app/src' / source_set).rglob('*'):
                if path.suffix not in ('.kt', '.java'):
                    continue
                text = path.read_text()
                package = re.search(r'^package\s+([\w.]+)', text, re.M)
                # Include inherited tests too, even when the subclass has no @Test method.
                classes = re.findall(r'^\s*(?:public\s+|internal\s+|open\s+|final\s+)*class\s+(\w*Test)\b', text, re.M)
                if re.search(r'@Test\b', text) and not classes:
                    raise ValueError(f'Unrecognized test class declaration: {path}')
                for name in classes:
                    if not package:
                        raise ValueError(f'Missing package: {path}')
                    full_name = f'{package[1]}.{name}'
                    if full_name in found[kind]:
                        raise ValueError(f'Duplicate test class: {full_name}')
                    found[kind][full_name] = source_set
    return found


def validate(catalog, found):
    for kind in SOURCES:
        assignments = [name for names in catalog[kind].values() for name in names]
        duplicate = sorted(name for name, count in Counter(assignments).items() if count != 1)
        missing = sorted(set(found[kind]) - set(assignments))
        stale = sorted(set(assignments) - set(found[kind]))
        if duplicate or missing or stale:
            raise ValueError(f'{kind}: duplicate={duplicate}, unassigned={missing}, missing classes={stale}')
        if any(not names for names in catalog[kind].values()):
            raise ValueError(f'{kind}: empty topic')


def validate_workflow(catalog, workflow):
    topics = re.findall(r'^\s*run: python3 scripts/ci-tests\.py run --topic ([a-z-]+)\s*$', workflow, re.M)
    if Counter(topics) != Counter(catalog['unit'].keys()):
        raise ValueError(f'CI topic steps differ from catalog: workflow={topics}, catalog={sorted(catalog["unit"])}')


def verify_results(directory, expected):
    suites = [ET.parse(path).getroot() for path in directory.glob('TEST-*.xml')]
    actual = {suite.get('name') for suite in suites}
    duplicate = sorted(name for name, count in Counter(s.get('name') for s in suites).items() if count != 1)
    missing = set(expected) - actual
    unexpected = actual - set(expected)
    empty = [suite.get('name') for suite in suites if int(suite.get('tests', 0)) <= int(suite.get('skipped', 0))]
    failures = sum(int(s.get('failures', 0)) + int(s.get('errors', 0)) for s in suites)
    skipped = sum(int(s.get('skipped', 0)) for s in suites)
    tests = sum(int(s.get('tests', 0)) for s in suites)
    if missing or unexpected or duplicate or empty or failures or not tests:
        raise ValueError(f'Invalid results: missing={sorted(missing)}, unexpected={sorted(unexpected)}, '
                         f'duplicate={duplicate}, empty={empty}, failures={failures}')
    return f'{len(actual)} classes, {tests} tests, {skipped} skipped'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('check', 'topics', 'run'))
    parser.add_argument('--topic')
    parser.add_argument('--variant', choices=VARIANTS, default='StableDebug')
    parser.add_argument('--fdroid', action='store_true')
    parser.add_argument('--gradle-arg', action='append', default=[])
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text())
    found = discover()
    validate(catalog, found)
    if args.command == 'check':
        validate_workflow(catalog, (ROOT / '.github/workflows/ci.yml').read_text())
        print('Every unit and instrumented test class belongs to exactly one topic.')
        return 0
    if args.command == 'topics':
        print(json.dumps(sorted(catalog['unit'])))
        return 0
    if args.topic and args.topic not in catalog['unit']:
        parser.error(f'Unknown topic: {args.topic}')
    names = catalog['unit'][args.topic] if args.topic else list(found['unit'])
    expected = [name for name in names if args.variant.endswith('Debug') or found['unit'][name] != 'testDebug']
    task = f'test{args.variant}UnitTest'
    result_dir = ROOT / 'app/build/test-results' / task
    # A failed compilation must not pass on XML left by an earlier run.
    if result_dir.exists():
        shutil.rmtree(result_dir)
    command = [str(ROOT / 'gradlew'), f':app:{task}', '--console=plain', '--max-workers=2']
    if args.topic:
        for name in expected:
            command.extend(('--tests', name))
    if args.fdroid:
        command.append('-PPLEKTRA_FDROID_BUILD=true')
    command.extend(args.gradle_arg)
    result = subprocess.run(command, cwd=ROOT)
    selection = f'{args.variant}{"-fdroid" if args.fdroid else ""}/{args.topic or "all"}'
    archive = ROOT / 'app/build/ci-reports' / selection
    if archive.exists():
        shutil.rmtree(archive)
    archive.mkdir(parents=True)
    for name, source in [('xml', result_dir), ('html', ROOT / 'app/build/reports/tests' / task)]:
        if source.exists() and result_dir.exists():
            shutil.copytree(source, archive / name)
    try:
        summary = verify_results(result_dir, expected)
    except ValueError as error:
        print(error, file=sys.stderr)
        return result.returncode or 1
    print(f'{selection}: {summary}')
    return result.returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except ValueError as error:
        sys.exit(str(error))
