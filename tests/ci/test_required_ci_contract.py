import json
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
                "    'ConversationControllerAuthorizationIT',\n"
                "    'ConversationControllerIT',\n"
                "    'UserAdminControllerE2EIT',\n"
                "    'UserControllerE2EIT',\n"
                "]\n"
                "classes = required + [f'Integration{i}IT' for i in range(71)]\n"
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

    def test_targeted_h2_case_requires_fresh_passing_evidence(self):
        runner = ROOT / "scripts/ci/run-required-integration-tests.sh"
        class_name = "AgencyDeletionCascadeAuthorizationIT"
        for mode, expected_step in (
            ("pass", None),
            ("skipped", "integration-report"),
            ("stale", "integration-report"),
            ("other", "integration-report"),
            ("missing", "integration-report"),
            ("empty", "integration-report"),
            ("mismatch", "integration-report"),
        ):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                subprocess.run(["git", "init", "-q", root], check=True)
                subprocess.run(
                    ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid",
                     "commit", "-q", "--allow-empty", "-m", "fixture"],
                    cwd=root, check=True,
                )
                subprocess.run(
                    ["git", "config", "--local", "status.showUntrackedFiles", "no"],
                    cwd=root, check=True,
                )
                source = root / "src/test/java" / f"{class_name}.java"
                source.parent.mkdir(parents=True)
                source.touch()
                maven = root / "mvnw"
                maven.write_text(
                    '#!/usr/bin/env python3\n'
                    'import os, sys\n'
                    'from pathlib import Path\n'
                    f'assert "-Dtest={class_name}" in sys.argv\n'
                    'assert "clean" in sys.argv and "integration-test" in sys.argv\n'
                    f'case = "{class_name}"\n'
                    'mode = os.environ["MOCK_REPORT_MODE"]\n'
                    'if mode == "missing": sys.exit(0)\n'
                    'if mode == "other": case = "UnrelatedIT"\n'
                    'count = int(mode == "skipped")\n'
                    'report = Path("target/surefire-reports") / f"TEST-{case}.xml"\n'
                    'report.parent.mkdir(parents=True)\n'
                    'row_class = "UnrelatedIT" if mode == "mismatch" else case\n'
                    'row = f\'<testcase classname="{row_class}" name="case">\'\n'
                    'if count: row += "<skipped/>"\n'
                    'row += "</testcase>"\n'
                    'if mode == "empty": row = ""\n'
                    'report.write_text(\n'
                    '    f\'<testsuite name="{case}" tests="1" failures="0" errors="0" skipped="{count}">\'\n'
                    '    f"{row}</testsuite>"\n'
                    ')\n'
                    'if mode == "stale": os.utime(report, (1, 1))\n'
                )
                maven.chmod(0o755)
                result = subprocess.run(
                    [runner, "--test", class_name], cwd=root, check=False,
                    env={**os.environ, "ORISO_MAVEN_WRAPPER": str(maven),
                         "MOCK_REPORT_MODE": mode},
                    capture_output=True, text=True,
                )
                self.assertEqual(0 if expected_step is None else 1, result.returncode)
                report = json.loads(result.stdout.splitlines()[-1])
                self.assertEqual(class_name, report["selection"])
                self.assertEqual(expected_step, report["failedStep"])
                self.assertRegex(report["revision"], r"^[0-9a-f]{40}$")
                self.assertIs(report["dirty"], True)

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

            result = subprocess.run([runner], cwd=temp_dir, env=env, check=False)

        self.assertEqual(23, result.returncode)

    def test_required_runner_retries_a_maven_central_403(self):
        runner = ROOT / "scripts/ci/run-required-integration-tests.sh"
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            attempt_file = temp_root / "attempts"
            arguments_file = temp_root / "arguments"
            fake_maven = temp_root / "mvnw"
            fake_maven.write_text(
                "#!/usr/bin/env python3\n"
                "import os\n"
                "import sys\n"
                "from pathlib import Path\n"
                "path = Path(os.environ['MAVEN_ATTEMPT_FILE'])\n"
                "attempt = int(path.read_text()) if path.exists() else 0\n"
                "attempt += 1\n"
                "path.write_text(str(attempt))\n"
                "Path(os.environ['MAVEN_ARGUMENTS_FILE']).write_text('\\n'.join(sys.argv[1:]))\n"
                "if attempt == 1:\n"
                "    print(\n"
                "        'Could not transfer artifact org.apache.maven.plugins:'\n"
                "        'maven-clean-plugin:pom:3.5.0 from/to central '\n"
                "        '(https://repo.maven.apache.org/maven2): status code: 403, '\n"
                "        'reason phrase: Forbidden (403)',\n"
                "        file=sys.stderr,\n"
                "    )\n"
                "    raise SystemExit(1)\n"
                "raise SystemExit(23)\n"
            )
            fake_maven.chmod(0o755)
            env = os.environ.copy()
            env["ORISO_MAVEN_WRAPPER"] = str(fake_maven)
            env["MAVEN_ATTEMPT_FILE"] = str(attempt_file)
            env["MAVEN_ARGUMENTS_FILE"] = str(arguments_file)
            env["ORISO_MAVEN_RESOLVE_RETRY_DELAY_SECONDS"] = "0"

            result = subprocess.run(
                [runner],
                cwd=temp_root,
                env=env,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(23, result.returncode, result.stderr)
            self.assertEqual("2", attempt_file.read_text())
            arguments = arguments_file.read_text()
            self.assertIn("github-maven-settings.xml", arguments)
            self.assertIn("clean", arguments)
            self.assertIn("integration-test", arguments)

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
            "ConsultantPictureDatabaseIT",
            "AdminStatisticsRepositoryMariaDbIT",
            "ProvisioningCompensationMariaDbIT",
            "ScheduledTaskClaimMariaDbIT",
            "TutorialProgressServiceMariaDbReplicaIT",
            "DeactivateGroupChatSchedulerMariaDbReplicaIT",
            "DeleteUserAccountSchedulerMariaDbReplicaIT",
            "DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT",
            "SupportRoomMigrationConvergenceIT",
            "AgencyEnquiryRoomPublicationDatabaseIT",
            "ConsultantAgencyLateJoinerMembershipMariaDbIT",
            "GroupChatMatrixCleanupAdoptionMariaDbIT",
        ):
            self.assertIn(f"!{mariadb_owned_test}", arguments)
        self.assertIn("-Dtest=**/*IT,", arguments)
        self.assertNotIn("!AgencyEnquiryAcceptanceDepartureIT", arguments)
        self.assertNotIn("!GroupChatMatrixCleanupAdoptionIT", arguments)
        self.assertNotIn("!InvitationAppointmentKindMigrationMariaDbIT", arguments)

    def test_mariadb_jobs_consume_the_assignment_with_separate_database_settings(self):
        workflow = (ROOT / ".github/workflows/mariadb-contract.yml").read_text()
        guest, standard = workflow.split("    - name: Verify real MariaDB schema contracts")

        self.assertIn("GUEST_PROMOTION_TEST_DB_URL", guest)
        self.assertIn("CREATE DATABASE migration_guest", guest)
        self.assertIn("python3 scripts/ci/database-test-assignment.py guest", guest)
        self.assertIn("python3 scripts/ci/database-test-assignment.py standard", standard)
        self.assertNotIn("GUEST_PROMOTION_TEST_DB_URL", standard)
        self.assertNotIn("-Dtest=", workflow)

    def test_pull_request_has_one_truthful_required_conclusion(self):
        workflow = (ROOT / ".github/workflows/ci-pull-request.yml").read_text()
        integration = job_block(workflow, "required-integration-tests")
        aggregate = job_block(workflow, "required-ci")

        self.assertIn("name: required integration tests", integration)
        self.assertNotIn("continue-on-error:", integration)
        self.assertIn(
            "needs: [validate, redis-contract, mariadb-contract, required-integration-tests,"
            " tenant-filter-on-integration-tests, notification-smtp-contract]",
            aggregate,
        )
        self.assertIn("if: always()", aggregate)
        self.assertIn("name: required PreDev CI", aggregate)
        self.assertIn("needs.required-integration-tests.result", aggregate)
        self.assertIn("needs.mariadb-contract.result", aggregate)
        self.assertIn("needs.tenant-filter-on-integration-tests.result", aggregate)
        self.assertIn("needs.notification-smtp-contract.result", aggregate)

    def test_required_aggregate_refuses_failed_skipped_and_cancelled_smtp(self):
        workflow = (ROOT / ".github/workflows/ci-pull-request.yml").read_text()
        aggregate = job_block(workflow, "required-ci")
        script = aggregate.split("      run: |\n", 1)[1].split("\n  #", 1)[0]
        script = "\n".join(line[8:] for line in script.splitlines())
        for smtp_result in ("success", "failure", "skipped", "cancelled"):
            with self.subTest(smtp_result=smtp_result):
                env = os.environ.copy()
                for name in ("VALIDATE", "REDIS", "MARIADB", "INTEGRATION", "TENANT_FILTER"):
                    env[name + "_RESULT"] = "success"
                env["SMTP_RESULT"] = smtp_result
                result = subprocess.run(["bash", "-c", script], env=env,
                                        capture_output=True, text=True, check=False)
                self.assertEqual(smtp_result == "success", result.returncode == 0)
                if smtp_result != "success":
                    self.assertIn("smtp=" + smtp_result, result.stdout)

    def test_publish_waits_for_required_integration_tests(self):
        workflow = (ROOT / ".github/workflows/ci-main.yml").read_text()
        publish = job_block(workflow, "publish")
        integration = job_block(workflow, "required-integration-tests")

        self.assertIn(
            "needs: [build, required-integration-tests, mariadb-contract, notification-smtp-contract]",
            publish,
        )
        self.assertIn("name: required integration tests", integration)
        self.assertNotIn("continue-on-error:", integration)

    def test_real_mariadb_contract_is_required_on_every_workflow(self):
        reusable = (ROOT / ".github/workflows/mariadb-contract.yml").read_text()
        self.assertIn("workflow_call:", reusable)
        self.assertIn("image: mariadb:10.11", reusable)
        self.assertIn("LIQUIBASE_IT_DB_URL", reusable)
        assignment = (ROOT / "scripts/ci/mariadb-contract-tests.txt").read_text()
        self.assertIn("standard DatabaseChangelogDriftIT", assignment)
        self.assertIn("standard AdminStatisticsRepositoryMariaDbIT", assignment)
        self.assertIn("standard ProvisioningCompensationMariaDbIT", assignment)
        self.assertIn("standard ScheduledTaskClaimMariaDbIT", assignment)
        self.assertIn("standard TutorialProgressServiceMariaDbReplicaIT", assignment)
        self.assertIn("standard DeactivateGroupChatSchedulerMariaDbReplicaIT", assignment)
        self.assertIn("standard DeleteUserAccountSchedulerMariaDbReplicaIT", assignment)
        self.assertIn("standard DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT", assignment)
        self.assertIn("guest LiveChatPromotionMigrationIT", assignment)
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

    def test_tenant_filter_job_reads_only_and_runs_with_redis_like_the_other_it_jobs(self):
        workflow = (ROOT / ".github/workflows/ci-pull-request.yml").read_text()
        tenant_filter = job_block(workflow, "tenant-filter-on-integration-tests")

        self.assertIn("permissions:\n      contents: read", tenant_filter)
        self.assertIn("services:\n      redis:\n        image: redis:7-alpine", tenant_filter)
        self.assertIn('--health-cmd "redis-cli ping"', tenant_filter)
        self.assertIn("ORISO_LOCAL_REDIS_IT: true", tenant_filter)

    def test_full_integration_suite_is_required_without_quarantine(self):
        runner = (ROOT / "scripts/ci/run-required-integration-tests.sh").read_text()
        settings = ROOT / "scripts/ci/github-maven-settings.xml"
        self.assertTrue(settings.is_file())
        self.assertIn(
            "maven-central.storage-download.googleapis.com/maven2",
            settings.read_text(),
        )
        self.assertIn("github-maven-settings.xml", runner)
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
