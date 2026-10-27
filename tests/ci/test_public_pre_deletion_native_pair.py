"""Offline closure controls for the NEW hosted actual creator/deletion pair."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / 'scripts/ci/public-pre-deletion-native-pair.py'
SPEC = importlib.util.spec_from_file_location('public_pre_deletion_pair', PATH)
pair = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pair)


CANONICAL_FUNCTION_HASHES = {'canonical_platform_configuration': 'ea9a5593096123ccee265b5691440029aa0aa3ab43abffac5b2f55977f67b060', 'install_canonical_platform_source': '9002a00be83db08b4b37a2762f190c783cff488db6935d6a5364516eec93eb81', 'verify_canonical_platform_human': 'c54bafb19680483ec08877dde79ee2e927eeb2fa3593337e4e51621bd645316c'}
ORIGINAL_METHODS_HASH = '3ac374d53fd85afb8a0cc8e497297933d5b6dd9f0dc0a62de1577852b391d0f9'


class PublicPreDeletionPairControls(unittest.TestCase):
    def witness(self, kind='self-asker'):
        return {'kind': kind, **{key: key != 'lostReplyExpiredHumanRecovered' or kind == 'self-asker' for key in pair.WITNESS_FLAGS}}

    def test_finite_three_kinds_and_every_flag_are_required(self):
        self.assertEqual({'self-asker', 'admin-consultant', 'admin-asker'}, set(pair.CASES))
        for kind in pair.CASES:
            self.assertTrue(pair.checked_witness(self.witness(kind), kind))
        for key in pair.WITNESS_FLAGS:
            value = self.witness(); value[key] = False
            self.assertFalse(pair.checked_witness(value, 'self-asker'))
            for malformed in [None, 1, 'true']:
                value[key] = malformed
                with self.assertRaises(ValueError): pair.checked_witness(value, 'self-asker')

    def test_extra_missing_kind_and_foreign_witness_close(self):
        for value in [{}, {**self.witness(), 'token': 'not-allowed'}, self.witness('other')]:
            with self.assertRaises(ValueError): pair.checked_witness(value, 'self-asker')
        with self.assertRaises(ValueError): pair.checked_witness(self.witness(), 'other')

    def test_xml_exact_inventory_and_failure_payload_are_sanitized(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); reports = root / 'raw'; reports.mkdir()
            suite = ET.Element('testsuite', name=pair.SUITE, tests='1', failures='1', errors='0', skipped='0')
            case = ET.SubElement(suite, 'testcase', classname=pair.SUITE, name=pair.CASES['self-asker'])
            ET.SubElement(case, 'failure', message='sensitive').text = 'secret bearer\nPublicPreDeletionNativePairProof.java:71'
            ET.ElementTree(suite).write(reports / 'TEST-pair.xml')
            output = root / 'clean.xml'
            result = pair.sanitize(reports, output, 'self-asker')
            self.assertTrue(result['exact_method_inventory']); self.assertFalse(result['accepted'])
            self.assertEqual(['PublicPreDeletionNativePairProof.java:71'], result['failure_locations'])
            self.assertNotIn('secret', output.read_text()); self.assertNotIn('bearer', output.read_text())
            self.assertFalse(pair.sanitize(reports, output, 'admin-consultant')['exact_method_inventory'])

    def test_absent_xml_is_not_behavior_acceptance(self):
        with tempfile.TemporaryDirectory() as folder:
            result = pair.sanitize(Path(folder), Path(folder) / 'clean.xml', 'self-asker')
            self.assertEqual(0, result['tests']); self.assertFalse(result['accepted'])
            self.assertFalse(result['exact_method_inventory'])

    def test_native_source_and_image_are_fixed(self):
        self.assertEqual('6ef53b161606a20bdba6fa0e9f9c3ebfcad99dd4', pair.KC_COMMIT)
        self.assertEqual('quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992', pair.IMAGE)
        self.assertIn('actualCreatorCommitted', pair.WITNESS_FLAGS)
        self.assertIn('originalSqlBirthPreserved', pair.WITNESS_FLAGS)
        self.assertIn('strictRealReceipt', pair.WITNESS_FLAGS)
        self.assertIn('oneLocalCallback', pair.WITNESS_FLAGS)

    def protocol(self, kind):
        return {'creatorCreate201': 1, 'creatorOwnedRead200': 1, 'creatorCommit200': 1,
            'maintenanceRead200': 1, 'deactivateAttempts': 1 if kind == 'self-asker' else 2,
            'deactivate200': 1 if kind == 'self-asker' else 2,
            'outcomeRead200': 1 if kind == 'self-asker' else 0,
            'humanPasswordGrants200': 2 if kind == 'self-asker' else 0}

    def test_creator_and_cleanup_protocol_counts_are_separately_closed(self):
        for kind in pair.CASES:
            value = self.protocol(kind)
            self.assertTrue(pair.checked_protocol(value, kind))
            for key in value:
                changed = dict(value); changed[key] += 1
                self.assertFalse(pair.checked_protocol(changed, kind))

    def test_protocol_counts_reject_foreign_schema_and_noninteger_counts(self):
        value = self.protocol('self-asker')
        for malformed in [{}, {**value, 'rawReceipt': 'forbidden'}, {**value, 'creatorCreate201': True}]:
            with self.assertRaises(ValueError): pair.checked_protocol(malformed, 'self-asker')

    def test_new_workflow_retains_fixed_resource_caps_and_three_kinds(self):
        import yaml
        value = yaml.safe_load((ROOT / '.github/workflows/scenario-c-public-pre-deletion-native-pair.yml').read_text())
        job = value['jobs']['public-native-pair']
        self.assertEqual(['self-asker', 'admin-asker', 'admin-consultant'], job['strategy']['matrix']['kind'])
        self.assertEqual(30, job['timeout-minutes'])
        text = json.dumps(value)
        for literal in [pair.KC_COMMIT, 'PyYAML==6.0.3', '21.0.12+8.0.LTS']:
            self.assertIn(literal, text)
        self.assertNotIn('continue-on-error', text)
        self.assertIn('-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2', PATH.read_text())

    def test_canonical_installer_functions_are_exact_qualified_baseline(self):
        import ast
        source = PATH.read_text(); tree = ast.parse(source)
        reference = ROOT.parent / 'state/completion-20261021/predeletion/canonical-platform-role-helper-a71.reference.py'
        # Durable reference is optional in checkout; the fixed source hashes are always checked.
        import hashlib
        expected = CANONICAL_FUNCTION_HASHES
        for node in tree.body:
            if isinstance(node, ast.FunctionDef) and node.name in expected:
                segment = ast.get_source_segment(source, node)
                self.assertEqual(expected[node.name], hashlib.sha256(segment.encode()).hexdigest())
        self.assertEqual(set(expected), {node.name for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in expected})

    def test_two_original_installer_ready_calls_and_every_optional_renewal_are_verified(self):
        import copy
        ready = {'phase': 'NATIVE_READY', 'tokenCount': 1, 'subjectClaimPresent': [False],
            'pinnedMasterIssuer': [True], 'userReadStatus': 200, 'sessionReadStatus': 200,
            'sameActualEnabledMasterInstallerAndNativeSessionVerified': True}
        renewal = {**ready, 'phase': 'RENEWAL', 'tokenCount': 2,
            'subjectClaimPresent': [False, False], 'pinnedMasterIssuer': [True, True]}
        self.assertTrue(pair.checked_installer_checks([ready, ready]))
        self.assertTrue(pair.checked_installer_checks([ready, ready, ready, renewal]))
        for checks in [[], [ready], [ready, ready, ready], [ready, renewal], [ready, ready, renewal]]:
            self.assertFalse(pair.checked_installer_checks(checks))
        for key, value in [('userReadStatus', 403), ('sessionReadStatus', 403),
                ('sameActualEnabledMasterInstallerAndNativeSessionVerified', False), ('pinnedMasterIssuer', [False])]:
            checks = copy.deepcopy([ready, ready]); checks[1][key] = value
            self.assertFalse(pair.checked_installer_checks(checks))

    def test_fixture_visibility_keeps_original_38_case_inventory(self):
        import hashlib
        import re
        path = ROOT / 'src/test/java/de/caritas/cob/userservice/api/workflow/delete/service/PublicPreDeletionJpaTest.java'
        text = path.read_text()
        methods = re.findall(r'@Test\s+void ([A-Za-z0-9_]+)\(', text)
        self.assertEqual(34, len(methods))
        self.assertIn("@org.junit.jupiter.params.provider.ValueSource(ints = {1, 123456001, 123456789, 999999999})", text)
        self.assertEqual(ORIGINAL_METHODS_HASH, hashlib.sha256('\n'.join(methods).encode()).hexdigest())
        self.assertNotIn('@MockitoBean', (ROOT / 'src/test/java/de/caritas/cob/userservice/api/workflow/delete/service/PublicPreDeletionNativePairProof.java').read_text())


if __name__ == '__main__': unittest.main()
