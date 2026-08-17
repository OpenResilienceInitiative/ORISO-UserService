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
    def test_required_runner_rejects_skipped_tests_and_counts_only_executed_tests(self):
        runner = ROOT / "scripts/ci/run-required-integration-tests.sh"
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            fake_maven = temp_root / "mvnw"
            fake_maven.write_text(
                "#!/usr/bin/env python3\n"
                "from pathlib import Path\n"
                "reports = Path('target/surefire-reports')\n"
                "reports.mkdir(parents=True)\n"
                "required = [\n"
                "    'AppointmentControllerE2EIT',\n"
                "    'ConversationControllerAuthorizationIT',\n"
                "    'ConversationControllerIT',\n"
                "    'UserAdminControllerE2EIT',\n"
                "    'UserControllerE2EIT',\n"
                "]\n"
                "classes = required + [f'Integration{i}IT' for i in range(70)]\n"
                "for index, name in enumerate(classes):\n"
                "    tests = 756 if index == 0 else 1\n"
                "    skipped = 4 if index == 0 else 0\n"
                "    (reports / f'TEST-{name}.xml').write_text(\n"
                "        f'<testsuite name=\"{name}\" tests=\"{tests}\" failures=\"0\" '\n"
                "        f'errors=\"0\" skipped=\"{skipped}\" />'\n"
                "    )\n"
            )
            fake_maven.chmod(0o755)
            env = os.environ.copy()
            env["ORISO_MAVEN_WRAPPER"] = str(fake_maven)

            result = subprocess.run(
                [runner],
                cwd=temp_root,
                env=env,
                check=False,
                capture_output=True,
                text=True,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("tests=830", result.stdout)
        self.assertIn("executed=826", result.stdout)
        self.assertIn("skipped=4", result.stdout)
        self.assertIn(
            "Expected at least 830 executed integration tests, found 826.", result.stderr
        )
        self.assertIn("Integration reports contain 4 skipped tests.", result.stderr)

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

    def test_required_runner_does_not_discover_mariadb_owned_tests(self):
        runner = ROOT / "scripts/ci/run-required-integration-tests.sh"
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            arguments_file = temp_root / "arguments"
            fake_maven = temp_root / "mvnw"
            fake_maven.write_text(
                "#!/usr/bin/env python3\n"
                "import os\n"
                "from pathlib import Path\n"
                "import sys\n"
                "Path(os.environ['MAVEN_ARGUMENTS_FILE']).write_text('\\n'.join(sys.argv[1:]))\n"
                "raise SystemExit(23)\n"
            )
            fake_maven.chmod(0o755)
            env = os.environ.copy()
            env["ORISO_MAVEN_WRAPPER"] = str(fake_maven)
            env["MAVEN_ARGUMENTS_FILE"] = str(arguments_file)

            result = subprocess.run([runner], cwd=temp_root, env=env, check=False)

            arguments = arguments_file.read_text()

        self.assertEqual(23, result.returncode)
        for mariadb_owned_test in (
            "DatabaseChangelogDriftIT",
            "AdminStatisticsRepositoryMariaDbIT",
            "ProvisioningCompensationMariaDbIT",
            "ScheduledTaskClaimMariaDbIT",
            "TutorialProgressServiceMariaDbReplicaIT",
            "OrganizerMariaDbReplicaIT",
            "DeactivateGroupChatSchedulerMariaDbReplicaIT",
            "DeleteUserAccountSchedulerMariaDbReplicaIT",
            "DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT",
            "SupportRoomMigrationConvergenceIT",
        ):
            self.assertIn(f"!{mariadb_owned_test}", arguments)

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

    def test_publish_waits_for_required_integration_tests(self):
        workflow = (ROOT / ".github/workflows/ci-main.yml").read_text()
        publish = job_block(workflow, "publish")
        integration = job_block(workflow, "required-integration-tests")

        self.assertIn("needs: [required-integration-tests, mariadb-contract]", publish)
        self.assertIn("name: required integration tests", integration)
        self.assertNotIn("continue-on-error:", integration)

    def test_real_mariadb_contract_is_required_on_every_workflow(self):
        reusable = (ROOT / ".github/workflows/mariadb-contract.yml").read_text()
        self.assertIn("workflow_call:", reusable)
        self.assertIn("image: mariadb:10.11", reusable)
        self.assertIn("LIQUIBASE_IT_DB_URL", reusable)
        self.assertIn("DatabaseChangelogDriftIT", reusable)
        self.assertIn("AdminStatisticsRepositoryMariaDbIT", reusable)
        self.assertIn("ProvisioningCompensationMariaDbIT", reusable)
        self.assertIn("ScheduledTaskClaimMariaDbIT", reusable)
        self.assertIn("TutorialProgressServiceMariaDbReplicaIT", reusable)
        self.assertIn("OrganizerMariaDbReplicaIT", reusable)
        self.assertIn("DeactivateGroupChatSchedulerMariaDbReplicaIT", reusable)
        self.assertIn("DeleteUserAccountSchedulerMariaDbReplicaIT", reusable)
        self.assertIn("DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT", reusable)
        self.assertNotIn("continue-on-error:", reusable)

        for relative_path in (
            ".github/workflows/ci-pull-request.yml",
            ".github/workflows/ci-feature-branch.yml",
            ".github/workflows/ci-main.yml",
        ):
            workflow = (ROOT / relative_path).read_text()
            mariadb = job_block(workflow, "mariadb-contract")
            self.assertIn("uses: ./.github/workflows/mariadb-contract.yml", mariadb)

    def test_required_integration_jobs_execute_redis_without_discovering_mariadb_tests(self):
        for relative_path in (
            ".github/workflows/ci-pull-request.yml",
            ".github/workflows/ci-feature-branch.yml",
            ".github/workflows/ci-main.yml",
        ):
            workflow = (ROOT / relative_path).read_text()
            integration = job_block(workflow, "required-integration-tests")
            self.assertIn(
                "services:\n      redis:\n        image: redis:7-alpine",
                integration,
                f"{relative_path} must provide Redis to the full integration suite",
            )
            self.assertIn(
                '--health-cmd "redis-cli ping"',
                integration,
                f"{relative_path} must wait for Redis readiness",
            )
            self.assertIn(
                "ORISO_LOCAL_REDIS_IT: true",
                integration,
                f"{relative_path} must execute Redis-gated integration tests",
            )
            self.assertNotIn("LIQUIBASE_IT_DB_URL", integration)
            self.assertNotIn("mariadb:", integration)

    def test_full_integration_suite_is_required_without_quarantine(self):
        runner = (ROOT / "scripts/ci/run-required-integration-tests.sh").read_text()
        self.assertIn("-Dskip.unit-tests=true", runner)
        self.assertIn('"-Dtest=${required_test_pattern}" clean integration-test', runner)
        minimum_reports = re.search(
            r"^minimum_reports = (?P<value>\d+)$", runner, re.MULTILINE
        )
        minimum_tests = re.search(
            r"^minimum_tests = (?P<value>\d+)$", runner, re.MULTILINE
        )
        self.assertIsNotNone(minimum_reports)
        self.assertIsNotNone(minimum_tests)
        self.assertGreaterEqual(int(minimum_reports.group("value")), 75)
        self.assertGreaterEqual(int(minimum_tests.group("value")), 830)
        self.assertIn("if len(reports) < minimum_reports:", runner)
        self.assertIn("executed = tests - skipped", runner)
        self.assertIn("if executed < minimum_tests:", runner)
        self.assertIn("if skipped:", runner)

        for required_e2e in (
            "AppointmentControllerE2EIT",
            "ConversationControllerAuthorizationIT",
            "ConversationControllerIT",
            "UserAdminControllerE2EIT",
            "UserControllerE2EIT",
        ):
            self.assertIn(f'"{required_e2e}"', runner)

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
