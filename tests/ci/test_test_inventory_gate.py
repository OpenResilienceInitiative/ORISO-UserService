import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
VERIFIER = ROOT / "scripts/ci/verify-test-inventory.py"


class TestInventoryGateTest(unittest.TestCase):
    def run_verifier(
        self,
        suite: str,
        expected_tests: int,
        report_name: str,
        report_tests: int,
        failures: int = 0,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            reports = temp_root / "reports"
            reports.mkdir()
            (reports / report_name).write_text(
                f'<testsuite name="example" tests="{report_tests}" '
                f'failures="{failures}" errors="0" skipped="0"/>'
            )
            classification = temp_root / "classification.json"
            classification.write_text(
                json.dumps(
                    {
                        "currentRequiredSuite": {
                            f"{suite}Tests": expected_tests,
                        }
                    }
                )
            )

            return subprocess.run(
                [
                    sys.executable,
                    VERIFIER,
                    "--suite",
                    suite,
                    "--reports",
                    reports,
                    "--classification",
                    classification,
                ],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_accepts_an_exact_clean_inventory(self):
        for suite, report_name in (
            ("unit", "TEST-ExampleTest.xml"),
            ("integration", "TEST-ExampleIT.xml"),
        ):
            with self.subTest(suite=suite):
                result = self.run_verifier(suite, 3, report_name, 3)

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(f"{suite} inventory: tests=3", result.stdout)

    def test_rejects_test_count_drift(self):
        result = self.run_verifier("unit", 4, "TEST-ExampleTest.xml", 3)

        self.assertEqual(1, result.returncode)
        self.assertIn("expected 4 tests, found 3", result.stderr)

    def test_rejects_failures_even_when_count_matches(self):
        result = self.run_verifier(
            "integration",
            3,
            "TEST-ExampleIT.xml",
            3,
            failures=1,
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("failures=1 errors=0", result.stderr)


if __name__ == "__main__":
    unittest.main()
