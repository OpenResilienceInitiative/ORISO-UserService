"""Evidence controls: absent or wrong native proof cannot become acceptance."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SOURCE = Path(__file__).resolve().parents[2] / "scripts/ci/protected-otp-native-pair.py"
spec = importlib.util.spec_from_file_location("protected_pair", SOURCE)
pair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pair)

class ProtectedPairEvidence(unittest.TestCase):
    def proof(self, tags=(), name=pair.CASE):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = ET.Element("testsuite", name=pair.SUITE)
            row = ET.SubElement(report, "testcase", classname=pair.SUITE, name=name)
            for tag in tags:
                ET.SubElement(row, tag).text = "Sensitive diagnostic must not escape"
            ET.ElementTree(report).write(root / "TEST-pair.xml")
            result = pair.sanitize(root, root / "sanitized.xml")
            self.assertNotIn("Sensitive diagnostic", (root / "sanitized.xml").read_text())
            return result

    def test_exact_one_actual_method_is_required(self):
        self.assertTrue(self.proof()["accepted"])
        self.assertFalse(self.proof(name="unrelated")["accepted"])

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
        workflow = (root / ".github/workflows/scenario-c-protected-otp-native-pair.yml").read_text()
        tree = yaml.safe_load(workflow)
        self.assertEqual({"contents": "read"}, tree["permissions"])
        self.assertIn(pair.KC_COMMIT, workflow)
        self.assertIn(pair.IMAGE, workflow)
        self.assertIn("[app, email]", workflow)
        self.assertIn("-DfailIfNoTests=true", SOURCE.read_text())
        self.assertIn("-Dsurefire.failIfNoSpecifiedTests=true", SOURCE.read_text())
        self.assertIn("capture_output=True, timeout=180", SOURCE.read_text())
        self.assertNotIn("continue-on-error", workflow)
        self.assertIn("PROTECTED_PROOF_RESOURCE_PROFILE: github-ubuntu-24-amd64-v1", workflow)
        import fnmatch
        self.assertTrue(fnmatch.fnmatch("protected-otp-native-pair.json", "protected-otp-native-*.json"))
        self.assertIn("target/protected-otp-native/protected-otp-native-pair.json", SOURCE.read_text())
        self.assertIn("target/protected-otp-native/protected-otp-native-*.json", workflow)

if __name__ == "__main__":
    unittest.main()
