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
        self.assertEqual([], proof.safe_failure_types(b'org.secret.PasswordSecretException password=secret'))

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

    def test_workflow_immutable_source_and_finite_default_state(self):
        import yaml
        workflow = yaml.safe_load((ROOT / '.github/workflows/scenario-c-application-startup-native-pair.yml').read_text())
        self.assertEqual(['dev'], workflow['on']['pull_request']['branches'])
        self.assertEqual(['dev', 'codex/367-c-startup-finish-20261023', 'codex/367-c-caller-finish-20261021'], workflow['on']['push']['branches'])
        job = next(iter(workflow['jobs'].values()))
        self.assertEqual('FENCED', job['env']['STARTUP_EXPECTED_STATE'])
        native = [s for s in job['steps'] if s.get('with', {}).get('repository') == 'OpenResilienceInitiative/ORISO-Keycloak']
        self.assertEqual(1, len(native)); self.assertEqual(proof.KC_COMMIT, native[0]['with']['ref'])
        self.assertEqual('6ef53b161606a20bdba6fa0e9f9c3ebfcad99dd4', proof.KC_COMMIT)
        self.assertNotIn('@MockitoBean', (ROOT / proof.JAVA_SOURCE).read_text())
        self.assertNotIn('setLazyInitialization', (ROOT / proof.JAVA_SOURCE).read_text())


if __name__ == '__main__': unittest.main()
