"""The retained native creation proof must gate delivery, not just run optionally."""
import os
from pathlib import Path
import subprocess
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
JOB = "creation-outcome-mariadb"


def document(name):
    return yaml.safe_load((ROOT / ".github/workflows" / name).read_text())


class CreationOutcomeCiContractTest(unittest.TestCase):
    def test_failed_or_skipped_native_creation_proof_cannot_pass_pr_aggregate(self):
        aggregate = document("ci-pull-request.yml")["jobs"]["required-ci"]
        step = aggregate["steps"][0]
        for outcome in ("failure", "skipped", "cancelled"):
            with self.subTest(outcome=outcome):
                env = dict(os.environ)
                env.update({key: "success" for key in step["env"]})
                env["CREATION_OUTCOME_RESULT"] = outcome
                result = subprocess.run(
                    ["bash", "-c", step["run"]],
                    env=env, capture_output=True, text=True, timeout=5,
                )
                self.assertNotEqual(0, result.returncode, "Native creation failure was ignored")

    def test_native_creation_proof_is_called_on_branch_pr_and_main(self):
        for name in ("ci-feature-branch.yml", "ci-pull-request.yml", "ci-main.yml"):
            with self.subTest(workflow=name):
                job = document(name)["jobs"].get(JOB)
                self.assertIsNotNone(job, "Required retained creation proof has no caller")
                self.assertEqual("./.github/workflows/creation-outcome-mariadb.yml", job["uses"])
                self.assertFalse(job.get("continue-on-error", False))
                if name == "ci-feature-branch.yml":
                    self.assertEqual("route-validation", job["needs"])
                    self.assertIn("run_validation", job["if"])
                else:
                    self.assertNotIn("if", job)

    def test_main_publication_and_pr_success_wait_for_native_creation(self):
        self.assertIn(JOB, document("ci-main.yml")["jobs"]["publish"]["needs"])
        aggregate = document("ci-pull-request.yml")["jobs"]["required-ci"]
        self.assertIn(JOB, aggregate["needs"])
        self.assertIn("CREATION_OUTCOME_RESULT", aggregate["steps"][0]["env"])

    def test_h2_exclusion_retains_native_owner_and_required_http_privacy(self):
        script = (ROOT / "scripts/ci/run-required-integration-tests.sh").read_text()
        self.assertIn("  CreationOperationMariaDbIT", script)
        self.assertIn('"ConsultantCreationOperationWebSecurityIT"', script)
        self.assertIn('"ConsultantCreationOperationPrivacyIT"', script)
        workflow = ROOT / ".github/workflows/creation-outcome-mariadb.yml"
        self.assertTrue(workflow.is_file(), "No native proof exists for the excluded suite")


if __name__ == "__main__":
    unittest.main()

