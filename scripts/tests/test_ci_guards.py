import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


catalog = module('ci_tests', SCRIPTS / 'ci-tests.py')
lint = module('check_lint', SCRIPTS / 'check-lint.py')


class CatalogGuardTest(unittest.TestCase):
    def test_missing_duplicate_and_stale_classes_fail(self):
        found = {'unit': {'example.SampleTest': 'test'}, 'instrumented': {}}
        for topics in ({'editing': []},
                       {'editing': ['example.SampleTest'], 'ui': ['example.SampleTest']},
                       {'editing': ['example.SampleTest', 'example.RemovedTest']}):
            with self.subTest(topics=topics), self.assertRaises(ValueError):
                catalog.validate({'unit': topics, 'instrumented': {}}, found)

    def test_inherited_test_without_annotations_is_discovered(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'app/src/test/java/InheritedTest.kt'
            path.parent.mkdir(parents=True)
            path.write_text('package example\nclass InheritedTest : BaseChecks()\n')
            self.assertEqual({'example.InheritedTest': 'test'}, catalog.discover(Path(tmp))['unit'])

    def test_empty_partial_unexpected_and_failed_reports_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            for xml in (None,
                        '<testsuite name="example.OtherTest" tests="1"/>',
                        '<testsuite name="example.SampleTest" tests="0"/>',
                        '<testsuite name="example.SampleTest" tests="1" skipped="1"/>',
                        '<testsuite name="example.SampleTest" tests="1" failures="1"/>'):
                report = directory / 'TEST-example.SampleTest.xml'
                if xml:
                    report.write_text(xml)
                with self.subTest(xml=xml), self.assertRaises(ValueError):
                    catalog.verify_results(directory, ['example.SampleTest'])
            report.write_text('<testsuite name="example.SampleTest" tests="2" skipped="1"/>')
            self.assertIn('2 tests, 1 skipped', catalog.verify_results(directory, ['example.SampleTest']))


class LintGuardTest(unittest.TestCase):
    def test_lint_paths_are_portable_and_source_changes_are_new_findings(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / 'lint.xml'
            def finding(path, source):
                report.write_text(f'<issues><issue id="Rule" severity="Error" message="Problem" errorLine1="{source}"><location file="{path}"/></issue></issues>')
                return lint.findings(report)
            before = finding('/Users/dev/project/app/src/main/Example.kt', 'oldCall()')
            self.assertEqual(before, finding('/home/runner/project/app/src/main/Example.kt', 'oldCall()'))
            self.assertEqual(1, sum((finding('/home/runner/project/app/src/main/Example.kt', 'newCall()') - before).values()))

    def test_line_moves_keep_identity_but_additional_errors_are_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / 'lint.xml'
            def findings(line, extra=''):
                report.write_text('<issues><issue id="MissingPermission" severity="Error" message="Permission required">'
                                  f'<location file="src/main/Example.kt" line="{line}"/></issue>{extra}</issues>')
                return lint.findings(report)
            before = findings(10)
            self.assertEqual(before, findings(20))
            duplicate = '<issue id="MissingPermission" severity="Error" message="Permission required"><location file="src/main/Example.kt" line="30"/></issue>'
            self.assertEqual(1, sum((findings(20, duplicate) - before).values()))
            changed = '<issue id="NewIssue" severity="Fatal" message="New failure"><location file="src/main/New.kt"/></issue>'
            self.assertEqual(1, sum((findings(20, changed) - before).values()))


if __name__ == '__main__':
    unittest.main()
