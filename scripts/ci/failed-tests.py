#!/usr/bin/env python3

"""Names the tests that failed, for the run summary.

The suite inventory reports how many tests failed but not which ones, so the
first step after a red run was always to open the job log and scroll. This
lists each failing test with its assertion message.

Reporting only: `suite-inventory.py --require` owns whether the job passes, so
this exits 0 even when it finds failures. That keeps the summary sections
ordered by usefulness rather than by which step happens to fail first.
"""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPORT_DIRECTORY = REPOSITORY_ROOT / "target" / "surefire-reports"

# Surefire distinguishes an assertion that did not hold from an exception that
# escaped. Both are red, and the distinction is often the first useful clue.
OUTCOMES = ("failure", "error")

MESSAGE_LIMIT = 160


class FailedTest:
    def __init__(self, class_name: str, name: str, outcome: str, message: str):
        self.class_name = class_name
        self.name = name
        self.outcome = outcome
        self.message = message

    @property
    def simple_class_name(self) -> str:
        return self.class_name.rsplit(".", 1)[-1]


def first_line(message: str) -> str:
    """Assertion messages are often multi-line diffs; a table needs one line."""
    for line in message.splitlines():
        stripped = line.strip()
        if stripped:
            return (
                stripped
                if len(stripped) <= MESSAGE_LIMIT
                else stripped[: MESSAGE_LIMIT - 1] + "…"
            )
    return ""


def escape(value: str) -> str:
    """Keeps a message containing a pipe from splitting the table cell."""
    return value.replace("|", "\\|")


def collect(report_directory: Path) -> list[FailedTest]:
    failed = []

    for report in sorted(report_directory.glob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        for case in root.iter("testcase"):
            for outcome in OUTCOMES:
                element = case.find(outcome)
                if element is None:
                    continue
                message = element.attrib.get("message") or element.text or ""
                failed.append(
                    FailedTest(
                        case.attrib.get("classname", "?"),
                        case.attrib.get("name", "?"),
                        outcome,
                        first_line(message),
                    )
                )
                break

    return failed


def render_markdown(failed: list[FailedTest]) -> str:
    if not failed:
        return "No failing tests in this run."

    lines = [
        f"{len(failed):,} failing test{'s' if len(failed) != 1 else ''}.",
        "",
        "| Test | Outcome | Detail |",
        "| --- | --- | --- |",
    ]
    for test in sorted(failed, key=lambda item: (item.class_name, item.name)):
        lines.append(
            f"| `{test.simple_class_name}.{test.name}` | {test.outcome} | "
            f"{escape(test.message)} |"
        )

    lines.extend(
        [
            "",
            "<details>",
            "<summary>Fully qualified names</summary>",
            "",
            "```",
        ]
    )
    for test in sorted(failed, key=lambda item: (item.class_name, item.name)):
        lines.append(f"{test.class_name}#{test.name}")
    lines.extend(["```", "", "</details>"])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--report-directory",
        type=Path,
        default=DEFAULT_REPORT_DIRECTORY,
        help="Surefire/Failsafe report directory (default: target/surefire-reports)",
    )
    arguments = parser.parse_args()

    if not arguments.report_directory.is_dir():
        print(f"No report directory at {arguments.report_directory}.")
        return 0

    # Trailing blank line: see the note in coverage-summary.py. It belongs here
    # rather than in the workflow step, where it would mask the step's exit
    # status inside the `{ ... } | tee` group.
    print(render_markdown(collect(arguments.report_directory)))
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
