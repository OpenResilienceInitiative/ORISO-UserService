import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    "maintenance_boundary_proof",
    Path(__file__).resolve().parents[2] / "scripts/ci/maintenance-boundary-proof.py",
)
PROOF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROOF)


class MaintenanceBoundaryEvidenceTest(unittest.TestCase):
    def reports(self, root, failure=None, error=None, skip=None, legacy_failure=False):
        suite = ET.Element("testsuite", name=PROOF.SUITE)
        for name in PROOF.CASES:
            case = ET.SubElement(suite, "testcase", classname=PROOF.SUITE, name=name)
            if name == failure or legacy_failure and name == PROOF.LEGACY:
                child = ET.SubElement(
                    case,
                    "failure",
                    type="org.opentest4j.AssertionFailedError",
                    message="password and token must be scrubbed",
                )
                child.text = "secret in native traceback"
            if name == error:
                ET.SubElement(case, "error", type="java.lang.NullPointerException")
            if name == skip:
                ET.SubElement(case, "skipped")
        ET.SubElement(suite, "properties").text = "raw-token"
        ET.SubElement(suite, "system-out").text = "raw-password"
        ET.ElementTree(suite).write(root / "TEST-boundary.xml")

    def run_case(self, **settings):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.reports(root, **settings)
            result = PROOF.sanitize(root, root / "clean.xml")
            return result, (root / "clean.xml").read_text()

    def test_exact_nine_green_cases_pass(self):
        result, _ = self.run_case()
        self.assertTrue(result["all_passed"])
        self.assertEqual(9, result["tests"])

    def test_rollback_assertion_requires_both_legacy_cleanup_controls(self):
        result, _ = self.run_case(failure="fiveActorsRollbackPropagatesScopedCommandDemandBeforeNativeCleanup")
        self.assertTrue(result["adapter_behavior_red"])
        for control in PROOF.CONTROLS:
            result, _ = self.run_case(failure=control)
            self.assertFalse(result["adapter_behavior_red"] or result["all_passed"])

    def test_missing_reports_are_not_red_or_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = PROOF.sanitize(Path(tmp), Path(tmp) / "clean.xml")
            self.assertFalse(result["all_passed"] or result["adapter_behavior_red"])

    def test_skips_refuse_acceptance_and_red(self):
        result, _ = self.run_case(skip="fiveActorsRequiresTheScopedAdapterBeforeRead")
        self.assertFalse(result["all_passed"] or result["adapter_behavior_red"])

    def test_red_requires_passing_legacy_control(self):
        settings = {"failure": "fiveActorsRequiresTheScopedAdapterBeforeRead"}
        result, _ = self.run_case(**settings)
        self.assertTrue(result["adapter_behavior_red"])
        result, _ = self.run_case(**settings, legacy_failure=True)
        self.assertFalse(result["adapter_behavior_red"])

    def test_fixture_errors_cannot_be_red(self):
        result, _ = self.run_case(
            failure="fiveActorsRequiresTheScopedAdapterBeforeRead",
            error="fiveActorsRequiresTheScopedAdapterBeforeDeletion",
        )
        self.assertFalse(result["adapter_behavior_red"])

    def test_diagnostics_are_removed_without_losing_methods(self):
        result, xml = self.run_case(
            failure="fiveActorsRequiresTheScopedAdapterBeforeRead"
        )
        self.assertEqual(9, result["tests"])
        for secret in ["password", "token", "traceback", "raw-"]:
            self.assertNotIn(secret, xml)
        self.assertEqual(9, len(ET.fromstring(xml).findall("testcase")))


