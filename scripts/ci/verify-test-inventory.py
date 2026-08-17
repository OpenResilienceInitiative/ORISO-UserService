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
    skipped_test_ids: list[str] = []

    for report in reports:
        root = ET.parse(report).getroot()
        for field in totals:
            totals[field] += int(root.attrib.get(field, 0))
        for test_case in root.iter("testcase"):
            if test_case.find("skipped") is None:
                continue
            skipped_test_ids.append(
                f"{test_case.attrib.get('classname', '<unknown>')}"
                f"#{test_case.attrib.get('name', '<unknown>')}"
            )

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
    expected_skipped_test_ids = (
        sorted(
            entry["testId"]
            for entry in current.get("environmentBoundSkippedTests", [])
        )
        if args.suite == "integration"
        else []
    )
    actual_skipped_test_ids = sorted(skipped_test_ids)
    unexpected_skips = sorted(
        set(actual_skipped_test_ids) - set(expected_skipped_test_ids)
    )
    missing_skips = sorted(
        set(expected_skipped_test_ids) - set(actual_skipped_test_ids)
    )
    if unexpected_skips or missing_skips:
        if unexpected_skips:
            print(
                "unexpected skipped tests: " + ", ".join(unexpected_skips),
                file=sys.stderr,
            )
        if missing_skips:
            print(
                "missing expected environment-bound skips: "
                + ", ".join(missing_skips),
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
