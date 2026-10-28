"""Finite startup evidence/privacy controls; no JVM or native container."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('startup_pair', ROOT / 'scripts/ci/application-startup-native-pair.py')
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class ApplicationStartupPairControls(unittest.TestCase):
    def witness(self, state):
        return {'state': state, 'observedBeans': [], 'failureTypes': ['BeanCreationException', 'IllegalStateException'] if state == 'FENCED' else [],
                **{key: True for key in proof.COMMON_FLAGS},
                **{key: state == 'OPEN' for key in proof.OPEN_FLAGS},
                'exactFence': state == 'FENCED'}

    def test_startup_installs_the_same_exact_positive_catalogue_in_both_peers(self):
        roles = {'consultant': ['consultant', 'group-chat-consultant'],
                 'consultant-basic': ['consultant'], 'user': ['user'],
                 'agency-admin': ['restricted-agency-admin', 'user-admin'],
                 'consultant-agency-admin': ['consultant', 'group-chat-consultant', 'restricted-agency-admin', 'user-admin'],
                 'consultant-agency-admin-basic': ['consultant', 'restricted-agency-admin', 'user-admin']}
        groups = {'/human-roles/' + name: value for name, value in roles.items()}
        profiles = [{'id': name + '-v1', 'groupPath': '/human-roles/' + name, 'realmRoles': value} for name, value in roles.items()]
        groups['/human-roles/consultant-agency-admin-unacknowledged'] = roles['consultant-agency-admin']
        profiles.append({'id': 'combined-unacknowledged-v1', 'groupPath': '/human-roles/consultant-agency-admin-unacknowledged', 'realmRoles': roles['consultant-agency-admin']})
        config = {'ordinaryProfiles': profiles, 'ordinaryAdminOperations': [{'sourceProfiles': ['consultant-agency-admin-basic-v1']}]}
        installed, selected = proof.startup_fixture_catalogue(config, groups)
        self.assertEqual(6, len(selected))
        self.assertEqual(selected, {p['groupPath']: p['realmRoles'] for p in installed['ordinaryProfiles']})
        self.assertEqual(config['ordinaryAdminOperations'], installed['ordinaryAdminOperations'])
        self.assertEqual(7, len(groups))
        self.assertEqual(7, len(config['ordinaryProfiles']))
        for bad in [profiles[1:], profiles + [profiles[0]], [{**p, 'groupPath': '/unreviewed'} if p['id'] == 'consultant-v1' else p for p in profiles]]:
            with self.assertRaises(ValueError): proof.startup_fixture_catalogue({'ordinaryProfiles': bad}, groups)
        changed = dict(groups); changed['/human-roles/consultant'] = ['technical']
        with self.assertRaises(ValueError): proof.startup_fixture_catalogue(config, changed)
        config['ordinaryAdminOperations'].append({'targetProfiles': ['combined-unacknowledged-v1']})
        with self.assertRaises(ValueError): proof.startup_fixture_catalogue(config, groups)

    def test_two_exact_expected_states_have_different_acceptance(self):
        for state in ['FENCED', 'OPEN']:
            self.assertTrue(proof.checked_witness(self.witness(state), state))
            for flag in proof.COMMON_FLAGS:
                value = self.witness(state); value[flag] = False
                self.assertFalse(proof.checked_witness(value, state))
        value = self.witness('FENCED'); value['contextStarted'] = True
        self.assertFalse(proof.checked_witness(value, 'FENCED'))
        value = self.witness('OPEN'); value['securityReady'] = False
        self.assertFalse(proof.checked_witness(value, 'OPEN'))

    def test_fence_does_not_imply_uncreated_graph_and_open_cannot_accept_fence(self):
        value = self.witness('OPEN'); value['exactFence'] = True
        self.assertFalse(proof.checked_witness(value, 'OPEN'))
        value = self.witness('FENCED'); value['scopedOwnersReady'] = True
        self.assertFalse(proof.checked_witness(value, 'FENCED'))

    def test_unknown_fields_states_values_and_secret_bean_names_are_refused(self):
        for value in [{}, self.witness('OTHER'), {**self.witness('FENCED'), 'bearer': 'sensitive'},
                      {**self.witness('FENCED'), 'observedBeans': ['password=secret']},
                      {**self.witness('FENCED'), 'failureTypes': ['Bearer secret']},
                      {**self.witness('FENCED'), 'actualApplication': 1}]:
            with self.assertRaises(ValueError): proof.checked_witness(value, 'FENCED')

    def test_exact_executed_java_method_and_no_skip_required(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); source = reports / ('TEST-' + proof.SUITE + '.xml')
            body = '<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '">'
            source.write_text(body + '</testcase></testsuite>')
            self.assertTrue(proof.sanitize(reports, reports / 'safe.xml')['accepted'])
            source.write_text(body + '<skipped/></testcase></testsuite>')
            self.assertFalse(proof.sanitize(reports, reports / 'safe.xml')['accepted'])

    def test_raw_diagnostics_never_survive_xml(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); output = reports / 'safe.xml'
            (reports / ('TEST-' + proof.SUITE + '.xml')).write_text(
                '<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '"><error>Bearer secret password mail OTP123</error></testcase></testsuite>')
            self.assertFalse(proof.sanitize(reports, output)['accepted'])
            for word in ['Bearer', 'secret', 'password', 'OTP123']:
                self.assertNotIn(word, output.read_text())

    def test_http_assertion_capsule_exports_only_closed_status_integers(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); output = reports / 'safe.xml'
            (reports / ('TEST-' + proof.SUITE + '.xml')).write_text(
                '<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '"><failure>'
                'Bearer secret mail password: expected: &lt;200&gt; but was: &lt;503&gt; '
                'unknown expected: &lt;123&gt; but was: &lt;456&gt;'
                '</failure></testcase></testsuite>')
            value = proof.sanitize(reports, output)
            self.assertFalse(value['accepted'])
            self.assertEqual([{'expected': 200, 'actual': 503}], value['http_status_assertions'])
            for secret in ['Bearer', 'secret', 'mail', 'password', '123', '456']:
                self.assertNotIn(secret, json.dumps(value) + output.read_text())

    def test_sql_input_capsule_exports_only_fixed_resource_number_and_vendor_code(self):
        raw = b'Failed to execute SQL script statement #17 of URL [file:/private/runner/application-startup-native-journals.sql]: secret password Bearer otp123 [42101-240]'
        value = proof.safe_child_input(raw)
        self.assertEqual({'resource': 'C_JOURNAL_FIXTURE', 'statement': 17, 'vendorErrorCodes': [42101]}, value)
        exported = json.dumps(value)
        for secret in ['private', 'password', 'Bearer', 'otp123', 'runner']:
            self.assertNotIn(secret, exported)
        self.assertEqual({}, proof.safe_child_input(b'password=secret unknown resource and payload'))
        self.assertEqual({}, proof.safe_child_input(b'Failed to execute SQL script statement #2 of URL [file:/unknown/secret.sql]'))

    def test_compiled_input_is_only_proof_and_unchanged_resources_and_always_restores(self):
        for raises in [False, True]:
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder); output = root / 'target/test-classes'; output.mkdir(parents=True)
                own = proof.SUITE.replace('.', '/')
                values = {own + '.class': b'proof', own + '$1.class': b'own anonymous',
                    'de/caritas/cob/userservice/api/ForeignTest$Config.class': b'test replacement',
                    'application-testing-credentials.properties': b'unchanged testing resource'}
                for name, value in values.items():
                    path = output / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(value)
                original = proof.test_output_manifest(output); observation = {}
                try:
                    with proof.isolated_test_classes(root, observation):
                        self.assertFalse((output / 'de/caritas/cob/userservice/api/ForeignTest$Config.class').exists())
                        self.assertEqual(b'own anonymous', (output / (own + '$1.class')).read_bytes())
                        self.assertEqual(b'unchanged testing resource', (output / 'application-testing-credentials.properties').read_bytes())
                        if raises: raise RuntimeError('child startup failed')
                except RuntimeError:
                    self.assertTrue(raises)
                self.assertTrue(observation['restoredByteExact'])
                self.assertEqual(original, proof.test_output_manifest(output))
                self.assertEqual(original, observation['restoredTestManifest'])
                self.assertFalse((root / 'target/application-startup-test-class-quarantine').exists())

    def test_cause_capsule_exports_only_fixed_library_and_source_exception_types(self):
        raw = b'org.springframework.beans.factory.NoUniqueBeanDefinitionException: secret password Bearer abc com.secret.UnknownException java.lang.IllegalStateException'
        self.assertEqual(['org.springframework.beans.factory.NoUniqueBeanDefinitionException', 'java.lang.IllegalStateException'], proof.safe_failure_types(raw))
        self.assertEqual([], proof.safe_failure_types(b'org.secret.PasswordSecretException de.secret.PasswordSecretException password=secret'))
        source_type = 'de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException'
        self.assertEqual([source_type], proof.safe_failure_types((source_type + ': Bearer secret').encode()))

    def test_dependency_capsule_accepts_only_existing_production_identifiers(self):
        raw = b"bean with name 'keycloakService' password=secret Bearer abc bean with name 'selfPasswordMaintenance' bean with name 'SensitiveUnknownLabel'"
        self.assertEqual(['keycloakService', 'selfPasswordMaintenance'], proof.safe_dependency_path(raw))
        self.assertEqual([], proof.safe_dependency_path(b"bean with name 'unknown-secret-label'"))

    def test_fenced_witness_requires_actual_closed_cause_chain(self):
        value = self.witness('FENCED'); value['failureTypes'] = []
        self.assertFalse(proof.checked_witness(value, 'FENCED'))
        value['failureTypes'] = ['BeanCreationException', 'OTHER', 'IllegalStateException']
        self.assertFalse(proof.checked_witness(value, 'FENCED'))

    def test_h2_fixture_reads_only_actual_master_sql_and_preserves_new_custody_column(self):
        with tempfile.TemporaryDirectory() as folder:
            path = proof.journal_fixture(Path(folder) / 'result.json')
            source = json.loads(path.with_name('application-startup-native-schema-source.json').read_text())
            self.assertEqual(27, len(source['sources']))
            self.assertIn('suspension_source_json LONGTEXT NULL', path.read_text())
            self.assertEqual(13, path.read_text().count('ALTER TABLE account_inactivity ADD COLUMN IF NOT EXISTS'))
            self.assertNotRegex(path.read_text(), r',\s*ADD COLUMN')
            self.assertEqual([13], list(source['exactSourceMultiAddSplit'].values()))
            self.assertNotIn('DROP TABLE', path.read_text())
            self.assertNotIn('DELETE FROM', path.read_text())
            for name in source['sources']:
                self.assertTrue((ROOT / name).is_file())

    def test_canonical_source_projection_preserves_full_existing_operation_grants(self):
        import copy
        original = {'bindings': [{'subject': 'primary', 'authority': 'PLATFORM',
            'realmRoles': ['agency-admin', 'tenant-admin'], 'protectedProfileIds': ['platform-v1', 'tenant-peer-v1'],
            'accountOperations': [{'operations': ['account.read', 'account.reassign-tenant'], 'targetProfiles': ['tenant-peer-v1']}]},
            {'subject': 'variant', 'authority': 'PLATFORM', 'realmRoles': ['agency-admin', 'tenant-admin'],
             'protectedProfileIds': ['platform-v1'], 'sourceProfileVariants': [{'profileId': 'platform-consultant-v2'}]}],
            'profiles': [{'id': 'platform-v1', 'realmRoles': ['agency-admin', 'tenant-admin']}]}
        projected = proof.startup_source_projection(original, 'primary')
        projected['bindings'][0]['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        projected['profiles'][0]['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        restored = proof.restore_startup_source_targets(original, projected, 'primary')
        expected = copy.deepcopy(original)
        for binding in expected['bindings']: binding['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        expected['profiles'][0]['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        self.assertEqual(expected, restored)
        changed = copy.deepcopy(projected); changed['bindings'][0]['accountOperations'][0]['operations'].append('account.delete')
        with self.assertRaises(ValueError): proof.restore_startup_source_targets(original, changed, 'primary')

    def test_binding_capsule_exports_only_fixed_installed_consumer_names(self):
        value = proof.safe_binding_failure(b'Incomplete scoped account binding: DeleteUserAccountService password=secret Bearer abc')
        self.assertEqual(['DeleteUserAccountService'], value)
        self.assertEqual([], proof.safe_binding_failure(b'Incomplete scoped account binding: PasswordSecret'))

    def test_open_requires_every_actual_http_and_cleanup_journey(self):
        for flag in ['bootNoEffect', 'authenticatedMetadata', 'selfLocaleApplied', 'selfLocaleReplay',
                     'consultantInverseRetention', 'consultantThenAdmin', 'adminThenConsultant', 'pureTenantTwoChildren']:
            value = self.witness('OPEN'); value[flag] = False
            self.assertFalse(proof.checked_witness(value, 'OPEN'), flag)

    def test_workflow_immutable_source_and_finite_default_state(self):
        import yaml
        workflow = yaml.safe_load((ROOT / '.github/workflows/scenario-c-application-startup-native-pair.yml').read_text())
        self.assertEqual(['dev'], workflow['on']['pull_request']['branches'])
        self.assertEqual(['dev', 'codex/367-c-startup-finish-20261023', 'codex/367-c-caller-finish-20261021'], workflow['on']['push']['branches'])
        job = next(iter(workflow['jobs'].values()))
        self.assertEqual('OPEN', job['env']['STARTUP_EXPECTED_STATE'])
        native = [s for s in job['steps'] if s.get('with', {}).get('repository') == 'OpenResilienceInitiative/ORISO-Keycloak']
        self.assertEqual(1, len(native)); self.assertEqual(proof.KC_COMMIT, native[0]['with']['ref'])
        self.assertEqual('bf3d7f8809d829e33e930115221b122be8e8c287', proof.KC_COMMIT)
        self.assertNotIn('@MockitoBean', (ROOT / proof.JAVA_SOURCE).read_text())
        self.assertNotIn('setLazyInitialization', (ROOT / proof.JAVA_SOURCE).read_text())


if __name__ == '__main__': unittest.main()
