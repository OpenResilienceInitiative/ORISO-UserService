#!/usr/bin/env python3

"""Derives the current test inventory from Surefire/Failsafe reports.

The stability record used to carry these counts as prose. Every branch that
adds a test changed them, so `documentation/USER_SERVICE_STABILITY.md`
conflicted on every merge and had to be reconciled by hand. The numbers are a
measurement, not a decision, so they are produced here instead of stored.
"""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPORT_DIRECTORY = REPOSITORY_ROOT / "target" / "surefire-reports"


class Suite:
    def __init__(self, label: str):
        self.label = label
        self.reports = 0
        self.tests = 0
        self.failures = 0
        self.errors = 0
        self.skipped = 0

    def add(self, root: ET.Element) -> None:
        self.reports += 1
        self.tests += int(root.attrib.get("tests", 0))
        self.failures += int(root.attrib.get("failures", 0))
        self.errors += int(root.attrib.get("errors", 0))
        self.skipped += int(root.attrib.get("skipped", 0))

    @property
    def clean(self) -> bool:
        return self.failures == 0 and self.errors == 0


def simple_class_name(root: ET.Element) -> str:
    return root.attrib.get("name", "").rsplit(".", 1)[-1]


def collect(report_directory: Path) -> tuple[Suite, Suite]:
    unit = Suite("Unit")
    integration = Suite("Integration + contract + E2E")

    for report in sorted(report_directory.glob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        target = integration if simple_class_name(root).endswith("IT") else unit
        target.add(root)

    return unit, integration


def render_markdown(unit: Suite, integration: Suite) -> str:
    """Renders only the halves that are actually present.

    CI runs unit tests and the integration contract in separate jobs, so each
    job sees one half of the reports. A locally run `./mvnw verify` produces
    both and therefore also the combined total.
    """
    present = [
        (suite, command)
        for suite, command in (
            (unit, "`./mvnw -Dskip.integration-tests=true test`"),
            (integration, "`scripts/ci/run-required-integration-tests.sh`"),
        )
        if suite.reports
    ]
    if not present:
        return "No Surefire or Failsafe reports found."

    lines = [
        "| Suite | Tests | Failures | Errors | Skipped | Command |",
        "| --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for suite, command in present:
        lines.append(
            f"| {suite.label} | {suite.tests:,} | {suite.failures} | "
            f"{suite.errors} | {suite.skipped} | {command} |"
        )

    lines.append("")
    if len(present) == 2:
        lines.append(
            f"Primary inventory: {unit.tests:,} unit plus {integration.tests:,} "
            f"integration executions, or {unit.tests + integration.tests:,}, "
            f"across {unit.reports + integration.reports:,} reports."
        )
    else:
        suite = present[0][0]
        lines.append(
            f"{suite.label} inventory: {suite.tests:,} executions across "
            f"{suite.reports:,} reports. The other half runs in its own job."
        )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--report-directory",
        type=Path,
        default=DEFAULT_REPORT_DIRECTORY,
        help="Surefire/Failsafe report directory (default: target/surefire-reports)",
    )
    parser.add_argument(
        "--require",
        choices=["unit", "integration", "both"],
        help="Fail when the named part of the inventory is empty or not clean",
    )
    arguments = parser.parse_args()

    if not arguments.report_directory.is_dir():
        print(f"No report directory at {arguments.report_directory}.", file=sys.stderr)
        return 1

    unit, integration = collect(arguments.report_directory)
    print(render_markdown(unit, integration))

    if arguments.require is None:
        return 0

    required = {
        "unit": [unit],
        "integration": [integration],
        "both": [unit, integration],
    }[arguments.require]

    for suite in required:
        if suite.tests == 0:
            print(f"{suite.label} inventory is empty.", file=sys.stderr)
            return 1
        if not suite.clean:
            print(f"{suite.label} inventory contains failures or errors.", file=sys.stderr)
            return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
