#!/usr/bin/env python3
"""Explicit, separately leased native User checked-origin -> real Tenant policy slice.

Compile/format the prepared fixture sources first. This runner never starts the whole
User application or substitutes a transport response for Tenant's actual policy guard.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as XML


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def inspect_optional(kind, name):
    command = ["docker", *([kind] if kind in ("network", "volume") else []), "inspect", name]
    result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    if not result.returncode:
        return json.loads(result.stdout)[0]
    # A daemon/access/timeout failure must never be reported as confirmed removal.
    missing = re.search(r"[Nn]o such (?:object|container|network|volume)", result.stderr)
    missing_network = kind == "network" and ("network " + name + " not found") in result.stderr
    if result.returncode == 1 and (missing or missing_network):
        return None
    raise RuntimeError("Exact owned resource readback is unavailable")


def main():
    parser = argparse.ArgumentParser()
    for name in ("user", "tenant", "helm", "java-home", "evidence"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    user, tenant, helm = (getattr(args, k).resolve() for k in ("user", "tenant", "helm"))
    java = args.java_home.resolve()
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=False)
    required_sources = (
        tenant / "src/test/java/com/vi/tenantservice/api/controller/AccountPolicyReceiverFixture.java",
        user / "src/test/java/de/caritas/cob/userservice/api/service/accountpolicy/CheckedAccountPolicyNativeGate.java",
    )
    if any(not path.is_file() for path in required_sources):
        receipt = {"state": "MISSING_PREPARED_SOURCE_NO_EXECUTION",
                   "safeFailure": "Both native fixture sources must be transplanted/formatted/compiled first"}
        (args.evidence / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))
        return 2
    support = module("bootstrap_native_support", tenant / "scripts/test-bootstrap-context-roundtrip.py")
    sys.path.insert(0, str(helm / "tests"))
    native = module("policy_native_actors", helm / "tests/scenario_c_receiver_fixture.py")
    import _keycloak_container as shared
    before = {"user": support.source_manifest(user), "tenant": support.source_manifest(tenant),
              "helmFixture": {str(p.relative_to(helm)): hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in (helm / "tests/scenario_c_receiver_fixture.py", helm / "tests/_keycloak_container.py")},
              "runner": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (args.evidence / "before-source.json").write_text(json.dumps(before, indent=2, sort_keys=True) + "\n")
    run_id = uuid.uuid4().hex
    network = "oriso-policy-" + run_id[:12]
    container = "oriso-policy-kc-" + run_id[:12]
    label = "oriso-policy-run-id=" + run_id
    receipt = {"state": "RUNNING", "runId": run_id, "stage": "prestart", "image": shared.IMAGE,
               "budgetMiB": 2896, "cleanup": [], "initialMemoryFreePercent": support.free_guard()}
    if receipt["initialMemoryFreePercent"] < 40:
        receipt["state"] = "PRESTART_REFUSAL_NO_EXECUTION"
        (args.evidence / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps({"state": receipt["state"]}))
        return 2
    server = None
    server_log = None
    private_directory = None
    planned_container = False
    planned_network = False
    status = 0
    overall = time.monotonic() + 1200
    try:
        # Track names before creation: an uncertain create response must still be inspected.
        planned_network = True
        support.docker("network", "create", "--label", label, network)
        planned_container = True
        support.docker("run", "-d", "--name", container, "--network", network, "--label", label,
                       "--memory=768m", "--cpus=2", "-p", "127.0.0.1::8080",
                       "-e", "JAVA_OPTS_APPEND=-Xmx384m -XX:ActiveProcessorCount=2",
                       "-e", "KC_BOOTSTRAP_ADMIN_USERNAME=" + shared.INSTALLER[0],
                       "-e", "KC_BOOTSTRAP_ADMIN_PASSWORD=" + shared.INSTALLER[1],
                       shared.IMAGE, "start-dev", "--http-relative-path=/auth")
        base = "http://" + support.docker("port", container, "8080/tcp").splitlines()[0] + "/auth"
        receipt["stage"] = "native-keycloak"
        deadline = min(time.monotonic() + 120, overall)
        while True:
            support.free_guard()
            try:
                admin = shared.master_admin(base)
                break
            except Exception:
                if time.monotonic() >= deadline:
                    raise support.FixtureError("Owned policy Keycloak did not become ready") from None
                time.sleep(2)
        realm = "policy-" + run_id[:12]
        actors = native.seed_actors(base, realm, admin)
        native.validate_native_accounts(base, realm, admin, actors)
        for actor in actors:
            native.validate_token(native.issue(base, realm, actor), actor, base + "/realms/" + realm)
        private_directory = tempfile.TemporaryDirectory(prefix="oriso-policy-")
        private = Path(private_directory.name).resolve()
        os.chmod(private, 0o700)
        fixture = private / "fixture.json"
        with os.fdopen(os.open(fixture, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as output:
            json.dump({"identityModel": "FIVE_ACTORS", "issuer": base + "/realms/" + realm, "tasks": actors}, output)
        env = {**os.environ, "JAVA_HOME": str(java), "PATH": str(java / "bin") + os.pathsep + os.environ["PATH"],
               "MAVEN_OPTS": "-Xmx512m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2",
               "GIT_CONFIG_NOSYSTEM": "1", "ORISO_TASK_TOKEN_FIXTURE": str(fixture),
               "ORISO_POLICY_SHARED_KEY": base64.b64encode(bytes([11]) * 32).decode(),
               "ORISO_POLICY_RECEIVER_READY": str(private / "receiver-ready"),
               "ORISO_POLICY_QUERY_COUNT": str(private / "policy-query-count")}
        for name, root in (("tenant", tenant), ("user", user)):
            receipt["stage"] = name + "-compile"
            command = ["./mvnw", "-q"]
            if name == "tenant":
                command += ["-Pnative-permission-matrix"]
            command += ["test-compile", "dependency:build-classpath", "-Dmdep.includeScope=test",
                        "-Dmdep.outputFile=" + str(private / (name + "-classpath"))]
            if support.bounded(command, root, env, private / (name + "-compile.log"), 420, overall):
                raise support.FixtureError(name + " compile failed before native policy execution")
        classpath = os.pathsep.join([str(tenant / "target/test-classes"), str(tenant / "target/classes"),
                                    (private / "tenant-classpath").read_text().strip()])
        jvm = ["-Xmx384m", "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=32m",
               "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=2"]
        server_log = support.private_log(private / "tenant.log")
        server = subprocess.Popen([str(java / "bin/java"), *jvm, "-cp", classpath,
            "com.vi.tenantservice.api.controller.AccountPolicyReceiverFixture"], cwd=tenant, env=env,
            stdin=subprocess.PIPE, stdout=server_log, stderr=subprocess.STDOUT, start_new_session=True)
        receipt["stage"] = "real-tenant-startup"
        deadline = min(time.monotonic() + 120, overall)
        ready = Path(env["ORISO_POLICY_RECEIVER_READY"])
        while not ready.is_file():
            support.free_guard()
            if server.poll() is not None or time.monotonic() >= deadline:
                raise support.FixtureError("Real Tenant policy fixture did not become ready")
            time.sleep(1)
        env["ORISO_POLICY_RECEIVER_URL"] = ready.read_text().strip()
        env["MAVEN_OPTS"] = "-Xmx256m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=16m -XX:ActiveProcessorCount=2"
        receipt["stage"] = "actual-user-modules-to-tenant-http"
        started = time.time()
        result = support.bounded(["./mvnw", "-q", "-Dtest=CheckedAccountPolicyNativeGate",
            "-DargLine=" + " ".join(jvm), "surefire:test"], user, env, private / "user.log", 240, overall)
        report = user / "target/surefire-reports/TEST-de.caritas.cob.userservice.api.service.accountpolicy.CheckedAccountPolicyNativeGate.xml"
        if not report.is_file() or report.stat().st_mtime < started:
            raise support.FixtureError("Fresh full native policy XML is missing")
        root = XML.parse(report).getroot()
        counts = {k: int(root.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")}
        if counts["tests"] != 5 or counts["skipped"]:
            raise support.FixtureError("The five native policy cases were skipped or missing")
        raw = report.read_bytes()
        if re.search(rb"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", raw):
            raise support.FixtureError("Unsafe token material in policy XML; retained privately only")
        shutil.copy2(report, args.evidence / report.name)
        receipt["counts"] = counts
        after = {**before, "user": support.source_manifest(user), "tenant": support.source_manifest(tenant)}
        receipt["sourceUnchanged"] = before == after
        if not receipt["sourceUnchanged"]:
            raise support.FixtureError("Source changed during the native policy proof")
        receipt["state"] = "PASS" if not result and not any(counts[k] for k in ("failures", "errors", "skipped")) else "EXECUTED_FAILURE"
        status = 0 if receipt["state"] == "PASS" else 1
    except Exception as failure:
        receipt["state"] = "FIXTURE_FAILURE"
        receipt["failureType"] = type(failure).__name__
        if isinstance(failure, support.FixtureError):
            receipt["safeFailure"] = str(failure)
        if private_directory:
            diagnostics = []
            for path in Path(private_directory.name).glob("*.log"):
                for line in path.read_text(errors="replace").splitlines():
                    if ("[ERROR]" in line or "Caused by:" in line or "APPLICATION FAILED" in line) and "eyJ" not in line:
                        for actor in locals().get("actors", []):
                            line = line.replace(actor["secret"], "[redacted]")
                        diagnostics.append({"log": path.name, "line": line[:500]})
            receipt["safeDiagnostics"] = diagnostics[-18:]
        status = 2
    finally:
        cleanup_errors = []
        try:
            if server is not None:
                if server.stdin:
                    server.stdin.close()
                support.stop(server)
        except Exception as failure:
            cleanup_errors.append({"ownedServer": type(failure).__name__})
        if server_log:
            server_log.close()
        for kind, name, planned in (("container", container, planned_container), ("network", network, planned_network)):
            if not planned:
                continue
            try:
                metadata = inspect_optional(kind, name)
                if metadata is not None:
                    labels = metadata["Config"].get("Labels", {}) if kind == "container" else metadata.get("Labels", {})
                    if labels.get("oriso-policy-run-id") != run_id:
                        raise RuntimeError("Exact owned label mismatch; refusing cleanup")
                    if kind == "container":
                        volumes = [m["Name"] for m in metadata.get("Mounts", []) if m["Type"] == "volume"]
                        support.docker("rm", "-f", "-v", name)
                        for volume in volumes:
                            if inspect_optional("volume", volume) is not None:
                                raise RuntimeError("Owned anonymous volume survived cleanup")
                        receipt["cleanup"].append({"container": name, "ownedAnonymousVolumesConfirmedAbsent": volumes})
                    else:
                        support.docker("network", "rm", name)
                if inspect_optional(kind, name) is not None:
                    raise RuntimeError("Exact owned resource survived cleanup")
                receipt["cleanup"].append({"kind": kind, "name": name, "confirmedAbsent": True})
            except Exception as failure:
                cleanup_errors.append({kind: name, "failureType": type(failure).__name__})
        if private_directory:
            private_directory.cleanup()
        if cleanup_errors:
            receipt["executionState"] = receipt["state"]
            receipt["state"] = "OWNED_CLEANUP_UNCONFIRMED"
            receipt["cleanupErrors"] = cleanup_errors
            status = 2
        (args.evidence / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print(json.dumps({"state": receipt["state"], "stage": receipt["stage"], "counts": receipt.get("counts"), "safeFailure": receipt.get("safeFailure")}))
    return status


if __name__ == "__main__":
    sys.exit(main())
