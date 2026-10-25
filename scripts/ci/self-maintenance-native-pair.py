#!/usr/bin/env python3
"""Two real JPA/public SELF/native replay cases; no credentials or raw output retained."""
import argparse
import ast
import base64
import copy
import hashlib
import http.server
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import threading
import time
import unittest
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
KC_COMMIT = "5d4c58e8df6090451fa544481d7ef16e6fab4fc1"
SUITE = "de.caritas.cob.userservice.api.adapters.web.controller.UserSelfMaintenanceNativePairProof"
CASES = {"password": "publicPasswordSavedReplayRequiresCurrentNativeGrantWithoutRepeatingJpaCallback",
         "locale": "publicLocaleSavedReplayRequiresCurrentNativeGrantWithoutRepeatingJpaCallback"}
IMAGE = "quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992"
WITNESS_FLAGS = {"actualJpa", "actualCurrentSelf", "canonicalAdmin", "firstNative200",
    "savedBeforeWithdrawal200", "sameFrozenCommand", "sameHumanAfterRestart", "readAfterWithdrawal200",
    "savedAfterWithdrawal403", "sqlAcknowledgementUnchanged", "oneLocalCallback",
    "freshHumanAfterRestoration", "restoredReplay200", "originalReceiptRestored",
    "nativeLogicalEffectVerified"}


def reviewed_operations(kind):
    if kind not in CASES: raise ValueError("Unknown finite SELF maintenance case")
    return ["account.read", "account.password" if kind == "password" else "account.locale"]


def checked_witness(value, kind):
    if (kind not in CASES or not isinstance(value, dict) or set(value) != WITNESS_FLAGS | {"kind"}
            or value["kind"] != kind or any(type(value[key]) is not bool for key in WITNESS_FLAGS)):
        raise ValueError("Exact nonsecret finite witness required")
    return all(value[key] for key in WITNESS_FLAGS)


def source_frame_catalog(paths):
    """Known source definitions only; the native caller supplies four fixed repository files."""
    result = {}
    for path in paths:
        path = Path(path).resolve()
        tree = ast.parse(path.read_text(), filename=str(path))
        result[str(path)] = [(node.name, node.lineno, node.end_lineno)
            for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))]
    return result


def sanitized_failure_frames(error, catalog):
    """Never inspect exception text, source lines, frame locals, or arbitrary filenames."""
    frames = []
    cursor = error.__traceback__
    while cursor is not None:
        filename = str(Path(cursor.tb_frame.f_code.co_filename).resolve())
        function = cursor.tb_frame.f_code.co_name
        line = cursor.tb_lineno
        if any(name == function and first <= line <= last
                for name, first, last in catalog.get(filename, [])):
            frames.append({"file": Path(filename).name, "function": function, "line": line})
        cursor = cursor.tb_next
    return frames[-20:]


def manifest(root):
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    return {name: hashlib.sha256(b"symlink\0" + os.readlink(root / name).encode()
            if (root / name).is_symlink() else (root / name).read_bytes()).hexdigest()
            for name in sorted(filter(None, names))}


def snapshot(kc):
    user = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    native = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=kc, text=True).strip()
    if user != os.environ.get("GITHUB_SHA") or native != KC_COMMIT:
        raise ValueError("Immutable paired source mismatch")
    return {"user_commit": user, "keycloak_commit": native,
            "user_sources": manifest(ROOT), "keycloak_sources": manifest(kc)}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def sanitize(reports, output, kind):
    if kind not in CASES: raise ValueError("Unknown finite SELF case")
    clean = ET.Element("testsuite", name=SUITE)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob("TEST-*.xml")); names = []; valid = len(files) == 1; frames = []
    for path in files:
        suite = ET.parse(path).getroot(); valid &= suite.get("name") == SUITE
        for case in suite.findall("testcase"):
            names.append(case.get("name")); valid &= case.get("classname") == SUITE
            child = ET.SubElement(clean, "testcase", classname=SUITE, name=case.get("name") or "missing")
            counts["tests"] += 1
            for tag, key in [("failure", "failures"), ("error", "errors"), ("skipped", "skipped")]:
                problem = case.find(tag)
                if problem is not None:
                    counts[key] += 1
                    ET.SubElement(child, tag, message="Sensitive paired diagnostic omitted")
                    frames += re.findall(r"UserSelfMaintenanceNativePairProof\.java:[0-9]+", problem.text or "")
        for key, count in counts.items():
            valid &= suite.get(key) is None or int(suite.get(key)) == count
    valid &= names == [CASES[kind]]
    for key, value in counts.items(): clean.set(key, str(value))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding="utf-8", xml_declaration=True)
    return {**counts, "exact_method_inventory": valid, "failure_locations": sorted(set(frames)),
            "accepted": valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)}


