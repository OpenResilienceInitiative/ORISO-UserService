#!/usr/bin/env python3
"""Actual chosen-password ASKER facade/JPA/native proof; no secret diagnostic output."""
import argparse
import base64
import copy
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import threading
import unittest
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
KC_COMMIT = "5d4c58e8df6090451fa544481d7ef16e6fab4fc1"
SUITE = "de.caritas.cob.userservice.api.admin.service.AskerReactivationNativePairProof"
CASE = "actualChosenPasswordPolicyReleaseAndCurrentGrantReplayPreserveOneJpaCallback"
FLAGS = {"actualJpa", "actualCurrentPlatform", "canonicalAdmin", "actualNativeRead200", "nativeProjectionBoundExceptMailboxCase", "nativeEmailCaseDifference",
    "signedPolicy400", "nativePolicyRefusalStateUnchanged", "rejectedClaimReleased", "differentPasswordNewOperation", "firstNative200", "newPasswordLogin",
    "oldPasswordLoginDenied", "savedReplay200", "readAfterWithdrawal200", "savedAfterWithdrawal403", "sqlAcknowledgementUnchanged",
    "restoredReplay200", "originalReceiptRestored", "sameFrozenCommands", "oneLocalCallback", "actualOriginalMachineDeadlineBound", "lost200FrozenUntilRenewedAfterOriginalDeadline"}
_spec = importlib.util.spec_from_file_location("existing_self_pair_data_controls", ROOT / "scripts/ci/self-maintenance-native-pair.py")
shared = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(shared)


def withdraw_reactivation(policy):
    """Split only already-reviewed PLATFORM user reach; preserve all other profile/operation grants."""
    result = copy.deepcopy(policy); grants = result["adminOperations"]; changed = []
    for grant in grants:
        if grant["authority"] == "PLATFORM" and "user-v1" in grant["targetProfiles"] and "account.reactivate" in grant["operations"]:
            only_user = copy.deepcopy(grant); only_user["targetProfiles"] = ["user-v1"]
            only_user["operations"] = [value for value in grant["operations"] if value != "account.reactivate"]
            other = copy.deepcopy(grant); other["targetProfiles"] = [value for value in grant["targetProfiles"] if value != "user-v1"]
            if other["targetProfiles"]: changed.append(other)
            changed.append(only_user)
        else: changed.append(grant)
    result["adminOperations"] = changed
    if result == policy: raise ValueError("Existing explicit user PLATFORM reactivate grant required")
    return result


def witness(value):
    if not isinstance(value, dict) or set(value) != FLAGS or any(type(value[key]) is not bool for key in FLAGS):
        raise ValueError("Closed nonsecret native witness required")
    return all(value[key] for key in FLAGS)


def snapshot(kc):
    user = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    native = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=kc, text=True).strip()
    if user != os.environ.get("GITHUB_SHA") or native != KC_COMMIT: raise ValueError("Exact source tuple required")
    return {"user_commit": user, "keycloak_commit": native, "user_sources": shared.manifest(ROOT), "keycloak_sources": shared.manifest(kc)}


def sanitize(reports, output):
    clean = ET.Element("testsuite", name=SUITE); counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob("TEST-*.xml")); names = []; valid = len(files) == 1; locations = []
    for path in files:
        suite = ET.parse(path).getroot(); valid &= suite.get("name") == SUITE
        for case in suite.findall("testcase"):
            names.append(case.get("name")); valid &= case.get("classname") == SUITE
            child = ET.SubElement(clean, "testcase", classname=SUITE, name=case.get("name") or "missing"); counts["tests"] += 1
            for tag, key in [("failure", "failures"), ("error", "errors"), ("skipped", "skipped")]:
                problem = case.find(tag)
                if problem is not None:
                    counts[key] += 1; ET.SubElement(child, tag, message="Sensitive native diagnostic omitted")
                    locations += shared.re.findall(r"AskerReactivationNativePairProof\.java:[0-9]+", problem.text or "")
        for key, count in counts.items(): valid &= suite.get(key) is None or int(suite.get(key)) == count
    valid &= names == [CASE]
    for key, value in counts.items(): clean.set(key, str(value))
    output.parent.mkdir(parents=True, exist_ok=True); ET.ElementTree(clean).write(output, encoding="utf-8", xml_declaration=True)
    return {**counts, "exact_method_inventory": valid, "failure_locations": sorted(set(locations)),
        "accepted": valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)}


