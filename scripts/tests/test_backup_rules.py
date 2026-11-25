"""Verify that Android backup allowlists never include generated Direct Boot data."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[2] / 'app/src/main/res/xml'


def matches(rule, domain, path):
    prefix = rule.get('path').rstrip('/')
    return rule.get('domain') == domain and (
        prefix == '.' or path == prefix or path.startswith(prefix + '/'))


def backed_up(rules, domain, path):
    includes = rules.findall('include')
    return (not includes or any(matches(rule, domain, path) for rule in includes)) and not any(
        matches(rule, domain, path) for rule in rules.findall('exclude'))


class BackupRulesTest(unittest.TestCase):
    def test_direct_boot_cache_is_excluded_from_every_backup_transport(self):
        legacy = ET.parse(RES / 'backup_rules.xml').getroot()
        modern = ET.parse(RES / 'data_extraction_rules.xml').getroot()
        for rules in (legacy, modern.find('cloud-backup'), modern.find('device-transfer')):
            with self.subTest(transport=rules.tag):
                for path in ('direct_boot_files/current', 'direct_boot_files/generation-123/layout.json'):
                    self.assertFalse(backed_up(rules, 'device_file', path))
                self.assertFalse(backed_up(rules, 'device_sharedpref', 'direct_boot_keyboard_config.xml'))
                self.assertTrue(backed_up(rules, 'sharedpref', 'plektra_prefs.xml'))
                self.assertTrue(backed_up(rules, 'file', 'keyboard_layouts/custom.json'))
                self.assertTrue(backed_up(rules, 'file', 'user_defaults.json'))
                self.assertFalse(backed_up(rules, 'sharedpref', 'speech_credentials.xml'))
