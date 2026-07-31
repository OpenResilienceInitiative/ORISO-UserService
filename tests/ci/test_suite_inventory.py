from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "scripts/ci/suite-inventory.py"
STABILITY_DOCUMENT = ROOT / "documentation/USER_SERVICE_STABILITY.md"


def report(name: str, tests: int, failures: int = 0, errors: int = 0, skipped: int = 0) -> str:
    return (
        f'<?xml version="1.0" encoding="UTF-8"?>'
        f'<testsuite name="{name}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}"/>'
    )


class SuiteInventoryContractTest(unittest.TestCase):
    def run_inventory(self, reports: dict[str, str], *arguments: str):
        with tempfile.TemporaryDirectory() as temp_dir:
            report_directory = Path(temp_dir)
            for file_name, content in reports.items():
                (report_directory / file_name).write_text(content)

            return subprocess.run(
                [
                    sys.executable,
                    INVENTORY,
                    "--report-directory",
                    report_directory,
                    *arguments,
                ],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_separates_integration_reports_from_unit_reports(self):
        result = self.run_inventory(
            {
                "TEST-de.example.FooTest.xml": report("de.example.FooTest", tests=7),
                "TEST-de.example.BarIT.xml": report("de.example.BarIT", tests=3, skipped=1),
            },
            "--require",
            "both",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("| Unit | 7 | 0 | 0 | 0 |", result.stdout)
        self.assertIn("| Integration + contract + E2E | 3 | 0 | 0 | 1 |", result.stdout)
        self.assertIn("7 unit plus 3 integration executions, or 10", result.stdout)

    def test_reports_one_half_when_only_one_job_has_run(self):
        result = self.run_inventory(
            {"TEST-de.example.FooTest.xml": report("de.example.FooTest", tests=7)},
            "--require",
            "unit",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("Integration + contract + E2E", result.stdout)
        self.assertIn("The other half runs in its own job.", result.stdout)

    def test_rejects_an_empty_inventory(self):
        result = self.run_inventory({}, "--require", "unit")

        self.assertEqual(result.returncode, 1)
        self.assertIn("empty", result.stderr)

    def test_rejects_reports_containing_failures(self):
        result = self.run_inventory(
            {"TEST-de.example.FooTest.xml": report("de.example.FooTest", tests=7, failures=1)},
            "--require",
            "unit",
        )

        self.assertEqual(result.returncode, 1)
        self.assertIn("failures or errors", result.stderr)

    def test_reports_without_requiring_anything_by_default(self):
        result = self.run_inventory(
            {"TEST-de.example.FooTest.xml": report("de.example.FooTest", tests=7, failures=1)}
        )

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_stability_document_does_not_hardcode_current_counts(self):
        """The counts moved on every branch and made this file conflict on each merge."""
        document = STABILITY_DOCUMENT.read_text()

        self.assertIn("scripts/ci/suite-inventory.py", document)
        for frozen_reference in ("4,707", "3,782", "4,722"):
            self.assertIn(
                frozen_reference,
                document,
                "frozen historical reference points must stay in the record",
            )
        self.assertNotIn(
            "| Unit |",
            document,
            "current suite counts belong in the generated inventory, not in prose",
        )


if __name__ == "__main__":
    unittest.main()
