from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
COVERAGE_SUMMARY = ROOT / "scripts/ci/coverage-summary.py"
MAVEN_BUILD_ACTION = ROOT / ".github/actions/maven-build/action.yml"

HEADER = (
    "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,"
    "BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,"
    "METHOD_MISSED,METHOD_COVERED"
)


def row(
    package: str,
    class_name: str,
    line_covered: int,
    line_missed: int,
    branch_covered: int = 0,
    branch_missed: int = 0,
) -> str:
    return (
        f"userservice,{package},{class_name},0,0,{branch_missed},{branch_covered},"
        f"{line_missed},{line_covered},0,0,0,0"
    )


class CoverageSummaryTest(unittest.TestCase):
    def run_summary(self, rows: list[str] | None):
        with tempfile.TemporaryDirectory() as temp_dir:
            report = Path(temp_dir) / "jacoco.csv"
            if rows is not None:
                report.write_text("\n".join([HEADER, *rows]) + "\n")

            return subprocess.run(
                [sys.executable, COVERAGE_SUMMARY, "--report", report],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_reports_line_and_branch_percentages(self):
        result = self.run_summary(
            [
                row("de.example.one", "Foo", line_covered=75, line_missed=25),
                row(
                    "de.example.two",
                    "Bar",
                    line_covered=25,
                    line_missed=75,
                    branch_covered=1,
                    branch_missed=3,
                ),
            ]
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("| Lines | 100 | 200 | **50.00%** |", result.stdout)
        self.assertIn("| Branches | 1 | 4 | **25.00%** |", result.stdout)

    def test_orders_the_breakdown_least_covered_first(self):
        result = self.run_summary(
            [
                row("de.example.covered", "Foo", line_covered=90, line_missed=10),
                row("de.example.bare", "Bar", line_covered=1, line_missed=99),
            ]
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        bare = result.stdout.index("de.example.bare")
        covered = result.stdout.index("de.example.covered")
        self.assertLess(bare, covered, "least covered package must come first")

    def test_aggregates_classes_of_one_package_into_a_single_row(self):
        """A per-class table would bury the totals; the HTML report carries that detail."""
        result = self.run_summary(
            [
                row("de.example", "Foo", line_covered=50, line_missed=0),
                row("de.example", "Bar", line_covered=0, line_missed=50),
            ]
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count("| `de.example` |"), 1)
        self.assertIn("| `de.example` | 100 | 50.0% |", result.stdout)
        self.assertIn("across 1 packages", result.stdout)

    def test_counts_a_class_without_branches_as_fully_covered(self):
        """Zero of zero branches is not applicable, not uncovered."""
        result = self.run_summary([row("de.example", "Foo", line_covered=10, line_missed=0)])

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("| Branches | 0 | 0 | **100.00%** |", result.stdout)

    def test_explains_a_missing_report_without_failing_the_step(self):
        """A build that fails before the report goal leaves no CSV behind."""
        result = self.run_summary(None)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("No JaCoCo report at", result.stdout)

    def test_tolerates_a_report_that_contains_no_classes(self):
        result = self.run_summary([])

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("contains no classes", result.stdout)

    def test_nothing_runs_after_the_inventory_gate_inside_its_pipeline(self):
        """A command after `suite-inventory.py` in that group replaces its exit status.

        The group is piped into tee, so the pipeline reports the group's status,
        which is the status of its last command. Appending even a bare `echo` for
        spacing makes a red suite exit 0 and the whole job report success. Blank
        lines between summary sections are printed by the scripts instead.
        """
        action = MAVEN_BUILD_ACTION.read_text()

        gate = action.index("suite-inventory.py --require unit")
        remainder = action[gate:]
        closing = remainder.index("} | tee -a")
        between = remainder[len("suite-inventory.py --require unit") : closing].strip()

        self.assertEqual(
            between,
            "",
            "no command may follow the inventory gate inside its pipeline group",
        )

    def test_maven_test_step_lets_the_build_reach_the_jacoco_report_goal(self):
        """Surefire and jacoco:report share the `test` phase, and surefire runs first.

        Without failure.ignore a red suite halts the build at surefire, the report
        goal never runs, and coverage is missing from exactly the runs that need
        looking at. `suite-inventory.py --require unit` still fails the job.
        """
        action = MAVEN_BUILD_ACTION.read_text()

        self.assertIn("-Dmaven.test.failure.ignore=true", action)
        self.assertIn("--require unit", action)


if __name__ == "__main__":
    unittest.main()
