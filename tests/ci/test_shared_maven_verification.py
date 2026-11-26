import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class SharedMavenVerificationTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.scripts = self.root / "scripts/ci"
        self.scripts.mkdir(parents=True)
        for name in (
            "verify-maven-unit.py",
            "run-maven-with-central-retry.sh",
            "suite-inventory.py",
        ):
            source = ROOT / "scripts/ci" / name
            if source.exists():
                shutil.copyfile(source, self.scripts / name)
        (self.root / ".gitignore").write_text("/target/\n")
        wrapper = self.root / "mvnw"
        wrapper.write_text(
            """#!/usr/bin/env python3
import os
from pathlib import Path
import sys

assert sys.argv[1:] == os.environ["EXPECTED_ARGUMENTS"].split()
mode = os.environ["MAVEN_RESULT"]
if mode == "failed":
    print("Expected failing test")
    sys.exit(7)
if mode in ("green", "skipped"):
    skipped = int(mode == "skipped")
    reports = Path("target/surefire-reports")
    reports.mkdir(parents=True, exist_ok=True)
    (reports / "TEST-SampleTest.xml").write_text(
        f'<testsuite name="SampleTest" tests="1" failures="0" errors="0" skipped="{skipped}"/>'
    )
"""
        )
        wrapper.chmod(0o755)
        for args in (
            ["init", "-q"],
            ["add", "."],
            ["-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "fixture"],
        ):
            subprocess.run(["git", *args], cwd=self.root, check=True)
        self.revision = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.root, text=True
        ).strip()

    def run_check(self, mode, *args):
        summary = self.root / "target/summary"
        summary.parent.mkdir(exist_ok=True)
        arguments = "-B -Dtest=SampleTest test" if args else "-B test"
        result = subprocess.run(
            [sys.executable, self.scripts / "verify-maven-unit.py", *args],
            cwd=self.root,
            env={
                **os.environ,
                "EXPECTED_ARGUMENTS": arguments,
                "MAVEN_RESULT": mode,
                "GITHUB_STEP_SUMMARY": str(summary),
            },
            text=True,
            capture_output=True,
        )
        self.assertTrue(summary.exists(), result.stderr)
        report = json.loads(result.stdout.splitlines()[-1])
        self.assertIn(result.stdout.splitlines()[-1], summary.read_text())
        self.assertEqual(self.revision, report["revision"])
        self.assertGreaterEqual(report["durationSeconds"], 0)
        return result, report

    def test_selected_run_and_hosted_report_introduce_no_extra_maven_execution(self):
        result, report = self.run_check("green", "--test", "SampleTest")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("passed", report["outcome"])
        self.assertEqual("SampleTest", report["selection"])
        self.assertFalse(report["dirty"])
        self.assertIsNone(report["failedStep"])

    def test_maven_failure_keeps_original_exit_status_and_marks_dirty_checkout(self):
        (self.root / "new-source.txt").touch()
        result, report = self.run_check("failed")
        self.assertEqual(7, result.returncode)
        self.assertIn("Expected failing test", result.stdout)
        self.assertEqual("maven-test", report["failedStep"])
        self.assertEqual("all", report["selection"])
        self.assertTrue(report["dirty"])

    def test_stale_green_report_cannot_satisfy_an_empty_run(self):
        reports = self.root / "target/surefire-reports"
        reports.mkdir(parents=True)
        report_file = reports / "TEST-OldTest.xml"
        report_file.write_text('<testsuite name="OldTest" tests="1" skipped="0"/>')
        os.utime(report_file, (1, 1))
        result, report = self.run_check("empty")
        self.assertEqual(1, result.returncode)
        self.assertEqual("unit-test-inventory", report["failedStep"])

    def test_unit_and_integration_runners_refuse_the_same_busy_checkout(self):
        lock_path = self.root / ".git/oriso-maven-verification.lock"
        with lock_path.open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            unit, unit_report = self.run_check("failed", "--test", "SampleTest")
            integration = subprocess.run(
                [ROOT / "scripts/ci/run-required-integration-tests.sh"],
                cwd=self.root,
                env={**os.environ, "ORISO_MAVEN_WRAPPER": str(self.root / "mvnw")},
                text=True,
                capture_output=True,
                check=False,
            )
        self.assertEqual(1, unit.returncode)
        self.assertEqual("checkout-busy", unit_report["failedStep"])
        integration_report = json.loads(integration.stdout.splitlines()[-1])
        self.assertEqual(1, integration.returncode)
        self.assertEqual("checkout-busy", integration_report["failedStep"])

    def test_all_skipped_cases_fail_the_current_run(self):
        result, report = self.run_check("skipped")
        self.assertEqual(1, result.returncode)
        self.assertEqual("unit-test-inventory", report["failedStep"])
