#!/usr/bin/env python3
"""Fresh hosted native checked-origin User -> actual Tenant HTTP policy slice."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as XML

BASES = {"tenant": "c0da86b50ee6537facf4af9b90eb799f5e975063",
         "user": "408d8da9ab7ce54e87af7f57e9499dbb2d3ed424"}
FIXTURES = {
    "tenant": "com/vi/tenantservice/api/controller/AccountPolicyReceiverFixture",
    "user": "de/caritas/cob/userservice/api/service/accountpolicy/CheckedAccountPolicyNativeGate",
}
UNITS = {"tenant": {"com.vi.tenantservice.config.security.WizardPolicyV2ContextTest": 35,
                    "com.vi.tenantservice.api.controller.GroupedTaskServiceIdentityAuthorizationIT": 5},
         "user": {
             "de.caritas.cob.userservice.api.service.accountpolicy.CheckedAccountPolicyReadsTest": 7,
             "de.caritas.cob.userservice.api.service.ConsultantImportServiceTest": 12,
             "de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningServiceTest": 26,
             "de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSagaTest": 41}}
CASES = {
    "actualHeldRepositoryOriginAndActualBrokerReachTheRealNarrowProjection",
    "unheldRevokedChangedAndForeignTransactionOriginsNeverQueryTenant",
    "aDifferentLiveTransactionCannotBorrowTheHeldInvitationCapability",
    "independentlySignedPurposeTargetAndOriginMutationsFailBeforeTheActualPolicyQuery",
    "actualConfiguredCsvLoopIssuesThePrivateImportOriginForTheRealHttpRead",
}


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def xml_inventory(path, started, expected, forbidden=()):
    if not path.is_file() or path.stat().st_mtime < started:
        raise ValueError("Fresh complete policy XML is missing")
    raw = path.read_bytes()
    if (re.search(rb"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?", raw)
            or any(value and value.encode() in raw for value in forbidden)):
        raise ValueError("Unsafe private material in XML; no upload permitted")
    tree = XML.fromstring(raw)
    counts = {k: int(tree.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")}
    cases = tree.findall("testcase")
    names = {case.get("name", "").removesuffix("()") for case in cases}
    if (len(cases) != counts["tests"] or counts["skipped"] or len(names) != len(cases)
            or (isinstance(expected, set) and names != expected)
            or (isinstance(expected, int) and counts["tests"] < expected)
            or any(sum(bool(case.findall(tag)) for case in cases) != counts[key]
                   for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")))):
        raise ValueError("Missing, duplicated or inconsistent policy XML cases")
    return counts, [{"name": case.get("name"),
                     "outcome": next((tag for tag in ("failure", "error") if case.find(tag) is not None), "pass")}
                    for case in cases]


def safe_java_frames(log):
    frames, types = [], []
    for line in log.read_text(errors="replace").splitlines():
        failure = re.search(r"(?:Caused by: |^)([A-Za-z_][A-Za-z0-9_.$]*(?:Exception|Error))(?::|$)", line)
        if failure:
            types.append(failure[1])
        frame = re.fullmatch(r"\s*at ((?:com\.vi\.tenantservice|de\.caritas\.cob\.userservice)[A-Za-z0-9_.$]+)\(([A-Za-z0-9_]+\.java):(\d+)\)", line)
        if frame:
            frames.append({"callsite": frame[1], "file": frame[2], "line": int(frame[3])})
    return {"exceptionTypes": list(dict.fromkeys(types))[-8:], "sourceFrames": frames[-10:]}


def compiled_inventory(root, role, classpath):
    compiled = root / "target/test-classes" / (FIXTURES[role] + ".class")
    if not compiled.is_file() or not classpath.is_file():
        raise ValueError("Actual compiled policy fixture and classpath are required")
    fixtures = {}
    for path in compiled.parent.glob(compiled.stem + "*.class"):
        data = path.read_bytes()
        if not data.startswith(b"\xca\xfe\xba\xbe"):
            raise ValueError("Actual compiled policy fixture is required")
        fixtures[str(path.relative_to(root))] = hashlib.sha256(data).hexdigest()
    dependencies = []
    for item in classpath.read_text().strip().split(os.pathsep):
        path = Path(item)
        if not path.is_file() or path.suffix != ".jar":
            raise ValueError("Every actual test classpath dependency must be a present JAR")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        dependencies.append({"jar": path.name, "sha256": digest, "bytes": path.stat().st_size})
    return {"fixture": fixtures, "classpathSHA256": hashlib.sha256(classpath.read_bytes()).hexdigest(),
            "dependencies": dependencies}


def main():
    parser = argparse.ArgumentParser()
    for name in ("user", "tenant", "helm", "java-home", "evidence"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get("CI") != "true" or os.environ.get("RUNNER_OS") != "Linux":
        raise SystemExit("Policy execution requires the selected fresh hosted Linux runner")
    roots = {role: getattr(args, role).resolve() for role in ("tenant", "user")}
    helm, java = args.helm.resolve(), args.java_home.resolve()
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    support = module("bootstrap_native_support", roots["tenant"] / "scripts/test-bootstrap-context-roundtrip.py")
    resources = support.resources
    sys.path.insert(0, str(helm / "tests"))
    native = module("policy_native_actors", helm / "tests/scenario_c_receiver_fixture.py")
    import _keycloak_container as shared
    before = {role: support.source_manifest(root) for role, root in roots.items()}
    before["helmFixture"] = {str(p.relative_to(helm)): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in (helm / "tests/scenario_c_receiver_fixture.py", helm / "tests/_keycloak_container.py")}
    (evidence / "before-source.json").write_text(json.dumps(before, indent=2, sort_keys=True) + "\n")
    run_id = uuid.uuid4().hex
    network, container = "oriso-policy-" + run_id[:12], "oriso-policy-kc-" + run_id[:12]
    label = "oriso-policy-run-id=" + run_id
    owned = resources.OwnedDocker("oriso-policy-run-id", run_id)
    receipt = {"state": "RUNNING", "runId": run_id, "stage": "prestart", "image": shared.IMAGE,
               "budgetMiB": 2896, "cleanup": [], "sourceUnchanged": False,
               "plannedResources": [["network", network], ["container", container]],
               "plannedProcesses": ["tenant-compile", "user-compile", "tenant-unit", "user-unit", "tenant-server", "user-native"]}
    server = server_log = temporary = None
    status, actors = 2, []
    overall = time.monotonic() + 1200
    def deadline_expired(signum, frame):
        raise support.FixtureError("The policy native job exceeded its 1200 second overall lease")

    try:
        signal.signal(signal.SIGALRM, deadline_expired)
        signal.alarm(1200)
        lease = support.configure_memory(2896, evidence)
        receipt["initialMemoryFreePercent"] = lease.check(starting=True)
        temporary = tempfile.TemporaryDirectory(prefix="oriso-policy-")
        private = Path(temporary.name).resolve()
        os.chmod(private, 0o700)
        env = {**os.environ, "JAVA_HOME": str(java), "PATH": str(java / "bin") + os.pathsep + os.environ["PATH"],
               "MAVEN_OPTS": "-Xmx512m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2",
               "GIT_CONFIG_NOSYSTEM": "1"}
        receipt["compiledClasses"] = {}
        for role, root in roots.items():
            receipt["stage"] = role + "-compile"
            source = root / "src/test/java" / (FIXTURES[role] + ".java")
            if not source.is_file() or (root / "target/test-classes" / (FIXTURES[role] + ".class")).exists():
                raise support.FixtureError("Fresh staged native policy source required before execution")
            changed = subprocess.check_output(["git", "diff", "--name-only", BASES[role], "HEAD", "--", "*.java"], cwd=root, text=True).splitlines()
            scoped = [str(root / name) for name in changed if (root / name).is_file()]
            if str(source) not in scoped:
                raise support.FixtureError("Native policy Java inventory is missing from immutable source")
            cp = private / (role + "-classpath")
            command = ["./mvnw", "-B", "-ntp", "-q",
                       "-DspotlessFiles=" + "|".join(re.escape(name) for name in scoped),
                       "test-compile", "dependency:build-classpath", "-Dmdep.includeScope=test",
                       "-Dmdep.outputFile=" + str(cp)]
            if support.bounded(command, root, env, private / (role + "-compile.log"), 420, overall):
                raise support.FixtureError(role + " ordinary compile/format failed before any container")
            receipt["compiledClasses"][role] = compiled_inventory(root, role, cp)
        # The same fresh normal classes run affected legacy and checked-policy units before native resources.
        receipt["units"] = {}
        for role, root in roots.items():
            receipt["stage"] = role + "-unit"
            started = time.time()
            command = ["./mvnw", "-B", "-ntp", "-q", "-Dtest=" + ",".join(UNITS[role]),
                       "-DfailIfNoTests=true", "-DargLine=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2", "surefire:test"]
            result = support.bounded(command, root, env, private / (role + "-unit.log"), 240, overall)
            receipt["units"][role] = {}
            for name, minimum in UNITS[role].items():
                report = root / "target/surefire-reports" / ("TEST-" + name + ".xml")
                counts, cases = xml_inventory(report, started, minimum)
                receipt["units"][role][name] = {"counts": counts, "cases": cases}
                shutil.copy2(report, evidence / report.name)
                if result or any(counts[k] for k in ("failures", "errors", "skipped")):
                    raise support.FixtureError(role + " affected unit suite failed before any container")
        receipt["stage"] = "network"
        owned.plan("network", network)
        support.docker("network", "create", "--label", label, network)
        support.free_guard()
        owned.plan("container", container)
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
        fixture = private / "fixture.json"
        with os.fdopen(os.open(fixture, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as output:
            json.dump({"identityModel": "FIVE_ACTORS", "issuer": base + "/realms/" + realm, "tasks": actors}, output)
        env.update({"ORISO_TASK_TOKEN_FIXTURE": str(fixture),
                    "ORISO_POLICY_SHARED_KEY": base64.b64encode(bytes([11]) * 32).decode(),
                    "ORISO_POLICY_RECEIVER_READY": str(private / "receiver-ready"),
                    "ORISO_POLICY_QUERY_COUNT": str(private / "policy-query-count"),
                    "ORISO_POLICY_OBSERVATIONS": str(private / "policy-observations.json")})
        tenant, user = roots["tenant"], roots["user"]
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
        result = support.bounded(["./mvnw", "-B", "-ntp", "-q", "-Dtest=CheckedAccountPolicyNativeGate",
            "-DargLine=" + " ".join(jvm), "-DfailIfNoTests=true", "surefire:test"], user, env, private / "user-native.log", 240, overall)
        report = user / "target/surefire-reports" / ("TEST-" + FIXTURES["user"].replace("/", ".") + ".xml")
        forbidden = [actor["secret"] for actor in actors] + [env["ORISO_POLICY_SHARED_KEY"], "synthetic-native-policy-link"]
        counts, cases = xml_inventory(report, started, CASES, forbidden)
        shutil.copy2(report, evidence / report.name)
        receipt["counts"], receipt["cases"] = counts, cases
        support.free_guard()
        receipt["state"] = "PASS" if not result and not any(counts[k] for k in ("failures", "errors", "skipped")) else "EXECUTED_FAILURE"
        status = 0 if receipt["state"] == "PASS" else 1
    except Exception as failure:
        receipt["state"] = "FIXTURE_FAILURE"
        receipt["failureType"] = type(failure).__name__
        if isinstance(failure, (support.FixtureError, resources.ResourceRefusal, ValueError)):
            receipt["safeFailure"] = str(failure)
        if temporary and actors:
            receipt["safeJavaFrames"] = {
                path.name: safe_java_frames(path) for path in Path(temporary.name).glob("*.log")}
        # Before actor creation, compiler output cannot contain native credentials or proofs.
        if temporary and not actors:
            diagnostics = []
            for path in Path(temporary.name).glob("*-compile.log"):
                diagnostics += [{"log": path.name, "line": line[:500]} for line in path.read_text(errors="replace").splitlines()
                                if "[ERROR]" in line and "eyJ" not in line]
            receipt["compileDiagnostics"] = diagnostics[-30:]
        status = 2
    finally:
        signal.alarm(0)
        cleanup_errors = []
        if temporary and receipt["state"] != "PASS":
            receipt["safeJavaFrames"] = {
                path.name: safe_java_frames(path) for path in Path(temporary.name).glob("*.log")}
        if temporary:
            observation = Path(temporary.name) / "policy-observations.json"
            if observation.is_file():
                receipt["observed"] = json.loads(observation.read_text())
        try:
            if server is not None:
                if server.stdin:
                    server.stdin.close()
                receipt["processCleanup"] = support.stop(server)
            if server_log:
                server_log.close()
        except Exception as failure:
            cleanup_errors.append({"ownedServer": type(failure).__name__})
        try:
            receipt["cleanup"] = owned.cleanup()
        except resources.CleanupRefusal as failure:
            cleanup_errors.extend(failure.errors)
            receipt["cleanup"] = failure.completed
        after = {role: support.source_manifest(root) for role, root in roots.items()}
        after["helmFixture"] = {str(p.relative_to(helm)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in (helm / "tests/scenario_c_receiver_fixture.py", helm / "tests/_keycloak_container.py")}
        receipt["sourceUnchanged"] = before == after
        (evidence / "after-source.json").write_text(json.dumps(after, indent=2, sort_keys=True) + "\n")
        if temporary:
            temporary.cleanup()
        if cleanup_errors or not receipt["sourceUnchanged"]:
            receipt["executionState"] = receipt["state"]
            receipt["state"] = "CLEANUP_OR_SOURCE_FAILURE_NO_ACCEPTANCE"
            receipt["cleanupErrors"] = cleanup_errors
            status = 2
        (evidence / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print(json.dumps({k: receipt.get(k) for k in ("state", "stage", "counts", "safeFailure")}))
    return status


if __name__ == "__main__":
    sys.exit(main())
