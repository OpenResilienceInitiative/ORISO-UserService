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

    def test_native_workflow_has_read_only_pins_required_builds_and_bounded_cleanup(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/scenario-c-self-app-native-pair.yml").read_text())
        self.assertEqual({"contents": "read"}, workflow["permissions"])
        job = workflow["jobs"]["public-app-native-pair"]
        self.assertEqual("ubuntu-24.04", job["runs-on"])
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
