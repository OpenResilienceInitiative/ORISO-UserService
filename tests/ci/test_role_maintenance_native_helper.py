"""Offline gate controls only; never native or public acceptance evidence."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

PATH = Path(__file__).resolve().parents[2] / 'scripts/ci/role-maintenance-native.py'
SPEC = importlib.util.spec_from_file_location('role_maintenance_native', PATH)
HELPER = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(HELPER)


class RoleMaintenanceNativeGateTest(unittest.TestCase):
    def witness(self):
        return {'kind': HELPER.KIND, **{flag: True for flag in HELPER.FLAGS}}

    def test_only_exact_closed_nonsecret_complete_witness_passes(self):
        self.assertTrue(HELPER.checked_witness(self.witness()))
        value = self.witness(); value['actualJpa'] = False
        self.assertFalse(HELPER.checked_witness(value))
        for edit in ['missing', 'extra', 'string', 'number', 'null', 'kind']:
            with self.subTest(edit=edit):
                value = self.witness()
                if edit == 'missing': value.pop('actualJpa')
                elif edit == 'extra': value['rawToken'] = 'synthetic'
                elif edit == 'kind': value['kind'] = 'password'
                else: value['actualJpa'] = {'string': 'true', 'number': 1, 'null': None}[edit]
                with self.assertRaises(ValueError): HELPER.checked_witness(value)

    def platform(self):
        return {'bindings': [{'subject': 'human', 'authority': 'PLATFORM', 'authorizerTenantIds': [0],
            'humanClientIds': ['protected-human-web'], 'audienceSets': [['userservice']],
            'protectedProfileIds': ['platform-v1'], 'realmRoles': ['agency-admin', 'tenant-admin']}],
            'profiles': [{'id': 'platform-v1', 'kind': 'PLATFORM_ADMIN', 'groupId': 'group',
                'groupPath': '/protected-admins/platform-admins', 'realmRoles': ['agency-admin', 'tenant-admin']}],
            'unrelatedTargetPolicy': {'operations': ['account.read']}}

    def test_canonical_platform_source_changes_only_exact_existing_binding_and_profile(self):
        original = self.platform(); saved = copy.deepcopy(original)
        result = HELPER.canonical_platform_configuration(original, 'human', 'group')
        self.assertEqual(original, saved)
        self.assertEqual(['agency-admin', 'tenant-admin', 'user-admin'], result['bindings'][0]['realmRoles'])
        self.assertEqual(result['bindings'][0]['realmRoles'], result['profiles'][0]['realmRoles'])
        result['bindings'][0]['realmRoles'] = original['bindings'][0]['realmRoles']
        result['profiles'][0]['realmRoles'] = original['profiles'][0]['realmRoles']
        self.assertEqual(original, result)

    def test_foreign_or_ambiguous_platform_catalog_never_mints_a_source(self):
        for change in ['subject', 'authority', 'tenant', 'client', 'audience', 'roles', 'duplicate', 'profile', 'group']:
            with self.subTest(change=change):
                value = self.platform(); binding = value['bindings'][0]
                if change == 'subject': binding['subject'] = 'foreign'
                elif change == 'authority': binding['authority'] = 'TENANT_PEER'
                elif change == 'tenant': binding['authorizerTenantIds'] = [7]
                elif change == 'client': binding['humanClientIds'] = ['foreign']
                elif change == 'audience': binding['audienceSets'] = [['userservice', 'other']]
                elif change == 'roles': binding['realmRoles'].append('user-admin')
                elif change == 'duplicate': value['bindings'].append(copy.deepcopy(binding))
                elif change == 'profile': value['profiles'][0]['kind'] = 'TENANT_ADMIN'
                else: value['profiles'][0]['groupId'] = 'foreign'
                with self.assertRaises(ValueError): HELPER.canonical_platform_configuration(value, 'human', 'group')

    def policy(self):
        return {'version': 1, 'native': {'issuer': 'fixed'}, 'ordinaryProfiles': [{'id': 'combined', 'version': 1, 'realmRoles': ['consultant']}],
            'adminOperations': [{'authority': 'PLATFORM', 'targetProfiles': ['combined'], 'selfOnly': False,
                'operations': ['account.read', 'account.profile-transition', 'account.profile']}]}

    def test_withdrawal_changes_only_the_explicit_logical_grant(self):
        original = self.policy(); saved = copy.deepcopy(original)
        result = HELPER.withdrawn_policy(original)
        self.assertEqual(saved, original)
        self.assertEqual(['account.read', 'account.profile'], result['adminOperations'][0]['operations'])
        result['adminOperations'] = copy.deepcopy(original['adminOperations'])
        self.assertEqual(original, result)

    def test_missing_grant_read_and_duplicate_operations_fail_closed(self):
        for operations in [['account.read'], ['account.profile-transition'], ['account.read', 'account.profile-transition', 'account.read']]:
            value = self.policy(); value['adminOperations'][0]['operations'] = operations
            with self.subTest(operations=operations), self.assertRaises(ValueError): HELPER.withdrawn_policy(value)
        for value in [{}, None, {'adminOperations': None}]:
            with self.subTest(shape=type(value).__name__), self.assertRaises(ValueError): HELPER.withdrawn_policy(value)

    def xml(self, directory, *, suite=HELPER.SUITE, name=HELPER.CASE, problem=None, duplicate=False):
        attrs = {'name': suite, 'tests': '2' if duplicate else '1', 'failures': '1' if problem == 'failure' else '0',
            'errors': '1' if problem == 'error' else '0', 'skipped': '1' if problem == 'skipped' else '0'}
        value = ET.Element('testsuite', attrs)
        case = ET.SubElement(value, 'testcase', classname=suite, name=name)
        if problem:
            child = ET.SubElement(case, problem, message='sensitive-synthetic-raw-message')
            child.text = 'sensitive-synthetic-body UserRoleMaintenanceNativePairProof.java:123'
        if duplicate: ET.SubElement(value, 'testcase', classname=suite, name=name)
        ET.ElementTree(value).write(Path(directory) / 'TEST-proof.xml')

    def test_exact_one_actual_method_is_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'clean.xml'; self.xml(tmp)
            self.assertTrue(HELPER.sanitize(tmp, out)['accepted'])
        for suite, name, duplicate in [('foreign', HELPER.CASE, False), (HELPER.SUITE, 'foreign', False), (HELPER.SUITE, HELPER.CASE, True)]:
            with self.subTest(suite=suite, name=name, duplicate=duplicate), tempfile.TemporaryDirectory() as tmp:
                self.xml(tmp, suite=suite, name=name, duplicate=duplicate)
                self.assertFalse(HELPER.sanitize(tmp, Path(tmp) / 'clean.xml')['accepted'])

    def test_failures_errors_skips_are_redacted_and_never_green(self):
        for tag in ['failure', 'error', 'skipped']:
            with self.subTest(tag=tag), tempfile.TemporaryDirectory() as tmp:
                self.xml(tmp, problem=tag); out = Path(tmp) / 'clean.xml'
                result = HELPER.sanitize(tmp, out)
                self.assertFalse(result['accepted'])
                self.assertNotIn('sensitive-synthetic', out.read_text())
                self.assertEqual(['UserRoleMaintenanceNativePairProof.java:123'], result['failure_locations'])

    def test_absent_xml_is_not_delivery(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(HELPER.sanitize(tmp, Path(tmp) / 'clean.xml')['accepted'])

    def test_nonhosted_and_foreign_kind_are_refused_before_any_fixture(self):
        for system, gha, kind in [('Darwin', 'true', HELPER.KIND), ('Linux', 'false', HELPER.KIND), ('Linux', 'true', 'locale')]:
            with self.subTest(system=system, gha=gha, kind=kind), patch.object(HELPER.platform, 'system', return_value=system), patch.dict(HELPER.os.environ, {'GITHUB_ACTIONS': gha, 'PAIR_KIND': kind}):
                with self.assertRaises(ValueError): HELPER.native(None, None, None)


if __name__ == '__main__': unittest.main()
