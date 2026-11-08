#!/usr/bin/env python3
"""Fail CI on lint errors that exceed the reviewed main baseline."""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / 'ci/lint-errors.json'


def location_key(value):
    # Lint emits absolute paths on developer machines and CI runners.
    value = value.replace("\\", "/")
    if "/app/" in value:
        return "app/" + value.split("/app/", 1)[1]
    return "app/" + value if value.startswith("src/") else value


def findings(report):
    result = Counter()
    for issue in ET.parse(report).getroot().findall('issue'):
        if issue.get('severity') not in ('Error', 'Fatal'):
            continue
        locations = tuple(sorted(location_key(loc.get('file', '')) for loc in issue.findall('location')))
        result[(issue.get('id'), issue.get('message'), locations, issue.get('errorLine1', '').strip())] += 1
    return result


def load_baseline(path=BASELINE):
    return Counter({(row['id'], row['message'], tuple(row['files']), row['source']): row['count']
                    for row in json.loads(path.read_text())['issues']})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    current = findings(args.report)
    new = current - load_baseline()
    for (issue, message, locations, source), count in sorted(new.items()):
        print(f'{issue} ({count}): {message}\n  {", ".join(locations)}\n  {source}', file=sys.stderr)
    print(f'{args.report.name}: {sum(current.values())} errors, {sum(new.values())} new errors.')
    return bool(new)


if __name__ == '__main__':
    sys.exit(main())
