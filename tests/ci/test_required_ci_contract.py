import os
import copy
import yaml
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


def assert_native_ownership(excluded, mariadb_workflow, caller_workflow):
    """Every H2 exclusion retains an actual required native owner."""
    owners = set()
    for match in re.finditer(r"-Dtest=([^\s]+)", mariadb_workflow):
        owners.update(match.group(1).split(","))
    document = yaml.safe_load(caller_workflow)
    events = document.get("on", document.get(True, {}))
    if events.get("pull_request") != {"branches": ["dev"]}:
        raise AssertionError("Dedicated native owner must run for PRs to dev")
    jobs = document.get("jobs", {})
    native = jobs.get("native-fairness", {})
    if not native or native.get("needs") != "caller-proof" or "caller-proof" not in jobs:
        raise AssertionError("Missing required native fairness gate")
    if (
        "if" in native or "if" in jobs["caller-proof"]
        or native.get("continue-on-error") or jobs["caller-proof"].get("continue-on-error")
    ):
        raise AssertionError("Native owner cannot be optional or suppressed")
    expected_engines = [
        {"version": "11.0.6", "image": "mariadb@sha256:082554e82eb0e333cddd152d474bb55b8155c8f6585d4c5b0fb47ce2f740cb08"},
        {"version": "11.4.13", "image": "mariadb@sha256:33a6653ab666774f48818120045f9367a81cd4c54c027412fe294a7d1274e2dc"},
    ]
    if (
        native.get("strategy", {}).get("matrix", {}).get("include", []) != expected_engines
        or native.get("strategy", {}).get("fail-fast") is not False
        or native.get("services", {}).get("mariadb", {}).get("image") != "${{ matrix.image }}"
    ):
        raise AssertionError("Both exact pinned Maria engines are required")
    selector = "-Dtest=de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleRetryFairnessMariaDbIT"
    steps = native.get("steps", [])
    critical = [step for step in steps if selector in step.get("run", "")]
    if (
        len(critical) != 1 or "if" in critical[0]
        or any(step.get("continue-on-error") for step in steps)
    ):
        raise AssertionError("Native owner execution cannot be optional or suppressed")
    source = "\n".join(step.get("run", "") for step in steps)
    required = [
        selector, "names == expected",
        "and totals == {'tests': 9, 'failures': 0, 'errors': 0, 'skipped': 0}",
        "status == 0 and before == after", "source-before.json", "source-after.json",
        "if not accepted:", "raise SystemExit(status or 1)",
    ]
    if not all(fragment in source for fragment in required):
        raise AssertionError("Native owner must enforce exact cases, no skips and source binding")
    owners.add("AccountLifecycleRetryFairnessMariaDbIT")
    if excluded - owners:
        raise AssertionError("H2 exclusions without an actual native owner: " + str(sorted(excluded - owners)))


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
            "OrganizerMariaDbReplicaIT",
            "DeactivateGroupChatSchedulerMariaDbReplicaIT",
            "DeleteUserAccountSchedulerMariaDbReplicaIT",
            "DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT",
            "SupportRoomMigrationConvergenceIT",
            "GroupChatMatrixCleanupAdoptionMariaDbIT",
        ):
            self.assertIn(f"!{mariadb_owned_test}", arguments)
        self.assertIn("-Dtest=**/*IT,", arguments)
        self.assertNotIn("!GroupChatMatrixCleanupAdoptionIT", arguments)

    def test_every_test_excluded_from_the_required_runner_is_run_by_the_mariadb_job(self):
        """No test may fall between the two required jobs.

        The required runner excludes real-MariaDB tests from discovery so that JUnit's
        environment conditions cannot manufacture green skips. That is only honest while the
        mariadb-contract job actually runs every excluded test. Asserting the exclusion list
        alone cannot see the gap: a name added to the exclusions and to nothing else silently
        removes the test from required CI, which is how SupportRoomMigrationConvergenceIT was
        lost.
        """
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

            subprocess.run([runner], cwd=temp_root, env=env, check=False)

            arguments = arguments_file.read_text()

        excluded = {
            selector[1:]
            for line in arguments.splitlines()
            if line.startswith("-Dtest=")
            for selector in line[len("-Dtest=") :].split(",")
            if selector.startswith("!")
        }
        self.assertTrue(excluded, "the runner must exclude the real-MariaDB tests")

        mariadb_workflow = (ROOT / ".github/workflows/mariadb-contract.yml").read_text()
        mariadb_selectors = set()
        for match in re.finditer(r"-Dtest=([^\s]+)", mariadb_workflow):
            mariadb_selectors.update(match.group(1).split(","))

        self.assertIn("GroupChatMatrixCleanupAdoptionMariaDbIT", mariadb_selectors)
        caller_workflow = (ROOT / ".github/workflows/scenario-c-caller-proof.yml").read_text()
        assert_native_ownership(excluded, mariadb_workflow, caller_workflow)

    def test_fairness_owner_requires_the_actual_pr_trigger(self):
        self.assert_invalid_fairness_owner(
            lambda document: document.get("on", document.get(True)).pop("pull_request", None)
        )

    def test_fairness_owner_requires_both_pinned_engines(self):
        self.assert_invalid_fairness_owner(
            lambda document: document["jobs"]["native-fairness"]["strategy"]["matrix"]["include"].pop()
        )

    def test_fairness_owner_requires_the_exact_nine_case_gate(self):
        self.assert_invalid_fairness_owner(
            lambda document: self.replace_native_source(document, "'tests': 9", "'tests': 8")
        )

    def test_fairness_owner_cannot_be_a_missing_native_job(self):
        self.assert_invalid_fairness_owner(
            lambda document: document["jobs"].pop("native-fairness")
        )

    def test_any_other_unassigned_suite_still_rejects_the_registry(self):
        caller = (ROOT / ".github/workflows/scenario-c-caller-proof.yml").read_text()
        mariadb = (ROOT / ".github/workflows/mariadb-contract.yml").read_text()
        with self.assertRaises(AssertionError):
            assert_native_ownership({"UnassignedMariaDbIT"}, mariadb, caller)

    def assert_invalid_fairness_owner(self, mutate):
        document = copy.deepcopy(yaml.safe_load((ROOT / ".github/workflows/scenario-c-caller-proof.yml").read_text()))
        mutate(document)
        with self.assertRaises(AssertionError):
            assert_native_ownership({"AccountLifecycleRetryFairnessMariaDbIT"}, "", yaml.safe_dump(document))

    def replace_native_source(self, document, before, after):
        for step in document["jobs"]["native-fairness"]["steps"]:
            if "run" in step:
                step["run"] = step["run"].replace(before, after)

    def test_pull_request_has_one_truthful_required_conclusion(self):
        workflow = (ROOT / ".github/workflows/ci-pull-request.yml").read_text()
        integration = job_block(workflow, "required-integration-tests")
        aggregate = job_block(workflow, "required-ci")

        self.assertIn("name: required integration tests", integration)
        self.assertNotIn("continue-on-error:", integration)
        self.assertIn(
            "needs: [validate, redis-contract, mariadb-contract, required-integration-tests,"
            " tenant-filter-on-integration-tests]",
            aggregate,
        )
        self.assertIn("if: always()", aggregate)
        self.assertIn("name: required PreDev CI", aggregate)
        self.assertIn("needs.required-integration-tests.result", aggregate)
        self.assertIn("needs.mariadb-contract.result", aggregate)
        self.assertIn("needs.tenant-filter-on-integration-tests.result", aggregate)

    def test_publish_waits_for_required_integration_tests(self):
        workflow = (ROOT / ".github/workflows/ci-main.yml").read_text()
        publish = job_block(workflow, "publish")
        integration = job_block(workflow, "required-integration-tests")

        self.assertIn("needs: [build, required-integration-tests, mariadb-contract]", publish)
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
