#!/usr/bin/env python3

"""Assign database integration tests and verify fresh native test reports."""

import argparse
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET

SCRIPT_DIR = Path(__file__).resolve().parent
MANIFEST = SCRIPT_DIR / "mariadb-contract-tests.txt"
REPORTS = Path("target/surefire-reports")


def read_assignment():
    assignment = {}
    for number, line in enumerate(MANIFEST.read_text().splitlines(), start=1):
        fields = line.partition("#")[0].split()
        if not fields:
            continue
        if (
            len(fields) != 2
            or fields[0] not in ("standard", "shared", "guest")
            or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*IT", fields[1])
        ):
            raise ValueError(f"{MANIFEST.name}:{number}: expected group and test class")
        group, test_class = fields
        if test_class in assignment:
            raise ValueError(f"{MANIFEST.name}:{number}: duplicate class {test_class}")
        assignment[test_class] = group
    groups = list(assignment.values())
    if not {"standard", "shared"}.intersection(groups) or groups.count("guest") != 1:
        raise ValueError(
            "Assign at least one standard class and exactly one guest class"
        )
    return assignment


def reports_for(classes):
    return [
        report
        for report in REPORTS.glob("TEST-*.xml")
        if report.stem.removeprefix("TEST-").rsplit(".", 1)[-1] in classes
    ]


def verify_reports(classes, group):
    observed = {}
    for report in reports_for(classes):
        suite = ET.parse(report).getroot()
        name = suite.attrib["name"].rsplit(".", 1)[-1]
        count = int(suite.attrib["tests"])
        if (
            name not in classes
            or name in observed
            or count < 1
            or (group == "guest" and count != 2)
            or any(
                int(suite.attrib[key]) != 0 for key in ("skipped", "failures", "errors")
            )
        ):
            raise ValueError(f"Invalid {group} MariaDB test report: {report.name}")
        observed[name] = count
    missing = sorted(set(classes) - observed.keys())
    if missing:
        raise ValueError(f"Missing {group} MariaDB test reports: {', '.join(missing)}")
    print(
        f"MariaDB {group} contract: {len(observed)} classes, {sum(observed.values())} passed"
    )


def run_native(group, classes):
    for report in reports_for(classes):
        report.unlink()
    if group == "guest":
        command = ["bash", str(SCRIPT_DIR / "run-maven-with-central-retry.sh")]
    else:
        command = ["./mvnw", "-Dspring.test.context.cache.maxSize=8"]
    completed = subprocess.run(
        [
            *command,
            "-B",
            "-Dskip.unit-tests=true",
            f"-Dtest={','.join(classes)}",
            "integration-test",
        ],
        check=False,
    )
    if completed.returncode:
        return completed.returncode
    verify_reports(classes, group)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", choices=("h2", "standard", "guest"))
    args = parser.parse_args()
    try:
        assignment = read_assignment()
        if args.suite == "h2":
            excluded = [f"!{name}" for name, group in assignment.items() if group != "shared"]
            print(",".join(("**/*IT", *excluded)))
            return 0
        groups = ("standard", "shared") if args.suite == "standard" else ("guest",)
        classes = [name for name, group in assignment.items() if group in groups]
        return run_native(args.suite, classes)
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
