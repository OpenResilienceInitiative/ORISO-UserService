#!/usr/bin/env python3
"""Create the bounded Surefire evidence for the UserService #251 test."""

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from math import isfinite
import os
from pathlib import Path
import re
import subprocess
from xml.etree import ElementTree


REPOSITORY = "OpenResilienceInitiative/ORISO-UserService"
CLASS = "de.caritas.cob.userservice.api.service.statistics.event.ArchiveOrDeleteSessionStatisticsEventTest"
METHOD = "getPayload_Should_ReturnValidJsonPayload"
SOURCE = "src/test/java/" + CLASS.replace(".", "/") + ".java"
REPORT_NAME = f"TEST-{CLASS}.xml"
LIMIT = 2 * 1024 * 1024
OUTCOMES = {"failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"}
DIAGNOSTICS = {"system-out", "system-err"}


def utc(value):
    if not value.endswith("Z"):
        raise ValueError("expected UTC")
    return datetime.fromisoformat(value[:-1] + "+00:00")


def selected_case(report):
    with report.open("rb") as stream:
        xml = stream.read(LIMIT + 1)
    if len(xml) > LIMIT:
        raise ValueError("oversized Surefire report")
    text = xml.decode("utf-8")
    if "<!DOCTYPE" in text or "<!ENTITY" in text:
        raise ValueError("unsupported XML declaration")
    suite = ElementTree.fromstring(text)
    cases = suite.findall("testcase")
    if suite.tag != "testsuite" or suite.get("name") != CLASS or suite.get("tests") != str(len(cases)):
        raise ValueError("incomplete Surefire suite")
    selected = [case for case in cases if (case.get("classname"), case.get("name")) == (CLASS, METHOD)]
    if len(selected) != 1:
        raise ValueError("expected one exact terminal case")
    return selected[0]


def export_evidence(report, destination, context):
    if (
        context["repository"] != REPOSITORY
        or context["workflowJob"] != "validate"
        or not re.fullmatch(r"[a-f0-9]{40}", context["commit"])
        or not re.fullmatch(r"[1-9][0-9]*", context["runId"])
        or type(context["runAttempt"]) is not int
        or context["runAttempt"] < 1
    ):
        raise ValueError("invalid candidate run identity")
    start = utc(context["startedAt"])
    end = utc(context["completedAt"])
    modified = datetime.fromtimestamp(report.stat().st_mtime, timezone.utc)
    if not start <= modified <= end:
        raise ValueError("Surefire report is outside the Maven observation window")
    if any(destination.iterdir()):
        raise ValueError("evidence directory must be empty")

    case = selected_case(report)
    suite = ElementTree.Element("testsuite", name=CLASS, tests="1")
    projection = ElementTree.SubElement(suite, "testcase", classname=CLASS, name=METHOD)
    duration = case.get("time")
    if duration is not None:
        duration_seconds = float(duration)
        if not isfinite(duration_seconds) or duration_seconds < 0:
            raise ValueError("invalid native test duration")
        projection.set("time", duration)
    for child in case:
        if child.tag in OUTCOMES:
            ElementTree.SubElement(projection, child.tag)
        elif child.tag not in DIAGNOSTICS:
            raise ValueError("unsupported test case result")

    xml = ElementTree.tostring(suite, encoding="utf-8", xml_declaration=True)
    receipt = {
        **context,
        "schemaVersion": 1,
        "format": "surefire",
        "project": "unit",
        "report": {"name": REPORT_NAME, "sha256": sha256(xml).hexdigest()},
        "cases": [{"file": SOURCE, "name": METHOD, "leaf": METHOD}],
    }
    (destination / REPORT_NAME).write_bytes(xml)
    (destination / "receipt.json").write_text(json.dumps(receipt) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", required=True)
    parser.add_argument("--started-at", required=True)
    parser.add_argument("--completed-at", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL)
        dirty = subprocess.check_output(["git", "status", "--porcelain"], text=True, stderr=subprocess.DEVNULL)
        if checkout.strip() != args.checkout or dirty:
            raise ValueError("candidate checkout changed during the Maven run")
        if os.environ["GITHUB_EVENT_NAME"] not in ("pull_request", "merge_group"):
            raise ValueError("expected a candidate validation event")
        context = {
            "repository": os.environ["GITHUB_REPOSITORY"],
            "commit": args.checkout,
            "runId": os.environ["GITHUB_RUN_ID"],
            "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
            "workflowJob": os.environ["GITHUB_JOB"],
            "startedAt": args.started_at,
            "completedAt": args.completed_at,
        }
        export_evidence(Path("target/surefire-reports") / REPORT_NAME, args.output, context)
    except (ValueError, OSError, OverflowError, KeyError, ElementTree.ParseError, subprocess.CalledProcessError):
        parser.exit(1, "No usable SigNoz unit test evidence for this candidate run.\n")


if __name__ == "__main__":
    main()
