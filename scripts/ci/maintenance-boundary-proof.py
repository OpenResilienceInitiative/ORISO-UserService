#!/usr/bin/env python3
"""Sanitized actual public KeycloakService boundary evidence; never native acceptance."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

SUITE = (
    "de.caritas.cob.userservice.api.adapters.keycloak.KeycloakServiceScenarioCModeTest"
)
LEGACY = "legacyModeRetainsExistingProfileProjection"
CONTROLS = {LEGACY}
CASES = {
    LEGACY,
    "fiveActorsRequiresTheScopedAdapterBeforeRead",
    "fiveActorsRequiresTheScopedAdapterBeforePasswordMutation",
    "fiveActorsRequiresTheScopedAdapterBeforeDeactivate",
    "fiveActorsRequiresTheScopedAdapterBeforeDeletion",
    "fiveActorsRequiresTheScopedAdapterBeforeEveryOtpRoute",
}
DEV_BASE = "c944417d9d664d35d63b31bb57c55f852c2d783c"
WORKFLOW = ".github/workflows/scenario-c-maintenance-caller-proof.yml"

SLICE = "adapter"
COMPILED_CLASSES = [
    "de/caritas/cob/userservice/api/adapters/keycloak/KeycloakService",
    "de/caritas/cob/userservice/api/adapters/keycloak/lifecycle/AccountLifecycleCodec",
]
TEST_CLASS = (
    "de/caritas/cob/userservice/api/adapters/keycloak/KeycloakServiceScenarioCModeTest"
)


def select_slice(name):
    global SUITE, LEGACY, CASES, SLICE, COMPILED_CLASSES, TEST_CLASS, CONTROLS
    if name == "locale":
        SLICE = name
        SUITE = "de.caritas.cob.userservice.api.adapters.web.controller.UserSelfLocaleScenarioCBoundaryTest"
        LEGACY = "legacyLocalePublicPatchKeepsTheExistingNoHeaderIdentityCall"
        CASES = {
            LEGACY,
            "publicPatchCapturesIntentAndDomainCommitBeforeScopedLocaleRpc",
            "publicPatchNewOwnerReplaysFrozenLocaleAfterLostReplyWithoutDomainReapply",
            "publicPatchSameIdCannotSubstituteTheCompleteMixedRequest",
            "publicPatchMissingIntentRefusesBeforeAnyDomainMutation",
            "domainOnlyPublicPatchKeepsItsWorkingNoHeaderContract",
        }
        COMPILED_CLASSES += [
            "de/caritas/cob/userservice/api/adapters/web/controller/UserController",
            "de/caritas/cob/userservice/api/adapters/web/controller/UserAccountControllerDelegate",
            "de/caritas/cob/userservice/api/adapters/keycloak/maintenance/SelfLocaleMaintenance",
            "de/caritas/cob/userservice/api/AccountManager",
            "de/caritas/cob/userservice/api/UserServiceMapper",
        ]
        TEST_CLASS = SUITE.replace(".", "/")
    elif name == "deletion":
        SLICE = name
        SUITE = "de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountScenarioCBoundaryTest"
        LEGACY = "legacyModeRetainsItsExistingDeletionWorkflow"
        CASES = {
            LEGACY,
            "fiveActorsMissingScopedDeletionStopsBeforeOwnedDownstreamCleanup",
            "publicSchedulerFreezesOwnedDeletionBeforeRpcAndAcknowledgesOnlyVerifiedReceipt",
            "publicSchedulerRejectsInvalidReceiptAndRetainsTheExactFrozenIntent",
            "publicSchedulerLosingLeaseBeforeFreezeCannotSendTheNativeDelete",
            "publicSchedulerNewOwnerReplaysFrozenDeleteAfterLostReplyAndAckNeverCallsNativeAgain",
        }
        COMPILED_CLASSES += [
            "de/caritas/cob/userservice/api/workflow/delete/service/DeleteUserAccountService",
            "de/caritas/cob/userservice/api/workflow/delete/scheduler/DeleteUserAccountScheduler",
            "de/caritas/cob/userservice/api/workflow/delete/service/ScheduledAskerMaintenance",
            "de/caritas/cob/userservice/api/workflow/delete/service/UserHardDeleteClaimService",
            "de/caritas/cob/userservice/api/workflow/delete/action/asker/DeleteKeycloakAskerAction",
        ]
        TEST_CLASS = SUITE.replace(".", "/")

    CONTROLS = {LEGACY}
    if name == "locale":
        CONTROLS.add("domainOnlyPublicPatchKeepsItsWorkingNoHeaderContract")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_manifest(root):
    # Actual tracked checkout only. Generated output, raw logs and secrets never become inputs.
    tracked = (
        subprocess.check_output(["git", "ls-files", "-z"], cwd=root)
        .decode()
        .split("\0")
    )
    return {
        name: sha(root / name)
        for name in sorted(name for name in tracked if name)
        if (root / name).is_file() and not name.startswith("target/")
    }


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def sanitize(reports, destination):
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    output = ET.Element("testsuite", name=SUITE)
    names, failures, errors, skipped = [], [], [], []
    legacy_passed = False
    passed_methods = set()
    consistent = True
    assertion_failure = False
    found = list(reports.glob("TEST-*.xml"))
    for report in found:
        suite = ET.parse(report).getroot()
        consistent &= suite.get("name") == SUITE
        local = dict.fromkeys(counts, 0)
        for case in suite.findall("testcase"):
            name = case.get("name", "")
            names.append(name)
            clean = ET.SubElement(
                output,
                "testcase",
                classname=case.get("classname", ""),
                name=name,
                time=case.get("time", "0"),
            )
            consistent &= case.get("classname") == SUITE
            counts["tests"] += 1
            local["tests"] += 1
            passed = True
            for kind, key, recorded in [
                ("failure", "failures", failures),
                ("error", "errors", errors),
                ("skipped", "skipped", skipped),
            ]:
                original = case.find(kind)
                if original is not None:
                    passed = False
                    counts[key] += 1
                    local[key] += 1
                    recorded.append(name)
                    ET.SubElement(clean, kind, message="Sensitive diagnostics omitted")
                    if kind == "failure" and name != LEGACY:
                        assertion_failure |= original.get("type") in {
                            "org.opentest4j.AssertionFailedError",
                            "org.opentest4j.MultipleFailuresError",
                            "java.lang.AssertionError",
                            "org.mockito.exceptions.verification.NoInteractionsWanted",
                        }
            legacy_passed |= name == LEGACY and passed
            if passed:
                passed_methods.add(name)
        for key, value in local.items():
            consistent &= suite.get(key) is None or int(suite.get(key)) == value
    for key, value in counts.items():
        output.set(key, str(value))
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(output).write(destination, encoding="utf-8", xml_declaration=True)
    inventory = (
        len(found) == 1
        and len(names) == len(CASES)
        and set(names) == CASES
        and consistent
    )
    return {
        **counts,
        "floor": len(CASES),
        "exact_inventory": inventory,
        "failed_methods": failures,
        "error_methods": errors,
        "skipped_methods": skipped,
        "legacy_control_passed": legacy_passed,
        "all_required_controls_passed": CONTROLS <= passed_methods,
        "adapter_behavior_red": bool(
            inventory
            and CONTROLS <= passed_methods
            and assertion_failure
            and not errors
            and not skipped
        ),
        "all_passed": bool(inventory and not failures and not errors and not skipped),
    }


def snapshot(root, report):
    toolchain = subprocess.check_output(["mvn", "--version"], cwd=root).decode()
    maven_line = next(
        line for line in toolchain.splitlines() if "Apache Maven " in line
    )
    java_line = next(
        line for line in toolchain.splitlines() if "Java version: " in line
    )
    if (
        "Apache Maven 3.9.14" not in maven_line
        or "Java version: 21.0.12" not in java_line
    ):
        raise ValueError("Unverified diagnostic toolchain")
    ancestor = (
        subprocess.run(
            ["git", "merge-base", "--is-ancestor", DEV_BASE, "HEAD"],
            cwd=root,
            capture_output=True,
        ).returncode
        == 0
    )
    value = {
        "github_sha": os.environ.get("GITHUB_SHA"),
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root)
        .decode()
        .strip(),
        "parents": subprocess.check_output(
            ["git", "show", "-s", "--format=%P", "HEAD"], cwd=root
        )
        .decode()
        .split(),
        "required_dev_parent": DEV_BASE,
        "dev_is_ancestor": ancestor,
        "toolchain": {
            "maven": "3.9.14",
            "java": "21.0.12",
            "role": "checksum-pinned diagnostic runtime; repository wrapper remains 3.9.15",
        },
        "source_manifest": source_manifest(root),
        "workflow_sha256": sha(root / WORKFLOW),
    }
    save(report, value)
    return 0 if ancestor else 1


def collect(root, report):
    before_path = report.parent / "maintenance-caller-before.json"
    before = json.loads(before_path.read_text()) if before_path.is_file() else {}
    result = sanitize(root / "target/surefire-reports", report.with_suffix(".xml"))
    after = source_manifest(root)
    stable = bool(
        before
        and before.get("source_manifest") == after
        and before.get("commit")
        == before.get("github_sha")
        == os.environ.get("GITHUB_SHA")
        and before.get("dev_is_ancestor")
        and before.get("workflow_sha256") == sha(root / WORKFLOW)
    )
    result.update(
        {
            "source_stable": stable,
            "source_manifest": after,
            "github_sha": os.environ.get("GITHUB_SHA"),
            "checkout": before,
            "workflow_sha256": sha(root / WORKFLOW),
            "compiled_class_sha256": {
                name: sha(root / name) if (root / name).is_file() else None
                for name in [
                    *(
                        "target/classes/" + value + ".class"
                        for value in COMPILED_CLASSES
                    ),
                    "target/test-classes/" + TEST_CLASS + ".class",
                ]
            },
            "slice": SLICE,
            "boundary": "Actual public "
            + SLICE
            + " behavior with passing legacy control; not native/complete scoped caller acceptance",
        }
    )
    result["adapter_behavior_red"] &= stable and all(
        result["compiled_class_sha256"].values()
    )
    result["accepted"] = bool(
        stable
        and result["all_passed"]
        and all(result["compiled_class_sha256"].values())
    )
    result["status"] = (
        "GREEN"
        if result["accepted"]
        else (
            "ADAPTER_BEHAVIOR_RED_ONLY"
            if result["adapter_behavior_red"]
            else "UNVERIFIED_OR_SETUP_FAILURE_NOT_BEHAVIOR_RED"
        )
    )
    save(report, result)
    print(
        json.dumps(
            {
                key: value
                for key, value in result.items()
                if key not in {"source_manifest", "checkout"}
            },
            sort_keys=True,
        )
    )
    return 0 if result["accepted"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["snapshot", "collect"])
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--slice", choices=["adapter", "deletion", "locale"], default="adapter")
    args = parser.parse_args()
    select_slice(args.slice)
    root = Path(__file__).resolve().parents[2]
    try:
        status = (
            snapshot(root, args.report)
            if args.mode == "snapshot"
            else collect(root, args.report)
        )
    except Exception as failure:
        save(
            args.report,
            {
                "status": "RUNNER_EXCEPTION_" + type(failure).__name__,
                "accepted": False,
                "adapter_behavior_red": False,
                "tests": 0,
                "floor": len(CASES),
                "failures": 0,
                "errors": 1,
                "skipped": 0,
            },
        )
        suite = ET.Element(
            "testsuite", name=SUITE, tests="1", failures="0", errors="1", skipped="0"
        )
        case = ET.SubElement(
            suite, "testcase", classname=SUITE, name="evidence_not_verified"
        )
        ET.SubElement(case, "error", message="Sanitized evidence runner failure")
        ET.ElementTree(suite).write(
            args.report.with_suffix(".xml"), encoding="utf-8", xml_declaration=True
        )
        status = 1
    raise SystemExit(status)
