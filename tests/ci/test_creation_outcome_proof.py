"""Adversarial receipt controls; these fixtures are not native/JPA execution evidence."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("creation_outcome_proof", ROOT / "scripts/ci/creation-outcome-proof.py")
PROOF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROOF)


class CreationOutcomeProofTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / "report.xml"
        self.frame = {
            "revision": "a" * 40,
            "inputs": {name: "b" * 64 for name in ("pom.xml", "api/useradminservice.yaml", PROOF.JAVA_SOURCE)},
        }
        self.xml = ET.Element("testsuite", name=PROOF.CLASS, tests="8", failures="0", errors="0", skipped="0")
        for method, count in PROOF.EXPECTED.items():
            for index in range(count):
                name = method if count == 1 else method + "(boolean)[" + str(index + 1) + "]"
                ET.SubElement(self.xml, "testcase", classname=PROOF.CLASS, name=name)

    def accepted(self, after=None, status=0):
        ET.ElementTree(self.xml).write(self.path)
        return PROOF.evaluate(self.path, self.frame, self.frame if after is None else after, status)["accepted"]

    def test_complete_distinct_eight_case_control_is_accepted(self):
        self.assertTrue(self.accepted())

    def test_count_only_lookalike_cannot_replace_a_required_native_case(self):
        self.xml.findall("testcase")[-1].set("name", "unrelatedHealthyCase")
        self.assertFalse(self.accepted())

    def test_both_parameter_invocations_are_required(self):
        cases = self.xml.findall("testcase")
        second = next(case for case in cases if case.get("name", "").endswith("[2]"))
        second.set("name", "differentHealthyCase")
        self.assertFalse(self.accepted())

    def test_duplicate_case_identity_is_rejected_even_with_the_right_total(self):
        cases = self.xml.findall("testcase")
        cases[-1].set("name", cases[0].get("name"))
        self.assertFalse(self.accepted())

    def test_nonzero_counts_and_hidden_failure_nodes_are_rejected(self):
        for key in ("failures", "errors", "skipped"):
            with self.subTest(count=key):
                self.xml.set(key, "1")
                self.assertFalse(self.accepted())
                self.xml.set(key, "0")
        failed = ET.SubElement(self.xml.findall("testcase")[0], "failure")
        self.assertFalse(self.accepted())
        self.xml.findall("testcase")[0].remove(failed)

    def test_changed_or_incomplete_source_frames_are_rejected(self):
        changed = copy.deepcopy(self.frame)
        changed["inputs"]["pom.xml"] = "c" * 64
        self.assertFalse(self.accepted(after=changed))
        del self.frame["inputs"][PROOF.JAVA_SOURCE]
        self.assertFalse(self.accepted())

    def test_failed_maven_and_count_lies_are_rejected(self):
        self.assertFalse(self.accepted(status=1))
        self.xml.set("tests", "9")
        self.assertFalse(self.accepted())

    def test_foreign_test_class_cannot_supply_the_eight_case_total(self):
        self.xml.findall("testcase")[0].set("classname", "unrelated.Proof")
        self.assertFalse(self.accepted())

    def test_missing_failure_metadata_cannot_be_assumed_zero(self):
        for key in ("failures", "errors", "skipped"):
            with self.subTest(missing=key):
                value = self.xml.attrib.pop(key)
                try:
                    accepted = self.accepted()
                except (KeyError, ValueError):
                    accepted = False
                self.assertFalse(accepted)
                self.xml.set(key, value)


if __name__ == "__main__":
    unittest.main()

