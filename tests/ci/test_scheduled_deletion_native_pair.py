"""Offline closure controls for the NEW hosted actual creator/deletion pair."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / 'scripts/ci/scheduled-deletion-native-pair.py'
SPEC = importlib.util.spec_from_file_location('scheduled_deletion_pair', PATH)
pair = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pair)


class ScheduledDeletionPairControls(unittest.TestCase):
    def witness(self, kind='asker'):
        return {'kind': kind, **{key: key != 'lostReplyRetainedBeforeRetry' or kind == 'asker' for key in pair.WITNESS_FLAGS}}

    def test_finite_three_kinds_and_every_flag_are_required(self):
        self.assertEqual({'asker', 'consultant', 'inactivity'}, set(pair.CASES))
        for kind in pair.CASES:
            self.assertTrue(pair.checked_witness(self.witness(kind), kind))
        for key in pair.WITNESS_FLAGS:
            value = self.witness(); value[key] = False
            self.assertFalse(pair.checked_witness(value, 'asker'))
            for malformed in [None, 1, 'true']:
                value[key] = malformed
                with self.assertRaises(ValueError): pair.checked_witness(value, 'asker')

    def test_extra_missing_kind_and_foreign_witness_close(self):
        for value in [{}, {**self.witness(), 'token': 'not-allowed'}, self.witness('other')]:
            with self.assertRaises(ValueError): pair.checked_witness(value, 'asker')
        with self.assertRaises(ValueError): pair.checked_witness(self.witness(), 'other')

    def test_xml_exact_inventory_and_failure_payload_are_sanitized(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); reports = root / 'raw'; reports.mkdir()
            suite = ET.Element('testsuite', name=pair.SUITE, tests='1', failures='1', errors='0', skipped='0')
            case = ET.SubElement(suite, 'testcase', classname=pair.SUITE, name=pair.CASES['asker'])
            ET.SubElement(case, 'failure', message='sensitive').text = 'secret bearer\nUserScheduledDeletionNativePairProof.java:71'
            ET.ElementTree(suite).write(reports / 'TEST-pair.xml')
            output = root / 'clean.xml'
            result = pair.sanitize(reports, output, 'asker')
            self.assertTrue(result['exact_method_inventory']); self.assertFalse(result['accepted'])
            self.assertEqual(['UserScheduledDeletionNativePairProof.java:71'], result['failure_locations'])
            self.assertNotIn('secret', output.read_text()); self.assertNotIn('bearer', output.read_text())
            self.assertFalse(pair.sanitize(reports, output, 'consultant')['exact_method_inventory'])

    def test_absent_xml_is_not_behavior_acceptance(self):
        with tempfile.TemporaryDirectory() as folder:
            result = pair.sanitize(Path(folder), Path(folder) / 'clean.xml', 'asker')
            self.assertEqual(0, result['tests']); self.assertFalse(result['accepted'])
            self.assertFalse(result['exact_method_inventory'])

    def test_native_source_and_image_are_fixed(self):
        self.assertEqual('5d5db75faa1c36ff6d6ed2eb41f71ecd415e6aeb', pair.KC_COMMIT)
        self.assertEqual('quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992', pair.IMAGE)
        self.assertIn('actualCreatorCommitted', pair.WITNESS_FLAGS)
        self.assertIn('claimedOriginalSqlBirthPreserved', pair.WITNESS_FLAGS)
        self.assertIn('strictRealReceipt', pair.WITNESS_FLAGS)
        self.assertIn('oneLocalCallback', pair.WITNESS_FLAGS)

    def test_creator_and_cleanup_protocol_counts_are_separately_closed(self):
        for kind in pair.CASES:
            value = {'creatorCreate201': 1, 'creatorOwnedRead200': 1, 'creatorCommit200': 1,
                'cleanupRead200': 2 if kind == 'inactivity' else 1,
                'cleanupDelete200': 2 if kind == 'asker' else 1,
                'cleanupDeleteAttempts': 2 if kind == 'asker' else 1}
            self.assertTrue(pair.checked_protocol(value, kind))
            value['creatorCommit200'] = 0
            self.assertFalse(pair.checked_protocol(value, kind))

    def test_protocol_counts_reject_foreign_schema_and_noninteger_counts(self):
        value = {'creatorCreate201': 1, 'creatorOwnedRead200': 1, 'creatorCommit200': 1,
            'cleanupRead200': 1, 'cleanupDelete200': 2, 'cleanupDeleteAttempts': 2}
        for malformed in [{}, {**value, 'rawReceipt': 'forbidden'}, {**value, 'creatorCreate201': True}]:
            with self.assertRaises(ValueError): pair.checked_protocol(malformed, 'asker')

    def test_new_workflow_retains_fixed_resource_caps_and_three_kinds(self):
        import yaml
        value = yaml.safe_load((ROOT / '.github/workflows/scenario-c-scheduled-deletion-native-pair.yml').read_text())
        job = value['jobs']['scheduled-native-pair']
        self.assertEqual(['asker', 'consultant', 'inactivity'], job['strategy']['matrix']['kind'])
        self.assertEqual(30, job['timeout-minutes'])
        text = json.dumps(value)
        for literal in [pair.KC_COMMIT, 'PyYAML==6.0.3', '21.0.12+8.0.LTS']:
            self.assertIn(literal, text)
        self.assertNotIn('continue-on-error', text)
        self.assertIn('-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2', PATH.read_text())


if __name__ == '__main__': unittest.main()
