import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'scripts/tests/fixtures/ro_words.txt'
RUNTIME_FIXTURE = ROOT / 'app/src/test/resources/dictionaries/ro_base.dict'


class DictionaryConversionTest(unittest.TestCase):
    def run_script(self, script, *args):
        return subprocess.run([sys.executable, str(ROOT / script), *map(str, args)],
                              capture_output=True, text=True, encoding='utf-8')

    def test_documented_commands_produce_runtime_fixture(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            intermediate = folder / 'ro_base.json'
            converted = self.run_script('convert_dictionaries.py', FIXTURE, intermediate, '--scale-frequencies')
            self.assertEqual(0, converted.returncode, converted.stderr)
            indexed = self.run_script('scripts/preprocess_dictionaries.py', intermediate,
                                      '--output-dir', folder / 'output')
            self.assertEqual(0, indexed.returncode, indexed.stderr)
            result = (folder / 'output/ro_base.dict').read_bytes()
            self.assertEqual(RUNTIME_FIXTURE.read_bytes(), result)
            self.assertTrue(result.startswith(b'{'))
            index = json.loads(result)
            expected = {'scoala': ('școală', 255), 'tara': ('țară', 195),
                        'paine': ('pâine', 135), 'inger': ('înger', 75)}
            for key, (word, frequency) in expected.items():
                entry = {'word': word, 'frequency': frequency, 'source': 0}
                self.assertEqual([entry], index['normalizedIndex'][key])
                for length in range(1, min(4, len(key)) + 1):
                    self.assertIn(entry, index['prefixCache'][key[:length]])

    def test_scaling_is_optional_and_preserves_count_order(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'words.txt'
            target = Path(folder) / 'ro_base.json'
            source.write_text('școală 1000000\nțară 1000\npâine 1\n', encoding='utf-8')
            plain = self.run_script('convert_dictionaries.py', source, target)
            self.assertEqual(0, plain.returncode, plain.stderr)
            self.assertEqual([1000000, 1000, 1], [entry['f'] for entry in json.loads(target.read_text())])
            scaled = self.run_script('convert_dictionaries.py', source, target, '--scale-frequencies')
            self.assertEqual(0, scaled.returncode, scaled.stderr)
            counts = [entry['f'] for entry in json.loads(target.read_text())]
            self.assertEqual(255, counts[0])
            self.assertGreater(counts[0], counts[1])
            self.assertGreater(counts[1], counts[2])
            self.assertGreaterEqual(counts[2], 1)

    def test_invalid_counts_fail_without_replacing_output(self):
        for line in ('școală nope', 'școală 2147483648', 'școală -1', 'școală 0',
                     'școală 1.5', 'școală', ''):
            with self.subTest(line=line), tempfile.TemporaryDirectory() as folder:
                source = Path(folder) / 'words.txt'
                target = Path(folder) / 'ro_base.json'
                source.write_text('țară 220\n' + line + '\n' if line else '', encoding='utf-8')
                target.write_text('existing', encoding='utf-8')
                result = self.run_script('convert_dictionaries.py', source, target)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Error:', result.stderr)
                self.assertEqual('existing', target.read_text())

    def test_single_file_mode_requires_language_filename(self):
        result = self.run_script('scripts/preprocess_dictionaries.py', 'words.json')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('_base.json', result.stderr)