def native(kc, report):
    if os.environ.get("PAIR_KIND") == "role-agency":
        import importlib.util
        spec = importlib.util.spec_from_file_location("role_maintenance_native", ROOT / "scripts/ci/role-maintenance-native.py")
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        result = helper.native(kc, report, sys.modules[__name__])
        return 0 if result.get("accepted") is True else 1
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Fresh hosted Linux native fixture required")
    available = int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemAvailable:")))
    if available < 4 * 1024 * 1024: raise ValueError("Four GiB headroom with two GiB reserve required")
    kind = os.environ.get("PAIR_KIND")
    if kind not in CASES: raise ValueError("Unknown finite native pair")
    module = kc / "keycloak-image/account-lifecycle-spi"; otp = kc / "keycloak-image/otp-config-spi"
    jars = {"LIFECYCLE_C_TEST_JAR": module / "target/account-lifecycle-spi-1.0-SNAPSHOT.jar",
            "OTP_C_TEST_JAR": otp / "target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar"}
    if not all(path.is_file() for path in jars.values()): raise ValueError("Actual paired provider JARs required")
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    classes = ["target/test-classes/" + SUITE.replace(".", "/") + ".class"] + [
        "target/classes/de/caritas/cob/userservice/api/" + path + ".class" for path in [
            "adapters/web/controller/UserController", "adapters/web/controller/UserAccountControllerDelegate",
            "adapters/keycloak/lifecycle/SelfPasswordMaintenance", "adapters/keycloak/maintenance/SelfLocaleMaintenance",
            "adapters/keycloak/lifecycle/SelfAccountOwner", "adapters/keycloak/lifecycle/AccountLifecycleCodec",
            "config/auth/TaskActorTokenBroker", "AccountManager", "service/ConsultantService"]]
    if not all((ROOT / path).is_file() for path in classes): raise ValueError("Actual compiled producer/JPA classes required")
    compiled = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
    before = snapshot(kc)
    if before != json.loads(report.with_name("self-maintenance-native-before.json").read_text()):
        raise ValueError("Source changed after required build snapshot")
    sys.path.insert(0, str(module / "tests"))
    import lifecycle_fixture as lifecycle
    from maintenance_human_fixture import CLIENT, ISSUER, HumanMaintenanceFixture
    from protected_account_operations import OperationFixture, ProtectedOperationRead
    if lifecycle.IMAGE != IMAGE: raise ValueError("Exact native image required")
    frame_catalog = source_frame_catalog([Path(__file__), *[
        module / "tests" / name for name in ["lifecycle_fixture.py",
            "maintenance_human_fixture.py", "protected_account_operations.py"]]])

    class UserPair(HumanMaintenanceFixture, unittest.TestCase):
        native_issuer = ISSUER
        installer_claims = staticmethod(OperationFixture.installer_claims)
        check_installer_identity = OperationFixture.check_installer_identity

        @classmethod
        def ready(cls):
            super().ready()
            cls("runTest").check_installer_identity([cls.installer], "NATIVE_READY")

        def admin(self, method, path="", body=None):
            previous = self.installer; claims = self.installer_claims(previous)
            if type(claims.get("exp")) is not int: raise ValueError("Installer expiry required")
            if claims["exp"] <= time.time() + 30:
                type(self).ready(); self.installer = type(self).installer
                self.check_installer_identity([previous, self.installer], "RENEWAL")
            return super().admin(method, path, body)

        @classmethod
        def reviewed_human_profile(cls):
            return ({"kind": "CONSULTANT", "group": "consultant", "roles": ["consultant", "group-chat-consultant"], "id": "consultant-v1"}
                    if kind == "password" else {"kind": "ASKER", "group": "user", "roles": ["user"], "id": "user-v1"})

        @classmethod
        def reviewed_self_operations(cls): return reviewed_operations(kind)

        @classmethod
        def extra_launch_arguments(cls):
            return ["-v", str(jars["OTP_C_TEST_JAR"].resolve()) + ":/opt/keycloak/providers/otp-config-spi.jar:ro", "-e", "IDENTITY_ACCOUNT_ADMIN_CLIENT_ID=backend-account-admin", "-e", "ORISO_APP_BASE_URL=https://otp-fixture.internal", "-e", "EMAIL_BRANDING_NAME=OTP Fixture", "-e", "EMAIL_LEGAL_ORGANISATION_NAME=Fixture Operator", "-v", str(kc / "keycloak-image/themes/oriso") + ":/opt/keycloak/themes/oriso:ro", "--add-host", "host.docker.internal:host-gateway"]

    receipt = {"kind": kind, "case": CASES[kind], "phase": "fixture-setup", "accepted": False,
        "executed": 0, "genuine_behavior_red": False, "before": before, "jars": hashes,
        "compiled_user_classes": compiled, "image": IMAGE}
    original_env = {key: os.environ.get(key) for key in jars}
    for key, path in jars.items(): os.environ[key] = str(path.resolve())
    control = None; stage = ["ORIGINAL"]; lock = threading.Lock(); controls = []
    try:
        UserPair.setUpClass(); fixture = UserPair("runTest"); fixture.human()
        native_user = fixture.admin("GET", "/users/" + fixture.account_id)
        realm = fixture.admin("GET", ""); realm["adminEventsEnabled"] = True
        realm["adminEventsDetailsEnabled"] = False; fixture.admin("PUT", "", realm)
        prefix = "ORISO_ACCOUNT_MAINTENANCE_CONFIG="
        installed = [arg for arg in UserPair.configured_command if arg.startswith(prefix)]
        assert len(installed) == 1
        original_policy = json.loads(installed[0][len(prefix):])
        assert len(original_policy["ordinaryProfiles"]) == 1
        assert original_policy["ordinaryProfiles"][0]["selfOperations"] == reviewed_operations(kind)
        original_profile = {key: original_policy["ordinaryProfiles"][0][key] for key in original_policy["ordinaryProfiles"][0] if key != "selfOperations"}

        def restart(action):
            with lock:
                assert (action, stage[0]) in {("withdraw", "ORIGINAL"), ("restore", "WITHDRAWN")}
                policy = copy.deepcopy(original_policy)
                if action == "withdraw": policy["ordinaryProfiles"][0]["selfOperations"] = ["account.read"]
                assert {key: policy["ordinaryProfiles"][0][key] for key in original_profile} == original_profile
                before_identity = fixture.admin("GET", "/users/" + fixture.account_id)
                old_command = UserPair.configured_command
                command = [prefix + json.dumps(policy, separators=(",", ":")) if arg.startswith(prefix) else arg for arg in old_command]
                differences = [(a, b) for a, b in zip(old_command, command) if a != b]
                if action == "withdraw": assert differences == [(installed[0], prefix + json.dumps(policy, separators=(",", ":")))]
                else: assert command == old_command
                subprocess.run(["docker", "stop", "-t", "5", UserPair.name], check=True, capture_output=True, timeout=30)
                subprocess.run(["docker", "rm", UserPair.name], check=True, capture_output=True, timeout=30)
                subprocess.run(command, check=True, capture_output=True, timeout=30)
                UserPair.refresh_endpoint("self-grant-" + action); UserPair.ready(); fixture.installer = UserPair.installer
                assert before_identity == fixture.admin("GET", "/users/" + fixture.account_id)
                stage[0] = "WITHDRAWN" if action == "withdraw" else "RESTORED"
                controls.append({"action": action, "sameOriginalProfileAndNativeIncarnation": True,
                    "samePersistedOwnedContainer": True, "onlyLogicalSelfGrantChanged": True})
                return {"nativeBase": UserPair.base, "stage": stage[0]}

        class Control(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                if self.path not in {"/withdraw", "/restore"} or self.headers.get("Content-Length", "0") != "0":
                    self.send_response(400); self.end_headers(); return
                try: value = restart(self.path[1:]); status = 200
                except BaseException as error:
                    receipt["control_failure_category"] = type(error).__name__
                    receipt["control_failure_frames"] = sanitized_failure_frames(error, frame_catalog)
                    value = {}; status = 503
                raw = json.dumps(value).encode(); self.send_response(status)
                self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw)))
                self.end_headers(); self.wfile.write(raw)

        control = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Control)
        threading.Thread(target=control.serve_forever, daemon=True).start()
        environment = dict(os.environ)
        environment.update({"PAIR_KIND": kind, "PAIR_NATIVE_BASE": fixture.base, "PAIR_REALM": lifecycle.REALM,
            "PAIR_REALM_ID": fixture.realm_id, "PAIR_ACCOUNT_ID": fixture.account_id,
            "PAIR_USERNAME": fixture.creation_input["account"]["username"], "PAIR_EMAIL": native_user["email"],
            "PAIR_PASSWORD": fixture.creation_input["account"]["password"], "PAIR_HUMAN_CLIENT": CLIENT,
            "PAIR_CONTEXT_KEY_B64": base64.b64encode(lifecycle.CONTEXT_KEY).decode(),
            "PAIR_CONTROL_BASE": "http://127.0.0.1:" + str(control.server_address[1]),
            "PAIR_WITNESS": str(report.with_name("self-maintenance-native-witness.json").resolve())})
        for actor, (client, _) in lifecycle.ACTORS.items():
            environment.update({"PAIR_" + actor + "_CLIENT": client,
                "PAIR_" + actor + "_SECRET": "synthetic-lifecycle-" + actor, "PAIR_" + actor + "_SUBJECT": lifecycle.SUBJECTS[actor]})
        reports = ROOT / "target/surefire-reports"
        for path in reports.glob("TEST-*.xml"): path.unlink()
        receipt["phase"] = "actual-public-jpa-native-pair"
        child = subprocess.run(["mvn", "-B", "-ntp", "-q", "surefire:test", "-Dtest=" + SUITE + "#" + CASES[kind],
            "-DfailIfNoTests=true", "-Dsurefire.failIfNoSpecifiedTests=true",
            "-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2"],
            cwd=ROOT, env=environment, capture_output=True, timeout=180)
        inventory = sanitize(reports, report.with_suffix(".xml"), kind); receipt.update(inventory)
        receipt["executed"] = inventory["tests"]
        receipt["child_exit"] = child.returncode; child = None
        witness_file = report.with_name("self-maintenance-native-witness.json")
        witness = json.loads(witness_file.read_text()) if witness_file.exists() else {}
        try: receipt["java_witness_complete"] = checked_witness(witness, kind)
        except ValueError:
            if witness_file.exists(): save(witness_file, {"witnessSchemaRejected": True})
            raise
        receipt["genuine_behavior_red"] = (inventory["exact_method_inventory"]
            and inventory["tests"] == inventory["failures"] == 1 and inventory["errors"] == inventory["skipped"] == 0
            and all(witness[key] for key in ["actualJpa", "actualCurrentSelf", "canonicalAdmin", "firstNative200"]))
        assert stage[0] == "RESTORED" and len(controls) == 2
        events = fixture.admin("GET", "/admin-events?resourcePath=accounts/" + fixture.account_id + "&max=100")
        actual = [event for event in events if event.get("resourcePath") == "accounts/" + fixture.account_id
            and event.get("operationType") == "UPDATE" and event.get("resourceType") == "USER"
            and event.get("authDetails", {}).get("clientId") == lifecycle.ACTORS["ACCOUNT_ADMIN"][0]
            and event.get("authDetails", {}).get("userId") == lifecycle.SUBJECTS["ACCOUNT_ADMIN"]]
        receipt["one_actual_native_effect_event"] = len(actual) == 1 and all(event.get("representation") is None for event in actual)
        receipt["controls"] = controls
        receipt["installer_identity_checks"] = ProtectedOperationRead.observation.get("installerIdentityChecks", [])
        identity = receipt["installer_identity_checks"]
        receipt["installer_identity_verified_every_restart"] = len(identity) >= 4 and all(check.get("sameActualEnabledMasterInstallerAndNativeSessionVerified") is True for check in identity)
        receipt["accepted"] = (inventory["accepted"] and receipt["child_exit"] == 0 and receipt["java_witness_complete"]
            and receipt["one_actual_native_effect_event"] and receipt["installer_identity_verified_every_restart"])
        receipt["phase"] = "complete"
    except BaseException as error:
        receipt["exception_category"] = type(error).__name__; receipt["accepted"] = False
        receipt["failure_frames"] = sanitized_failure_frames(error, frame_catalog)
        if isinstance(error, KeyboardInterrupt): raise
    finally:
        if control is not None: control.shutdown(); control.server_close()
        try:
            UserPair.doClassCleanups()
            if getattr(UserPair, "tearDown_exceptions", []): receipt["accepted"] = False; receipt["cleanup_failure_count"] = len(UserPair.tearDown_exceptions)
        except BaseException as error: receipt["cleanup_category"] = type(error).__name__; receipt["accepted"] = False
        for key, value in original_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        receipt["after"] = snapshot(kc); receipt["source_stable"] = before == receipt["after"]
        receipt["jar_stable"] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        receipt["compiled_user_stable"] = compiled == {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
        receipt["accepted"] &= receipt["source_stable"] and receipt["jar_stable"] and receipt["compiled_user_stable"]
        save(report, receipt)
    return 0 if receipt["accepted"] else 1


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("mode", choices=["snapshot", "native", "finalize"])
    parser.add_argument("--keycloak", type=Path, required=True)
    parser.add_argument("--report", type=Path, default=ROOT / "target/self-maintenance-native/self-maintenance-native-result.json")
    args = parser.parse_args()
    if args.mode == "snapshot": save(args.report.with_name("self-maintenance-native-before.json"), snapshot(args.keycloak)); return 0
    if args.mode == "native": return native(args.keycloak.resolve(), args.report)
    if not args.report.exists(): save(args.report, {"phase": "not-executed", "executed": 0, "accepted": False, "genuine_behavior_red": False})
    return 0 if json.loads(args.report.read_text()).get("accepted") is True else 1


if __name__ == "__main__": raise SystemExit(main())
