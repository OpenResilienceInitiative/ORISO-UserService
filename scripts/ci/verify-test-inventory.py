import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", choices=("unit", "integration"), required=True)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--classification", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    pattern = "TEST-*Test.xml" if args.suite == "unit" else "TEST-*IT.xml"
    reports = sorted(args.reports.glob(pattern))
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}

    for report in reports:
        root = ET.parse(report).getroot()
        for field in totals:
            totals[field] += int(root.attrib.get(field, 0))

    print(
        f"{args.suite} inventory: "
        + " ".join(f"{field}={value}" for field, value in totals.items())
        + f" reports={len(reports)}"
    )

    current = json.loads(args.classification.read_text())["currentRequiredSuite"]
    expected_tests = current[f"{args.suite}Tests"]
    expected_skipped = current.get(f"{args.suite}Skipped", 0)
    expected_reports = current.get(f"{args.suite}Reports")

    if totals["failures"] or totals["errors"]:
        print(
            f"{args.suite} inventory contains failures={totals['failures']} "
            f"errors={totals['errors']}.",
            file=sys.stderr,
        )
        return 1
    if totals["tests"] != expected_tests:
        print(
            f"{args.suite} inventory expected {expected_tests} tests, "
            f"found {totals['tests']}.",
            file=sys.stderr,
        )
        return 1
    if totals["skipped"] != expected_skipped:
        print(
            f"{args.suite} inventory expected {expected_skipped} skipped tests, "
            f"found {totals['skipped']}.",
            file=sys.stderr,
        )
        return 1
    if expected_reports is not None and len(reports) != expected_reports:
        print(
            f"{args.suite} inventory expected {expected_reports} reports, "
            f"found {len(reports)}.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
