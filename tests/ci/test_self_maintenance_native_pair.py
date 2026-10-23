"""Offline acceptance/privacy controls; never starts a JVM or native fixture."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("self_maintenance_pair", ROOT / "scripts/ci/self-maintenance-native-pair.py")
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class SelfMaintenancePairControls(unittest.TestCase):
    def witness(self, kind):
        return {"kind": kind, **{key: True for key in proof.WITNESS_FLAGS}}

    def test_only_complete_password_or_locale_witness_can_pass(self):
        for kind in ("password", "locale"):
            self.assertTrue(proof.checked_witness(self.witness(kind), kind))
            for flag in proof.WITNESS_FLAGS:
                value = self.witness(kind); value[flag] = False
                self.assertFalse(proof.checked_witness(value, kind))

    def test_unknown_extra_missing_and_nontyped_facts_cannot_be_exported(self):
        for value in ({}, self.witness("other"), {**self.witness("password"), "receipt": "sensitive"},
                      {**self.witness("password"), next(iter(proof.WITNESS_FLAGS)): 1}):
            with self.assertRaises(ValueError): proof.checked_witness(value, "password")

    def test_exact_case_inventory_requires_one_executed_case_and_no_skip(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder)
            method = proof.CASES["password"]
            xml = reports / ("TEST-" + proof.SUITE + ".xml")
            xml.write_text('<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + method + '"/></testsuite>')
            self.assertTrue(proof.sanitize(reports, reports / "safe.xml", "password")["accepted"])
            xml.write_text('<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + method + '"><skipped/></testcase></testsuite>')
            self.assertFalse(proof.sanitize(reports, reports / "safe.xml", "password")["accepted"])

    def test_failure_xml_never_preserves_sensitive_diagnostic_text(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); output = reports / "safe.xml"
            (reports / ("TEST-" + proof.SUITE + ".xml")).write_text(
                '<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASES["locale"] + '"><failure>password Bearer token secret</failure></testcase></testsuite>')
            result = proof.sanitize(reports, output, "locale")
            self.assertFalse(result["accepted"])
            self.assertNotIn("Bearer", output.read_text())
            self.assertNotIn("password", output.read_text())

    def test_pair_policy_grants_exact_read_and_selected_operation_only(self):
        self.assertEqual(["account.read", "account.password"], proof.reviewed_operations("password"))
        self.assertEqual(["account.read", "account.locale"], proof.reviewed_operations("locale"))
        with self.assertRaises(ValueError): proof.reviewed_operations("other")

    def test_workflow_uses_same_exact_common_source_and_two_finite_cases(self):
        import yaml
        workflow = yaml.safe_load((ROOT / ".github/workflows/scenario-c-self-maintenance-native-pair.yml").read_text())
        job = next(iter(workflow["jobs"].values()))
        self.assertEqual(["password", "locale"], job["strategy"]["matrix"]["kind"])
        native = [step for step in job["steps"] if step.get("with", {}).get("repository") == "OpenResilienceInitiative/ORISO-Keycloak"]
        self.assertEqual(1, len(native)); self.assertEqual(proof.KC_COMMIT, native[0]["with"]["ref"])
        self.assertEqual("e1b2baae5c8509f2a28a2de306761a14c5840faa", proof.KC_COMMIT)


if __name__ == "__main__": unittest.main()
