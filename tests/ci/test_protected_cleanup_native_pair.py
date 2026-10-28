import importlib.util
import tempfile
import unittest
from pathlib import Path

path = Path(__file__).resolve().parents[2] / 'scripts/ci/protected-cleanup-native-pair.py'
spec = importlib.util.spec_from_file_location('cleanup_pair', path)
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class CleanupPairControls(unittest.TestCase):
    def parse(self, body):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            if body is not None:
                (root / 'TEST-owned.xml').write_text(body)
            output = root / 'safe.xml'
            result = proof.sanitize(root, output)
            return result, output.read_text()

    def suite(self, method=None, extra=''):
        return f'<testsuite name="{proof.SUITE}" tests="1"><testcase classname="{proof.SUITE}" name="{method or proof.CASE}">{extra}</testcase></testsuite>'

    def test_missing_or_wrong_inventory_cannot_be_native_credit(self):
        self.assertFalse(self.parse(None)[0]['accepted'])
        self.assertFalse(self.parse(self.suite('differentMethod'))[0]['accepted'])

    def test_exact_one_case_is_required_for_green(self):
        result, _ = self.parse(self.suite())
        self.assertTrue(result['accepted'])
        self.assertEqual(1, result['tests'])

    def test_failure_and_skip_refuse_without_copying_sensitive_text(self):
        for kind in ('failure', 'error', 'skipped'):
            result, clean = self.parse(self.suite(extra=f'<{kind} message="never-copy">never-copy</{kind}>'))
            self.assertFalse(result['accepted'])
            self.assertNotIn('never-copy', clean)

    def test_same_key_native_wrapper_and_actual_broker_crossing_are_mandatory(self):
        root = Path(__file__).resolve().parents[2]
        java = (root / 'src/test/java/de/caritas/cob/userservice/api/adapters/keycloak/lifecycle/ProtectedAccountCleanupNativePairProof.java').read_text()
        self.assertIn('new TaskActorTokenBroker(actors, machineAuth, decoder, config)', java)
        self.assertIn('broker.issueCheckedMaintenance', java)
        self.assertIn('broker.issueCheckedProvisioning', java)
        self.assertIn('coordinator.reconcileTerminal(73)', java)
        self.assertIn('restarted.withAcknowledged', java)
        self.assertNotIn('mock(ProtectedAccountRecovery.class)', java)
        self.assertEqual('05fefa772dda8b248be9531e0552aa9910e1b71e', proof.KC_COMMIT)


if __name__ == '__main__':
    unittest.main()
