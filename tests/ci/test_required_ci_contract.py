import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


def job_block(workflow: str, job_name: str) -> str:
    marker = f"  {job_name}:\n"
    start = workflow.index(marker)
    remainder = workflow[start + len(marker) :]
    next_job = re.search(r"\n  [a-zA-Z0-9_-]+:\n", remainder)
    return remainder if next_job is None else remainder[: next_job.start()]


class RequiredCiContractTest(unittest.TestCase):
    def test_integration_tests_preserve_the_configured_test_database(self):
        test_root = ROOT / "src/test/java"
        offenders = []

        for source in test_root.rglob("*.java"):
            for line_number, line in enumerate(source.read_text().splitlines(), start=1):
                stripped = line.strip()
                replaces_with_any_database = (
                    stripped
                    == "@AutoConfigureTestDatabase(replace = Replace.ANY)"
                    or stripped
                    == "@AutoConfigureTestDatabase("
                    "replace = AutoConfigureTestDatabase.Replace.ANY)"
                )
                uses_replacing_default = stripped == "@AutoConfigureTestDatabase"
                if replaces_with_any_database or uses_replacing_default:
                    offenders.append(
                        f"{source.relative_to(ROOT)}:{line_number}: {stripped}"
                    )

        self.assertEqual(
            [],
            offenders,
            "Integration tests must retain application-testing.properties "
            "(including H2 MariaDB compatibility settings):\n"
            + "\n".join(offenders),
        )

    def test_required_runner_propagates_maven_failure(self):
        runner = ROOT / "scripts/ci/run-required-integration-tests.sh"
        with tempfile.TemporaryDirectory() as temp_dir:
            fake_maven = Path(temp_dir) / "mvnw"
            fake_maven.write_text("#!/usr/bin/env bash\nexit 23\n")
            fake_maven.chmod(0o755)
            env = os.environ.copy()
            env["ORISO_MAVEN_WRAPPER"] = str(fake_maven)

            result = subprocess.run([runner], cwd=ROOT, env=env, check=False)

        self.assertEqual(23, result.returncode)

    def test_pull_request_has_one_truthful_required_conclusion(self):
        workflow = (ROOT / ".github/workflows/ci-pull-request.yml").read_text()
        integration = job_block(workflow, "required-integration-tests")
        aggregate = job_block(workflow, "required-ci")

        self.assertIn("name: required integration tests", integration)
        self.assertNotIn("continue-on-error:", integration)
        self.assertIn(
            "needs: [validate, redis-contract, mariadb-contract, required-integration-tests]",
            aggregate,
        )
        self.assertIn("if: always()", aggregate)
        self.assertIn("name: required PreDev CI", aggregate)
        self.assertIn("needs.required-integration-tests.result", aggregate)
        self.assertIn("needs.mariadb-contract.result", aggregate)

    def test_real_redis_replica_state_contract_is_required_on_every_workflow(self):
        reusable = (ROOT / ".github/workflows/redis-contract.yml").read_text()
        self.assertIn("workflow_call:", reusable)
        self.assertIn("image: redis:7-alpine", reusable)
        self.assertIn("ConsultantActivityRegistryRedisIT", reusable)
        self.assertIn("ActiveViewRegistryRedisIT", reusable)
        self.assertNotIn("continue-on-error:", reusable)

        for relative_path in (
            ".github/workflows/ci-pull-request.yml",
            ".github/workflows/ci-feature-branch.yml",
            ".github/workflows/ci-main.yml",
        ):
            workflow = (ROOT / relative_path).read_text()
            redis = job_block(workflow, "redis-contract")
            self.assertIn("uses: ./.github/workflows/redis-contract.yml", redis)

    def test_publish_waits_for_required_integration_tests(self):
        workflow = (ROOT / ".github/workflows/ci-main.yml").read_text()
        publish = job_block(workflow, "publish")
        integration = job_block(workflow, "required-integration-tests")

        self.assertIn(
            "needs: [required-integration-tests, mariadb-contract, redis-contract]",
            publish,
        )
        self.assertIn("name: required integration tests", integration)
        self.assertNotIn("continue-on-error:", integration)

    def test_real_mariadb_contract_is_required_on_every_workflow(self):
        reusable = (ROOT / ".github/workflows/mariadb-contract.yml").read_text()
        self.assertIn("workflow_call:", reusable)
        self.assertIn("image: mariadb:10.11", reusable)
        self.assertIn("LIQUIBASE_IT_DB_URL", reusable)
        self.assertIn("DatabaseChangelogDriftIT", reusable)
        self.assertIn("AdminStatisticsRepositoryMariaDbIT", reusable)
        self.assertIn("OrganizerMariaDbReplicaIT", reusable)
        self.assertIn("DeactivateGroupChatSchedulerMariaDbReplicaIT", reusable)
        self.assertIn("ConsultantMessageStatServiceMariaDbReplicaIT", reusable)
        self.assertNotIn("continue-on-error:", reusable)

        for relative_path in (
            ".github/workflows/ci-pull-request.yml",
            ".github/workflows/ci-feature-branch.yml",
            ".github/workflows/ci-main.yml",
        ):
            workflow = (ROOT / relative_path).read_text()
            mariadb = job_block(workflow, "mariadb-contract")
            self.assertIn("uses: ./.github/workflows/mariadb-contract.yml", mariadb)

    def test_full_integration_suite_is_required_without_quarantine(self):
        runner = (ROOT / "scripts/ci/run-required-integration-tests.sh").read_text()
        self.assertIn("-Dskip.unit-tests=true clean integration-test", runner)
        self.assertIn("tests < 900", runner)
        self.assertIn("UserControllerE2EIT", runner)

        for relative_path in (
            ".github/workflows/ci-pull-request.yml",
            ".github/workflows/ci-feature-branch.yml",
            ".github/workflows/ci-main.yml",
        ):
            workflow = (ROOT / relative_path).read_text()
            self.assertNotIn("legacy-integration-quarantine", workflow)
            self.assertNotIn("maven-verify-burnin", workflow)

        self.assertFalse(
            (ROOT / ".github/actions/maven-verify-burnin/action.yml").exists()
        )


if __name__ == "__main__":
    unittest.main()
