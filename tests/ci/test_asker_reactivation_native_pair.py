"""Offline proof/privacy controls only; no JVM, container, token or authority generation."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("asker_pair", ROOT / "scripts/ci/asker-reactivation-native-pair.py")
proof = importlib.util.module_from_spec(spec); spec.loader.exec_module(proof)


class AskerReactivationPairControls(unittest.TestCase):
    def test_closed_witness_requires_every_actual_fact(self):
        complete = {key: True for key in proof.FLAGS}
        self.assertTrue(proof.witness(complete))
        for key in proof.FLAGS:
            self.assertFalse(proof.witness({**complete, key: False}))
        for value in ({}, {**complete, "password": "forbidden"}, {**complete, "actualJpa": 1}):
            with self.assertRaises(ValueError): proof.witness(value)

    def test_only_user_platform_reactivation_is_withdrawn_preserving_every_other_grant(self):
        policy = {"ordinaryProfiles": [{"id": "user-v1", "version": 1}], "adminOperations": [
            {"authority": "PLATFORM", "selfOnly": False, "targetProfiles": ["consultant-v1", "user-v1"], "operations": ["account.read", "account.profile", "account.reactivate"]},
            {"authority": "TENANT_PEER", "selfOnly": False, "targetProfiles": ["user-v1"], "operations": ["account.read", "account.reactivate"]}]}
        original = copy.deepcopy(policy); changed = proof.withdraw_reactivation(policy)
        def reach(value):
            return {(g["authority"], g["selfOnly"], p, op) for g in value["adminOperations"] for p in g["targetProfiles"] for op in g["operations"]}
        self.assertEqual(reach(original) - {("PLATFORM", False, "user-v1", "account.reactivate")}, reach(changed))
        self.assertEqual(original, policy); self.assertEqual(original["ordinaryProfiles"], changed["ordinaryProfiles"])
        with self.assertRaises(ValueError): proof.withdraw_reactivation(changed)

    def test_original_and_later_cycle_effects_have_three_exact_independent_checkpoints(self):
        self.assertTrue(proof.phase_effects([1, 2, 3]))
        for value in [[], [1], [1, 3, 4], [1, 1, 2], [1, 2, 3, 4], [True, 2, 3], [1, 2, 3.0], (1, 2, 3)]:
            self.assertFalse(proof.phase_effects(value))

    def test_exact_one_case_and_zero_skips_are_mandatory(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); path = reports / ("TEST-" + proof.SUITE + ".xml")
            def write(child="", name=proof.CASE): path.write_text('<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + name + '">' + child + '</testcase></testsuite>')
            write(); self.assertTrue(proof.sanitize(reports, reports / "safe.xml")["accepted"])
            for child, name in [("<skipped/>", proof.CASE), ("", "unknown")]:
                write(child, name); self.assertFalse(proof.sanitize(reports, reports / "safe.xml")["accepted"])
            write(); (reports / "TEST-extra.xml").write_text(path.read_text()); self.assertFalse(proof.sanitize(reports, reports / "safe.xml")["accepted"])

    def test_no_sensitive_failure_text_survives_xml_export(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); output = reports / "safe.xml"
            (reports / ("TEST-" + proof.SUITE + ".xml")).write_text('<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '"><failure>Bearer SYNTHETIC_JWT password=SYNTHETIC_PASSWORD OTP=654321 AskerReactivationNativePairProof.java:17</failure></testcase></testsuite>')
            result = proof.sanitize(reports, output); self.assertFalse(result["accepted"])
            self.assertEqual(["AskerReactivationNativePairProof.java:17"], result["failure_locations"])
            for value in ["Bearer", "SYNTHETIC_JWT", "SYNTHETIC_PASSWORD", "654321"]: self.assertNotIn(value, output.read_text())

    def test_java_failure_category_is_closed_and_never_exports_type_or_message(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); output = reports / "safe.xml"
            for java_type, expected in [
                ("org.springframework.web.client.HttpClientErrorException$Forbidden", "HTTP_403"),
                ("org.springframework.web.client.HttpClientErrorException$Unauthorized", "HTTP_401"),
                ("org.springframework.http.converter.HttpMessageConversionException", "RESPONSE_CONVERSION_ERROR"),
                ("java.lang.RuntimeException.SYNTHETIC_JWT", "OTHER_JAVA_ERROR")]:
                (reports / ("TEST-" + proof.SUITE + ".xml")).write_text('<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '"><error type="' + java_type + '" message="Bearer SYNTHETIC_JWT password=SYNTHETIC_PASSWORD OTP=654321">Bearer SYNTHETIC_JWT</error></testcase></testsuite>')
                result = proof.sanitize(reports, output)
                self.assertEqual([expected], result["failure_categories"])
                for secret in [java_type, "SYNTHETIC_JWT", "SYNTHETIC_PASSWORD", "654321"]:
                    self.assertNotIn(secret, output.read_text() + json.dumps(result))

    def test_only_repository_known_setup_frames_can_be_exported(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "protected_account_operations.py"; source.write_text('def source_frontier():\n    raise RuntimeError("Bearer SYNTHETIC_JWT password=SYNTHETIC_PASSWORD OTP=654321")\n')
            scope = {}; exec(compile(source.read_text(), str(source), "exec"), scope)
            try: scope["source_frontier"]()
            except RuntimeError as error:
                frames = proof.shared.sanitized_failure_frames(error, proof.shared.source_frame_catalog([source]))
                self.assertEqual([{"file": source.name, "function": "source_frontier", "line": 2}], frames)
                for secret in [folder, "SYNTHETIC_JWT", "SYNTHETIC_PASSWORD", "654321"]: self.assertNotIn(secret, json.dumps(frames))

    def test_workflow_retains_pinned_source_and_existing_resource_runtime_caps(self):
        import yaml
        value = yaml.safe_load((ROOT / ".github/workflows/scenario-c-asker-reactivation-native-pair.yml").read_text())
        job = next(iter(value["jobs"].values())); self.assertEqual(30, job["timeout-minutes"]); self.assertNotIn("strategy", job)
        native = [s for s in job["steps"] if s.get("with", {}).get("repository") == "OpenResilienceInitiative/ORISO-Keycloak"]
        self.assertEqual([proof.KC_COMMIT], [s["with"]["ref"] for s in native]); self.assertEqual("9b575dedb7eefaa509c023042ad534da6d1926cf", proof.KC_COMMIT)
        actual = [s for s in job["steps"] if s.get("timeout-minutes") == 10]; self.assertEqual(1, len(actual)); self.assertEqual("github-ubuntu-24-amd64-v1", actual[0]["env"]["PROTECTED_PROOF_RESOURCE_PROFILE"])
        source = (ROOT / "scripts/ci/asker-reactivation-native-pair.py").read_text()
        for required in ["timeout=180", "OperationFixture.memoryStart()", "require_active(\"before-actual-reactivation-jpa\")", "check.get(\"sameActualEnabledMasterInstallerAndNativeSessionVerified\")", "no diagnostic"]:
            if required != "no diagnostic": self.assertIn(required, source)
        self.assertNotIn("print(child", source); self.assertNotIn("error.__str__", source)


if __name__ == "__main__": unittest.main()