def native(kc, report):
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true": raise ValueError("Hosted Linux required")
    if int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemAvailable:"))) < 4 * 1024 * 1024:
        raise ValueError("Original four GiB headroom/two GiB reserve required")
    module = kc / "keycloak-image/account-lifecycle-spi"; otp = kc / "keycloak-image/otp-config-spi"
    jars = {"LIFECYCLE_C_TEST_JAR": module / "target/account-lifecycle-spi-1.0-SNAPSHOT.jar",
        "OTP_C_TEST_JAR": otp / "target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar",
        "SCOPED_OTP_READY_FIXTURE_JAR": module / "target/scoped-otp-ready-fixture.jar"}
    if not all(path.is_file() for path in jars.values()): raise ValueError("Actual provider/isolated observer JARs required")
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    classes = ["target/test-classes/" + SUITE.replace(".", "/") + ".class"] + ["target/classes/de/caritas/cob/userservice/api/" + path + ".class" for path in [
        "admin/facade/AskerUserAdminFacade", "admin/service/IdentityReactivationSagaStore", "admin/service/admin/AdminScope",
        "adapters/keycloak/lifecycle/ScopedAskerReactivation", "adapters/keycloak/lifecycle/AccountLifecycleCodec",
        "adapters/keycloak/lifecycle/SelfPasswordIntentCodec", "adapters/keycloak/lifecycle/ReactivationUserSnapshotSchema",
        "config/auth/TaskActorTokenBroker", "workflow/delete/service/DeletionLifecycleService"]]
    if not all((ROOT / path).is_file() for path in classes): raise ValueError("Actual compiled caller/JPA classes required")
    compiled = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
    before = snapshot(kc)
    if before != json.loads(report.with_name("asker-reactivation-native-before.json").read_text()): raise ValueError("Build source changed")
    sys.path.insert(0, str(module / "tests"))
    import lifecycle_fixture as lifecycle
    from protected_account_operations import OperationFixture, ProtectedOperationRead
    from test_protected_invitations import HUMAN_PASSWORD, ISSUER
    if lifecycle.IMAGE != shared.IMAGE or OperationFixture.native_issuer != ISSUER: raise ValueError("Pinned image/issuer required")
    catalog = shared.source_frame_catalog([Path(__file__), *[module / "tests" / name for name in [
        "lifecycle_fixture.py", "protected_account_operations.py", "test_protected_invitations.py", "protected_otp_bootstrap.py"]]])
    record = {"accepted": False, "executed": 0, "phase": "fixture-setup", "genuine_behavior_red": False,
        "before": before, "jars": hashes, "compiled_user_classes": compiled, "image": lifecycle.IMAGE}
    previous_env = {key: os.environ.get(key) for key in jars}
    for key, path in jars.items(): os.environ[key] = str(path.resolve())
    stage = ["ORIGINAL"]; controls = []; lock = threading.Lock(); control = None; commands = []
    original_run = subprocess.run
    def capture(args, *positional, **keywords):
        if isinstance(args, list) and args[:2] == ["docker", "run"]: commands.append(args.copy())
        return original_run(args, *positional, **keywords)
    try:
        # Observe only the actual fully constructed existing fixture launch, including its existing SMTP/OTP guards.
        subprocess.run = capture
        try: OperationFixture.setUpClass()
        finally: subprocess.run = original_run
        fixture = OperationFixture("runTest"); fixture.human(record=False)
        command = [entry for entry in commands if "--name" in entry and entry[entry.index("--name") + 1] == OperationFixture.name][-1]
        prefix = "ORISO_ACCOUNT_MAINTENANCE_CONFIG="; installed = [arg for arg in command if arg.startswith(prefix)]
        assert len(installed) == 1
        original_policy = json.loads(installed[0][len(prefix):]); withdrawn = withdraw_reactivation(original_policy)
        account = OperationFixture.ordinary_adopted_account; current = fixture.admin("GET", "/users/" + account)
        assert type(current["createdTimestamp"]) is int and current["createdTimestamp"] > 0
        sql_email = "Reactivation.Mixed." + current["id"] + "@OrIso.Org"
        current.update(email=sql_email, enabled=False, requiredActions=[])
        fixture.admin("PUT", "/users/" + account, current)
        old_password = "Original-reactivation-fixture-937451"
        fixture.admin("PUT", "/users/" + account + "/reset-password", {"type": "password", "value": old_password, "temporary": False})
        current = fixture.admin("GET", "/users/" + account)
        assert current["email"] != sql_email and current["email"].lower() == sql_email.lower()
        realm = fixture.admin("GET", ""); realm.update(passwordPolicy="length(12)", adminEventsEnabled=True, adminEventsDetailsEnabled=False); fixture.admin("PUT", "", realm)
        record["actual_native_email_lowercase_measured"] = True
        admin_client = fixture.admin("GET", "/clients?clientId=" + lifecycle.ACTORS["ACCOUNT_ADMIN"][0])[0]
        admin_client.setdefault("attributes", {})["access.token.lifespan"] = "15"
        fixture.admin("PUT", "/clients/" + admin_client["id"], admin_client)
        record["fixture_original_admin_token_lifetime_seconds"] = 15
        def restart(action):
            with lock:
                assert (action, stage[0]) in {("withdraw", "ORIGINAL"), ("restore", "WITHDRAWN")}
                policy_arg = prefix + json.dumps(withdrawn, separators=(",", ":")) if action == "withdraw" else installed[0]
                next_command = [policy_arg if arg.startswith(prefix) else arg for arg in command]
                assert len(command) == len(next_command)
                assert all(old == new or old == installed[0] for old, new in zip(command, next_command))
                native_before = fixture.admin("GET", "/users/" + account)
                original_run(["docker", "stop", "-t", "5", OperationFixture.name], check=True, capture_output=True, timeout=30)
                original_run(["docker", "rm", OperationFixture.name], check=True, capture_output=True, timeout=30)
                OperationFixture.memoryStart()
                original_run(next_command, check=True, capture_output=True, timeout=30)
                OperationFixture.refresh_endpoint("asker-reactivation-" + action); OperationFixture.ready(); fixture.installer = OperationFixture.installer
                assert native_before == fixture.admin("GET", "/users/" + account)
                stage[0] = "WITHDRAWN" if action == "withdraw" else "RESTORED"
                controls.append({"action": action, "sameNativeIncarnation": True, "onlyUserPlatformReactivationGrantChanged": True})
                return {"nativeBase": OperationFixture.base, "stage": stage[0]}
        class Control(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                if self.path not in {"/withdraw", "/restore"} or self.headers.get("Content-Length", "0") != "0": self.send_response(400); self.end_headers(); return
                try: value = restart(self.path[1:]); status = 200
                except BaseException as error:
                    record["control_failure_category"] = type(error).__name__; record["control_failure_frames"] = shared.sanitized_failure_frames(error, catalog); value = {}; status = 503
                raw = json.dumps(value).encode(); self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
        control = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Control); threading.Thread(target=control.serve_forever, daemon=True).start()
        environment = dict(os.environ); environment.update({"PAIR_NATIVE_BASE": fixture.base, "PAIR_REALM": lifecycle.REALM,
            "PAIR_REALM_ID": fixture.realm_id, "PAIR_ACCOUNT_ID": account, "PAIR_USERNAME": current["username"], "PAIR_EMAIL": current["email"],
            "PAIR_SQL_EMAIL": sql_email, "PAIR_OLD_PASSWORD": old_password, "PAIR_PLATFORM_USERNAME": "reviewed-human", "PAIR_PLATFORM_SUBJECT": fixture.human_subject,
            "PAIR_PLATFORM_PASSWORD": HUMAN_PASSWORD, "PAIR_CONTEXT_KEY_B64": base64.b64encode(lifecycle.CONTEXT_KEY).decode(),
            "PAIR_CONTROL_BASE": "http://127.0.0.1:" + str(control.server_address[1]), "PAIR_WITNESS": str(report.with_name("asker-reactivation-native-witness.json").resolve())})
        for actor, (client, _) in lifecycle.ACTORS.items(): environment.update({"PAIR_" + actor + "_CLIENT": client, "PAIR_" + actor + "_SECRET": "synthetic-lifecycle-" + actor, "PAIR_" + actor + "_SUBJECT": lifecycle.SUBJECTS[actor]})
        reports = ROOT / "target/surefire-reports"
        for path in reports.glob("TEST-*.xml"): path.unlink()
        from protected_resources import require_active, evidence
        require_active("before-actual-reactivation-jpa")
        record["phase"] = "actual-facade-jpa-native"
        child = original_run(["mvn", "-B", "-ntp", "-q", "surefire:test", "-Dtest=" + SUITE + "#" + CASE,
            "-DfailIfNoTests=true", "-Dsurefire.failIfNoSpecifiedTests=true", "-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2"],
            cwd=ROOT, env=environment, capture_output=True, timeout=180)
        require_active("after-actual-reactivation-jpa"); record["resource_decisions"] = evidence()
        inventory = sanitize(reports, report.with_suffix(".xml")); record.update(inventory); record["executed"] = inventory["tests"]; record["child_exit"] = child.returncode; child = None
        witness_path = report.with_name("asker-reactivation-native-witness.json"); facts = json.loads(witness_path.read_text()) if witness_path.exists() else {}
        try: record["java_witness_complete"] = witness(facts)
        except ValueError:
            if witness_path.exists(): shared.save(witness_path, {"witnessSchemaRejected": True})
            raise
        record["genuine_behavior_red"] = inventory["exact_method_inventory"] and inventory["tests"] == inventory["failures"] == 1 and inventory["errors"] == inventory["skipped"] == 0 and all(facts[key] for key in ["actualJpa", "actualCurrentPlatform", "canonicalAdmin", "actualNativeRead200", "nativeProjectionBoundExceptMailboxCase", "nativeEmailCaseDifference"])
        events = fixture.admin("GET", "/admin-events?resourcePath=accounts/" + account + "&max=100")
        actual = [event for event in events if event.get("resourcePath") == "accounts/" + account and event.get("operationType") == "UPDATE" and event.get("resourceType") == "USER" and event.get("authDetails", {}).get("clientId") == lifecycle.ACTORS["ACCOUNT_ADMIN"][0] and event.get("authDetails", {}).get("userId") == lifecycle.SUBJECTS["ACCOUNT_ADMIN"]]
        record["one_actual_native_effect_event"] = len(actual) == 1 and all(event.get("representation") is None for event in actual)
        record["controls"] = controls; record["installer_identity_checks"] = ProtectedOperationRead.observation.get("installerIdentityChecks", [])
        checks = record["installer_identity_checks"]; record["installer_identity_verified_every_restart"] = len(checks) >= 4 and all(check.get("sameActualEnabledMasterInstallerAndNativeSessionVerified") is True for check in checks)
        record["accepted"] = inventory["accepted"] and record["child_exit"] == 0 and record["java_witness_complete"] and record["one_actual_native_effect_event"] and record["installer_identity_verified_every_restart"] and stage[0] == "RESTORED" and len(controls) == 2
        record["phase"] = "complete"
    except BaseException as error:
        record["exception_category"] = type(error).__name__; record["accepted"] = False; record["failure_frames"] = shared.sanitized_failure_frames(error, catalog)
        if isinstance(error, KeyboardInterrupt): raise
    finally:
        subprocess.run = original_run
        if control is not None: control.shutdown(); control.server_close()
        try:
            OperationFixture.doClassCleanups()
            if getattr(OperationFixture, "tearDown_exceptions", []): record["accepted"] = False; record["cleanup_failure_count"] = len(OperationFixture.tearDown_exceptions)
        except BaseException as error: record["cleanup_category"] = type(error).__name__; record["accepted"] = False
        for key, value in previous_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        record["after"] = snapshot(kc); record["source_stable"] = before == record["after"]
        record["jar_stable"] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        record["compiled_user_stable"] = compiled == {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
        record["accepted"] &= record["source_stable"] and record["jar_stable"] and record["compiled_user_stable"]; shared.save(report, record)
    return 0 if record["accepted"] else 1


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("mode", choices=["snapshot", "native", "finalize"]); parser.add_argument("--keycloak", type=Path, required=True)
    parser.add_argument("--report", type=Path, default=ROOT / "target/asker-reactivation-native/asker-reactivation-native-result.json"); args = parser.parse_args()
    if args.mode == "snapshot": shared.save(args.report.with_name("asker-reactivation-native-before.json"), snapshot(args.keycloak)); return 0
    if args.mode == "native": return native(args.keycloak.resolve(), args.report)
    if not args.report.exists(): shared.save(args.report, {"phase": "not-executed", "executed": 0, "accepted": False, "genuine_behavior_red": False})
    return 0 if json.loads(args.report.read_text()).get("accepted") is True else 1


if __name__ == "__main__": raise SystemExit(main())
