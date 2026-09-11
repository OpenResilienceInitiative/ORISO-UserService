from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
FAILED_TESTS = ROOT / "scripts/ci/failed-tests.py"


def report(class_name: str, cases: str) -> str:
    return (
        f'<?xml version="1.0" encoding="UTF-8"?>'
        f'<testsuite name="{class_name}" tests="1">{cases}</testsuite>'
    )


def case(class_name: str, name: str, outcome: str | None = None, message: str = "") -> str:
    if outcome is None:
        return f'<testcase classname="{class_name}" name="{name}"/>'
    return (
        f'<testcase classname="{class_name}" name="{name}">'
        f'<{outcome} message="{message}" type="java.lang.AssertionError"/>'
        f"</testcase>"
    )


class FailedTestsTest(unittest.TestCase):
    def run_failed_tests(self, reports: dict[str, str] | None):
        with tempfile.TemporaryDirectory() as temp_dir:
            report_directory = Path(temp_dir) / "surefire-reports"
            if reports is not None:
                report_directory.mkdir()
                for file_name, content in reports.items():
                    (report_directory / file_name).write_text(content)

            return subprocess.run(
                [sys.executable, FAILED_TESTS, "--report-directory", report_directory],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_names_a_failing_test_with_its_message(self):
        result = self.run_failed_tests(
            {
                "TEST-de.example.FooTest.xml": report(
                    "de.example.FooTest",
                    case("de.example.FooTest", "rejectsBlankName", "failure", "expected 1 but was 2"),
                )
            }
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("1 failing test.", result.stdout)
        self.assertIn("`FooTest.rejectsBlankName`", result.stdout)
        self.assertIn("expected 1 but was 2", result.stdout)
        self.assertIn("de.example.FooTest#rejectsBlankName", result.stdout)

    def test_distinguishes_an_error_from_a_failure(self):
        result = self.run_failed_tests(
            {
                "TEST-de.example.FooTest.xml": report(
                    "de.example.FooTest",
                    case("de.example.FooTest", "boom", "error", "NullPointerException"),
                )
            }
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("| error |", result.stdout)

    def test_ignores_passing_tests(self):
        result = self.run_failed_tests(
            {
                "TEST-de.example.FooTest.xml": report(
                    "de.example.FooTest",
                    case("de.example.FooTest", "passes")
                    + case("de.example.FooTest", "fails", "failure", "nope"),
                )
            }
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("1 failing test.", result.stdout)
        self.assertNotIn("FooTest.passes", result.stdout)

    def test_reports_a_clean_run(self):
        result = self.run_failed_tests(
            {
                "TEST-de.example.FooTest.xml": report(
                    "de.example.FooTest", case("de.example.FooTest", "passes")
                )
            }
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("No failing tests", result.stdout)

    def test_escapes_a_pipe_so_it_cannot_break_the_table(self):
        result = self.run_failed_tests(
            {
                "TEST-de.example.FooTest.xml": report(
                    "de.example.FooTest",
                    case("de.example.FooTest", "parses", "failure", "expected a|b but got c"),
                )
            }
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("expected a\\|b but got c", result.stdout)

    def test_reports_zero_exit_even_with_failures(self):
        """suite-inventory.py --require owns the job outcome; this section is reporting."""
        result = self.run_failed_tests(
            {
                "TEST-de.example.FooTest.xml": report(
                    "de.example.FooTest",
                    case("de.example.FooTest", "fails", "failure", "nope"),
                )
            }
        )

        self.assertEqual(result.returncode, 0)

    def test_tolerates_a_missing_report_directory(self):
        result = self.run_failed_tests(None)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("No report directory at", result.stdout)


if __name__ == "__main__":
    unittest.main()