class DeletionBoundaryEvidenceTest(unittest.TestCase):
    def proof(self):
        proof = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(proof)
        proof.select_slice("deletion")
        return proof

    def test_actual_ten_method_deletion_inventory_qualifies_only_assertion_red(self):
        proof = self.proof()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            suite = ET.Element(
                "testsuite",
                name=proof.SUITE,
                tests="10",
                failures="1",
                errors="0",
                skipped="0",
            )
            ET.SubElement(
                suite,
                "testcase",
                classname=proof.SUITE,
                name="legacyModeRetainsItsExistingDeletionWorkflow",
            )
            case = ET.SubElement(
                suite,
                "testcase",
                classname=proof.SUITE,
                name="fiveActorsMissingScopedDeletionStopsBeforeOwnedDownstreamCleanup",
            )
            ET.SubElement(
                case,
                "failure",
                type="org.mockito.exceptions.verification.NoInteractionsWanted",
            )
            ET.SubElement(
                suite,
                "testcase",
                classname=proof.SUITE,
                name="publicSchedulerFreezesOwnedDeletionBeforeRpcAndAcknowledgesOnlyVerifiedReceipt",
            )
            for name in [
                "publicSchedulerRejectsInvalidReceiptAndRetainsTheExactFrozenIntent",
                "publicSchedulerLosingLeaseBeforeFreezeCannotSendTheNativeDelete",
                "publicSchedulerNewOwnerReplaysFrozenDeleteAfterLostReplyAndAckNeverCallsNativeAgain",
                "fiveActorsMissingScopedConsultantDeletionStopsBeforeOwnedDownstreamCleanup",
                "legacyModeRetainsItsExistingConsultantDeletionWorkflow",
                "fiveActorsMissingScopedSchedulerDelegateRefusesActualStartup",
                "legacyMissingScopedSchedulerDelegateKeepsActualStartup",
            ]:
                ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
            ET.ElementTree(suite).write(root / "TEST-deletion.xml")
            result = proof.sanitize(root, root / "clean.xml")
            self.assertEqual(10, result["floor"])
            self.assertTrue(result["adapter_behavior_red"])
            self.assertFalse(result["all_passed"])

    def test_consultant_and_scheduler_legacy_controls_cannot_be_silently_failed(self):
        proof = self.proof()
        self.assertEqual(3, len(proof.CONTROLS))
        for control in proof.CONTROLS:
            with self.subTest(control=control), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                suite = ET.Element("testsuite", name=proof.SUITE)
                for name in proof.CASES:
                    row = ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
                    if name == control:
                        ET.SubElement(row, "failure", type="org.opentest4j.AssertionFailedError")
                ET.ElementTree(suite).write(root / "TEST-deletion.xml")
                result = proof.sanitize(root, root / "clean.xml")
                self.assertFalse(result["adapter_behavior_red"] or result["all_passed"])

    def test_old_adapter_inventory_cannot_qualify_deletion(self):
        proof = self.proof()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            MaintenanceBoundaryEvidenceTest().reports(root)
            result = proof.sanitize(root, root / "clean.xml")
            self.assertFalse(result["adapter_behavior_red"] or result["all_passed"])


class LocaleBoundaryEvidenceTest(unittest.TestCase):
    def proof(self):
        proof = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(proof)
        proof.select_slice("locale")
        return proof

    def result(self, failed_controls=()):
        proof = self.proof()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            suite = ET.Element("testsuite", name=proof.SUITE)
            for name in proof.CASES:
                case = ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
                if name not in proof.CONTROLS or name in failed_controls:
                    ET.SubElement(case, "failure", type="org.opentest4j.AssertionFailedError")
            ET.ElementTree(suite).write(root / "TEST-locale.xml")
            return proof.sanitize(root, root / "clean.xml")

    def test_nine_public_cases_require_both_domain_and_legacy_controls(self):
        result = self.result()
        self.assertEqual(9, result["floor"])
        self.assertTrue(result["adapter_behavior_red"])
        self.assertTrue(result["all_required_controls_passed"])
        self.assertEqual(7, result["failures"])

    def test_failing_domain_only_control_cannot_be_locale_red(self):
        result = self.result({"domainOnlyPublicPatchKeepsItsWorkingNoHeaderContract"})
        self.assertTrue(result["legacy_control_passed"])
        self.assertFalse(result["adapter_behavior_red"])

    def test_missing_locale_reports_never_qualify(self):
        proof = self.proof()
        with tempfile.TemporaryDirectory() as tmp:
            result = proof.sanitize(Path(tmp), Path(tmp) / "clean.xml")
            self.assertFalse(result["adapter_behavior_red"] or result["all_passed"])


class JpaBoundaryEvidenceTest(unittest.TestCase):
    def test_actual_commit_control_is_required_before_rollback_assertion_red(self):
        proof = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(proof)
        proof.select_slice("jpa")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            suite = ET.Element("testsuite", name=proof.SUITE)
            control = None
            for name in proof.CASES:
                case = ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
                if name in proof.CONTROLS:
                    control = case
                else:
                    ET.SubElement(case, "failure", type="org.opentest4j.AssertionFailedError")
            path = root / "TEST-jpa.xml"
            ET.ElementTree(suite).write(path)
            result = proof.sanitize(root, root / "clean.xml")
            self.assertEqual(2, result["floor"])
            self.assertTrue(result["adapter_behavior_red"])
            self.assertFalse(result["legacy_control_passed"])
            ET.SubElement(control, "failure", type="org.opentest4j.AssertionFailedError")
            ET.ElementTree(suite).write(path)
            self.assertFalse(proof.sanitize(root, root / "clean.xml")["adapter_behavior_red"])


