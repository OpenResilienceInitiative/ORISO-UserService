"""Evidence controls: absent or wrong native proof cannot become acceptance."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SOURCE = Path(__file__).resolve().parents[2] / "scripts/ci/protected-public-native-pair.py"
spec = importlib.util.spec_from_file_location("protected_pair", SOURCE)
pair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pair)

class ProtectedPairEvidence(unittest.TestCase):
    def test_login_observation_cannot_export_a_raw_response_or_coerced_status(self):
        safe = {"status": 400, "category": "INVALID_USER_CREDENTIALS", "sameTotpAsEnrollment": True}
        self.assertEqual(safe, pair.checked_login_observation(safe))
        for unsafe in [dict(safe, body="Bearer secret"),
                       dict(safe, category="raw private message"),
                       dict(safe, status=True), dict(safe, status="400"),
                       dict(safe, status=200), {"status": 200, "category": "ACCEPTED", "token": "secret"}]:
            with self.subTest(fields=sorted(unsafe)):
                with self.assertRaises(ValueError):
                    pair.checked_login_observation(unsafe)

        email = {"challengeStatus": 400, "challengeType": "EMAIL", "acceptedStatus": 200}
        self.assertEqual(email, pair.checked_email_login_observation(email))
        for unsafe in [dict(email, code="123456"), dict(email, challengeStatus=True),
                       dict(email, challengeType="raw private response")]:
            with self.assertRaises(ValueError):
                pair.checked_email_login_observation(unsafe)

    def test_registration_status_observation_cannot_export_messages(self):
        self.assertEqual(502, pair.registration_status(b"PROTECTED_PUBLIC_REGISTRATION_STATUS=502\nBearer secret"))
        self.assertIsNone(pair.registration_status(b"unrelated private message"))
        for raw in [b"PROTECTED_PUBLIC_REGISTRATION_STATUS=502 secret", b"PROTECTED_PUBLIC_REGISTRATION_STATUS=true", b"PROTECTED_PUBLIC_REGISTRATION_STATUS=502\nPROTECTED_PUBLIC_REGISTRATION_STATUS=200"]:
            with self.assertRaises(ValueError):
                pair.registration_status(raw)

    def test_failure_observation_exports_only_closed_source_locations(self):
        raw = b"Bearer secret; SQL private-row; JdbcProtectedAccountActivation.java:120 ProtectedPublicTenantFixture.java:81 unknown.java:456"
        observed = pair.closed_failure_frames(raw, b"ProtectedAdminOnboardingService.java:42")
        self.assertEqual(["JdbcProtectedAccountActivation.java:120", "ProtectedAdminOnboardingService.java:42", "ProtectedPublicTenantFixture.java:81"], observed)
        self.assertNotIn("secret", str(observed))
        self.assertNotIn("private-row", str(observed))

    def test_xml_error_observation_keeps_only_type_and_closed_source_locations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = ET.Element("testsuite", name=pair.SUITE)
            case = ET.SubElement(suite, "testcase", classname=pair.SUITE, name=pair.CASE)
            error = ET.SubElement(case, "error", type="jakarta.servlet.ServletException")
            error.text = "Bearer secret; SQL private-row; JdbcProtectedAccountActivation.java:120\nCaused by: java.lang.IllegalStateException: secret\nCaused by: org.opentest4j.AssertionFailedError: private-value\n\tat org.springframework.test.web.servlet.MockMvc.perform(MockMvc.java:201)\n\tat example.privateData(secret.java:34)"
            ET.ElementTree(suite).write(root / "TEST-pair.xml")
            result = pair.sanitize(root, root / "sanitized.xml")
            self.assertEqual(["JdbcProtectedAccountActivation.java:120"], result.get("closed_source_failure_frames"))
            self.assertEqual(["jakarta.servlet.ServletException", "java.lang.IllegalStateException", "org.opentest4j.AssertionFailedError"], result.get("failure_types"))
            self.assertEqual(["org.springframework.test.web.servlet.MockMvc#perform@MockMvc.java:201"], result.get("closed_spring_failure_frames"))
            self.assertNotIn("secret", str(result))
            self.assertNotIn("private-row", str(result))
            self.assertNotIn("secret", (root / "sanitized.xml").read_text())

    def proof(self, tags=(), name=pair.CASE, expected=pair.CASE):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = ET.Element("testsuite", name=pair.SUITE)
            row = ET.SubElement(report, "testcase", classname=pair.SUITE, name=name)
            for tag in tags:
                ET.SubElement(row, tag).text = "Sensitive diagnostic must not escape"
            ET.ElementTree(report).write(root / "TEST-pair.xml")
            result = pair.sanitize(root, root / "sanitized.xml", expected)
            self.assertNotIn("Sensitive diagnostic", (root / "sanitized.xml").read_text())
            return result

    def test_exact_one_actual_method_is_required(self):
        self.assertTrue(self.proof()["accepted"])
        self.assertFalse(self.proof(name="unrelated")["accepted"])
        self.assertFalse(self.proof(name=pair.TENANT_CASE)["accepted"])
        self.assertTrue(self.proof(name=pair.TENANT_CASE, expected=pair.TENANT_CASE)["accepted"])
        self.assertTrue(self.proof(name=pair.EMAIL_CASE, expected=pair.EMAIL_CASE)["accepted"])
        self.assertTrue(self.proof(name=pair.TENANT_EMAIL_CASE, expected=pair.TENANT_EMAIL_CASE)["accepted"])
        self.assertTrue(self.proof(name=pair.LOST_COMMIT_CASE, expected=pair.LOST_COMMIT_CASE)["accepted"])
        self.assertTrue(self.proof(name=pair.RESERVED_CASE, expected=pair.RESERVED_CASE)["accepted"])
        with self.assertRaises(ValueError):
            self.proof(name="unrelated", expected="unrelated")

    def test_failure_error_and_skip_cannot_be_accepted(self):
        for tag in ["failure", "error", "skipped"]:
            with self.subTest(tag=tag):
                self.assertFalse(self.proof([tag])["accepted"])

    def test_absent_xml_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            result = pair.sanitize(Path(directory), Path(directory) / "clean.xml")
            self.assertFalse(result["accepted"])
            self.assertEqual(0, result["tests"])

    def test_native_pin_and_finite_pair_workflow_are_bound(self):
        import yaml
        root = SOURCE.parents[2]
        workflow = (root / ".github/workflows/scenario-c-protected-public-native-pair.yml").read_text()
        tree = yaml.safe_load(workflow)
        self.assertEqual({"contents": "read"}, tree["permissions"])
        self.assertIn(pair.KC_COMMIT, workflow)
        self.assertIn(pair.IMAGE, workflow)
        self.assertEqual(["app", "email"], tree["jobs"]["protected-recipient-native-pair"]["strategy"]["matrix"]["kind"])
        self.assertEqual(["PLATFORM_ADMIN", "TENANT_ADMIN"], tree["jobs"]["protected-recipient-native-pair"]["strategy"]["matrix"]["role"])
        matrix = tree["jobs"]["protected-recipient-native-pair"]["strategy"]["matrix"]
        self.assertEqual(["happy"], matrix["scenario"])
        self.assertEqual([{"kind": "app", "role": "PLATFORM_ADMIN", "scenario": "lost-commit"},
                          {"kind": "app", "role": "TENANT_ADMIN", "scenario": "reserved-new"}], matrix["include"])
        self.assertIn("-DfailIfNoTests=true", SOURCE.read_text())
        self.assertIn("-Dsurefire.failIfNoSpecifiedTests=true", SOURCE.read_text())
        self.assertIn("capture_output=True, timeout=180", SOURCE.read_text())
        self.assertNotIn("continue-on-error", workflow)
        self.assertIn("PROTECTED_PROOF_RESOURCE_PROFILE: github-ubuntu-24-amd64-v1", workflow)
        import fnmatch
        self.assertTrue(fnmatch.fnmatch("protected-public-native-pair.json", "protected-public-native-*.json"))
        self.assertIn("target/protected-public-native/protected-public-native-pair.json", SOURCE.read_text())
        self.assertIn("target/protected-public-native/protected-public-native-*.json", workflow)

if __name__ == "__main__":
    unittest.main()
