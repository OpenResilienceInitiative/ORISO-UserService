#!/usr/bin/env python3
"""Run the UserService unit gate and identify the checked revision and outcome."""

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / "scripts/ci"


def test_class(value):
    if not re.fullmatch(r"(?:[A-Za-z_]\w*\.)*[A-Za-z_]\w*Test", value, re.ASCII):
        raise argparse.ArgumentTypeError("select one Surefire test class ending in Test")
    return value


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def java_version():
    home = os.environ.get("JAVA_HOME")
    java = str(Path(home) / "bin/java") if home else "java"
    try:
        result = subprocess.run(
            [java, "-version"], capture_output=True, text=True, check=False
        )
    except OSError:
        return None
    match = re.search(r'^(?:openjdk|java) version "([^"]+)"', result.stderr, re.MULTILINE)
    return match.group(1) if match else None


def run_unit_tests(selection):
    command = ["bash", str(CI / "run-maven-with-central-retry.sh"), "-B"]
    if selection:
        command.append(f"-Dtest={selection}")
    started = time.time_ns()
    status = subprocess.run([*command, "test"], cwd=ROOT, check=False).returncode
    if status:
        return (128 - status if status < 0 else status), "maven-test"

    inventory_status = subprocess.run(
        [
            sys.executable,
            str(CI / "suite-inventory.py"),
            "--require",
            "unit",
            "--modified-since-ns",
            str(started),
        ],
        cwd=ROOT,
        check=False,
    ).returncode
    return (1, "unit-test-inventory") if inventory_status else (0, None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test", type=test_class, help="run one unit test class")
    args = parser.parse_args()
    start = time.monotonic()
    revision = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain", "--untracked-files=normal"))
    lock_path = ROOT / git("rev-parse", "--git-path", "oriso-maven-verification.lock")
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            status, failed_step = 1, "checkout-busy"
        else:
            status, failed_step = run_unit_tests(args.test)

    report = {
        "check": "userservice-maven-unit",
        "revision": revision,
        "dirty": dirty,
        "selection": args.test or "all",
        "javaVersion": java_version(),
        "durationSeconds": round(time.monotonic() - start, 3),
        "outcome": "failed" if status else "passed",
        "failedStep": failed_step,
        "exitCode": status,
    }
    serialized = json.dumps(report)
    print(serialized)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write(f"### UserService unit verification\n\n```json\n{serialized}\n```\n\n")
    return status


if __name__ == "__main__":
    sys.exit(main())