class AvailabilityBoundaryEvidenceTest(unittest.TestCase):
    def test_both_legacy_and_default_off_controls_cover_the_ten_public_cases(self):
        proof = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(proof)
        proof.select_slice("availability")
        self.assertEqual(10, len(proof.CASES))
        self.assertEqual(3, len(proof.CONTROLS))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            suite = ET.Element("testsuite", name=proof.SUITE)
            for name in proof.CASES:
                row = ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
                if name not in proof.CONTROLS:
                    ET.SubElement(row, "failure", type="org.opentest4j.AssertionFailedError")
            path = root / "TEST-availability.xml"
            ET.ElementTree(suite).write(path)
            result = proof.sanitize(root, root / "clean.xml")
            self.assertEqual(7, result["failures"])
            self.assertTrue(result["adapter_behavior_red"])
            row = next(x for x in suite.findall("testcase") if x.get("name") == "legacyUnknownAvailabilityKeepsItsExistingHintResponse")
            ET.SubElement(row, "failure", type="org.opentest4j.AssertionFailedError")
            ET.ElementTree(suite).write(path)
            self.assertFalse(proof.sanitize(root, root / "clean.xml")["adapter_behavior_red"])


class SelfAppBoundaryEvidenceTest(unittest.TestCase):
    def proof(self):
        proof = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(proof)
        proof.select_slice("app")
        return proof

    def test_six_public_assertions_require_actual_missing_service_and_legacy_control(self):
        proof = self.proof()
        self.assertEqual(11, len(proof.CASES))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            suite = ET.Element("testsuite", name=proof.SUITE)
            for name in proof.CASES:
                row = ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
                if name not in proof.CONTROLS:
                    ET.SubElement(row, "failure", type="org.opentest4j.AssertionFailedError")
                else:
                    control = row
            path = root / "TEST-app.xml"
            ET.ElementTree(suite).write(path)
            result = proof.sanitize(root, root / "clean.xml")
            self.assertEqual(9, result["failures"])
            self.assertTrue(result["adapter_behavior_red"])
            self.assertFalse(result["all_passed"])
            ET.SubElement(control, "failure", type="org.opentest4j.AssertionFailedError")
            ET.ElementTree(suite).write(path)
            self.assertFalse(proof.sanitize(root, root / "clean.xml")["adapter_behavior_red"])

    def test_missing_skipped_or_fixture_error_app_methods_never_qualify(self):
        proof = self.proof()
        for kind in ("missing", "skipped", "error"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                suite = ET.Element("testsuite", name=proof.SUITE)
                names = sorted(proof.CASES - proof.CONTROLS)
                for name in proof.CASES:
                    if kind == "missing" and name == names[0]:
                        continue
                    row = ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
                    if name == names[0]:
                        ET.SubElement(row, kind, type="java.lang.IllegalStateException")
                    elif name == names[1]:
                        ET.SubElement(row, "failure", type="org.opentest4j.AssertionFailedError")
                ET.ElementTree(suite).write(root / "TEST-app.xml")
                result = proof.sanitize(root, root / "clean.xml")
                self.assertFalse(result["all_passed"] or result["adapter_behavior_red"])


class SelfAppJpaEvidenceTest(unittest.TestCase):
    def test_actual_jpa_four_cases_require_known_success_control_and_no_skips(self):
        proof = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(proof)
        proof.select_slice("app-jpa")
        self.assertEqual(4, len(proof.CASES))
        for kind in ("green", "failure", "skipped", "error", "missing", "control-failure"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                suite = ET.Element("testsuite", name=proof.SUITE)
                target = sorted(proof.CASES - proof.CONTROLS)[0]
                for name in proof.CASES:
                    if kind == "missing" and name == target:
                        continue
                    row = ET.SubElement(suite, "testcase", classname=proof.SUITE, name=name)
                    if name == target and kind in {"failure", "skipped", "error"}:
                        ET.SubElement(row, kind, type="org.opentest4j.AssertionFailedError" if kind == "failure" else "java.lang.IllegalStateException")
                    if kind == "control-failure" and name in proof.CONTROLS:
                        ET.SubElement(row, "failure", type="org.opentest4j.AssertionFailedError")
                ET.ElementTree(suite).write(root / "TEST-app-jpa.xml")
                result = proof.sanitize(root, root / "clean.xml")
                self.assertEqual(kind == "green", result["all_passed"])
                self.assertEqual(kind == "failure", result["adapter_behavior_red"])
