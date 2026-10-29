#!/usr/bin/env python3
"""Source-bound acceptance for the required real-MariaDB creation proof."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

CLASS = "de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationMariaDbIT"
JAVA_SOURCE = "src/test/java/" + CLASS.replace(".", "/") + ".java"
EXPECTED = {
    "actualProxiedSagaJpaCommitIsObservedThroughSharedDataSource": 1,
    "actualOuterSagaRollbackAndCommitFailureRetainIndependentJournal": 2,
    "requiresNewWriterFailureRollsBackItsJdbcConditionalWriteAndNeverPermitsDispatch": 1,
    "twoApplicationContextsPermitExactlyOneNativePostAndReturnThePersistedWinner": 1,
    "independentWritesSurviveOuterRollbackPoolRestartAndAccountDeletion": 1,
    "staleTransitionAndCaptureRacePreservesEveryDimension": 1,
    "additiveUpgradeRetainsExistingAccountFromPreviousChangelogBoundary": 1,
}


def record_source(root, output):
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    inputs = {}
    for name in paths:
        if not name:
            continue
        path = root / name
        raw = os.fsencode(os.readlink(path)) if path.is_symlink() else path.read_bytes()
        inputs[name] = hashlib.sha256(raw).hexdigest()
    data = {
        "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "inputs": inputs,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n")
    return data


def evaluate(report_path, before, after, maven_exit):
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise ValueError("Invalid source frame")
    report = ET.parse(report_path).getroot()
    counts = {key: int(report.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
    cases = report.findall("testcase")
    identities = [(case.get("classname"), case.get("name")) for case in cases]
    methods = Counter(name.split("(")[0] for _, name in identities if isinstance(name, str))
    inputs = before.get("inputs", {})
    snapshot_shape = (
        isinstance(before.get("revision"), str)
        and re.fullmatch("[a-f0-9]{40}", before["revision"]) is not None
        and isinstance(inputs, dict)
        and all(isinstance(name, str) and isinstance(digest, str)
                and re.fullmatch("[a-f0-9]{64}", digest) for name, digest in inputs.items())
        and {"pom.xml", "api/useradminservice.yaml", JAVA_SOURCE} <= set(inputs)
    )
    accepted = (
        maven_exit == 0 and snapshot_shape and before == after
        and report.get("name") == CLASS
        and counts["tests"] >= 8 and counts["tests"] == len(cases)
        and counts["failures"] == counts["errors"] == counts["skipped"] == 0
        and len(identities) == len(set(identities))
        and all(classname == CLASS and isinstance(name, str) and name for classname, name in identities)
        and all(methods[name] >= count for name, count in EXPECTED.items())
        and not any(case.find(kind) is not None for case in cases for kind in ("failure", "error", "skipped"))
    )
    return {
        "accepted": bool(accepted), "counts": counts, "sourceUnchanged": before == after,
        "sourceRevision": before.get("revision"), "trackedInputCount": len(inputs),
        "requiredMethodInvocations": EXPECTED,
        "actualCaseIdentities": [{"classname": classname, "name": name} for classname, name in identities],
        "scope": "Real MariaDB/Liquibase/JPA transactions with mocked native and downstream ports; not native Keycloak HTTP proof.",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("record", "verify"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--proof", type=Path)
    args = parser.parse_args()
    if args.mode == "record":
        if args.output is None:
            parser.error("record requires --output")
        record_source(args.root.resolve(), args.output)
        return 0
    if args.proof is None:
        parser.error("verify requires --proof")
    proof = args.proof
    try:
        before = json.loads((proof / "source-before.json").read_text())
        after = json.loads((proof / "source-after.json").read_text())
        result = evaluate(
            args.root / "target/surefire-reports" / ("TEST-" + CLASS + ".xml"),
            before, after, int((proof / "maven.exit").read_text()),
        )
        compiled = {}
        for folder in ("target/classes", "target/test-classes"):
            for path in sorted((args.root / folder).rglob("*.class")):
                compiled[str(path.relative_to(args.root))] = hashlib.sha256(path.read_bytes()).hexdigest()
        (proof / "compiled-class-sha256.json").write_text(json.dumps(compiled, sort_keys=True, indent=2) + "\n")
        expected_class = "target/test-classes/" + CLASS.replace(".", "/") + ".class"
        result["compiledClassCount"] = len(compiled)
        result["criticalTestClassCompiled"] = expected_class in compiled
        result["accepted"] = result["accepted"] and result["criticalTestClassCompiled"]
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError):
        result = {"accepted": False, "failure": "Required creation proof or source frame is unavailable/invalid"}
    proof.mkdir(parents=True, exist_ok=True)
    (proof / "receipt.json").write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print("Required creation outcome proof: " + ("PASS" if result["accepted"] else "FAIL"))
    return 0 if result["accepted"] else 1


if __name__ == "__main__":
    sys.exit(main())

