import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("self_app_native_pair", ROOT / "scripts/ci/self-app-native-pair.py")
PROOF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROOF)


class SelfAppNativePairEvidenceTest(unittest.TestCase):
    def test_only_actual_one_case_no_skip_xml_can_qualify(self):
        for kind in ("green", "failure", "error", "skipped", "missing", "duplicate", "stale", "wrong-count"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                suite = ET.Element("testsuite", name=PROOF.SUITE)
                if kind != "missing":
                    row = ET.SubElement(suite, "testcase", classname=PROOF.SUITE, name=PROOF.CASE)
                    if kind in {"failure", "error", "skipped"}:
                        ET.SubElement(row, kind, message="raw JWT/password/native body").text = "secret\nat example.UserSelfAppOtpNativePairProof.method(UserSelfAppOtpNativePairProof.java:123)"
                if kind == "duplicate": ET.SubElement(suite, "testcase", classname=PROOF.SUITE, name=PROOF.CASE)
                if kind == "stale": suite.set("name", "other.suite")
                if kind == "wrong-count": suite.set("tests", "2")
                ET.ElementTree(suite).write(root / "TEST-pair.xml")
                output = root / "sanitized.xml"
                result = PROOF.sanitize(root, output)
                self.assertEqual(kind == "green", result["accepted"])
                self.assertNotIn("secret", output.read_text())
                self.assertNotIn("password", output.read_text())
                self.assertEqual(["UserSelfAppOtpNativePairProof.java:123"] if kind in {"failure", "error", "skipped"} else [], result["failure_source_locations"])

    def test_email_selects_exact_one_method_and_rejects_app_or_unknown_pair(self):
        for kind in ("green", "failure", "error", "skipped", "missing", "duplicate", "wrong-app"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                suite = ET.Element("testsuite", name=PROOF.SUITE)
                if kind != "missing":
                    row = ET.SubElement(suite, "testcase", classname=PROOF.SUITE,
                                        name=PROOF.CASE if kind == "wrong-app" else PROOF.EMAIL_CASE)
                    if kind in {"failure", "error", "skipped"}:
                        ET.SubElement(row, kind, message="secret recipient/native code/JWT")
                    if kind == "duplicate":
                        ET.SubElement(suite, "testcase", classname=PROOF.SUITE, name=PROOF.EMAIL_CASE)
                ET.ElementTree(suite).write(root / "TEST-pair.xml")
                result = PROOF.sanitize(root, root / "clean.xml", "email")
                self.assertEqual(kind == "green", result["accepted"])
                self.assertNotIn("secret", (root / "clean.xml").read_text())
                with self.assertRaises(ValueError):
                    PROOF.sanitize(root, root / "clean.xml", "other")

    def test_delivered_code_requires_one_actual_message_and_one_distinct_code(self):
        self.assertEqual("654321", PROOF.delivered_code([b"Content-Type: text/plain\n\n654321"]))
        for messages in ([], [b"Content-Type: text/plain\n\nnone"], [b"Content-Type: text/plain\n\n654321 and 123456"], [b"one", b"two"]):
            with self.subTest(count=len(messages)), self.assertRaises(ValueError): PROOF.delivered_code(messages)

    def test_only_fixture_installer_lifetime_can_trigger_preemptive_refresh(self):
        import base64
        import json
        def token(expiry):
            encoded = base64.urlsafe_b64encode(json.dumps({"exp": expiry}).encode()).decode().rstrip("=")
            return "fixture." + encoded + ".native-signature"
        self.assertTrue(PROOF.fixture_installer_expired(token(90), 100))
        self.assertFalse(PROOF.fixture_installer_expired(token(200), 100))
        for value in [None, "200", True, -1]:
            with self.assertRaises(ValueError):
                PROOF.fixture_installer_expired(token(value), 100)

    def test_native_workflow_has_read_only_pins_required_builds_and_bounded_cleanup(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/scenario-c-self-app-native-pair.yml").read_text())
        self.assertEqual({"contents": "read"}, workflow["permissions"])
        job = workflow["jobs"]["public-app-native-pair"]
        self.assertEqual("ubuntu-24.04", job["runs-on"])
        self.assertEqual(["app", "email"], job["strategy"]["matrix"]["kind"])
        self.assertFalse(job["strategy"]["fail-fast"])
        self.assertEqual("${{ matrix.kind }}", job["env"]["PAIR_KIND"])
        steps = job["steps"]
        native = next(s for s in steps if "self-app-native-pair.py native" in s.get("run", ""))
        self.assertEqual(10, native["timeout-minutes"])
        self.assertIn(PROOF.IMAGE, native["run"])
        checkout = next(s for s in steps if s.get("with", {}).get("repository"))
        self.assertEqual(PROOF.KC_COMMIT, checkout["with"]["ref"])
        self.assertFalse(checkout["with"]["persist-credentials"])
        text = "\n".join(s.get("run", "") for s in steps)
        self.assertIn("clean install", text)
        self.assertIn("clean package", text)
        self.assertIn("test-compile -DskipTests", text)
        self.assertIn("doClassCleanups", (ROOT / "scripts/ci/self-app-native-pair.py").read_text())
        self.assertTrue(any(s.get("if") == "always()" and "label=oriso-c-lifecycle-test=true" in s.get("run", "") for s in steps))
