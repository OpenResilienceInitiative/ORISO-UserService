#!/usr/bin/env python3
"""Exact owning SQL/OTP adapter/native pair; emits no bearer, password, mail or raw logs."""
import argparse
import base64
import hashlib
import http.server
import importlib.util
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
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
KC_COMMIT = "54c44dee3050dd483305fb68d1219b24ec40e7b8"
SUITE = "de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrapNativePairProof"
CASE = "actualOwnedRecipientCompletesOtpAfterLostNativeReplyWithoutEnablingAccount"
IMAGE = "quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992"

def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")

def manifest(root):
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    return {name: hashlib.sha256((b"symlink\0" + os.readlink(root / name).encode()) if (root / name).is_symlink() else (root / name).read_bytes()).hexdigest() for name in sorted(paths) if name and ((root / name).is_file() or (root / name).is_symlink())}

def snapshot(kc):
    current = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
    native = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=kc).decode().strip()
    if current != os.environ.get("GITHUB_SHA") or native != KC_COMMIT:
        raise ValueError("Immutable paired source checkout mismatch")
    return {"user_commit": current, "keycloak_commit": native, "user_sources": manifest(ROOT), "keycloak_sources": manifest(kc)}

def sanitize(reports, output):
    clean = ET.Element("testsuite", name=SUITE)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob("TEST-*.xml"))
    names, locations, valid = [], [], len(files) == 1
    for path in files:
        suite = ET.parse(path).getroot()
        valid &= suite.get("name") == SUITE
        for row in suite.findall("testcase"):
            names.append(row.get("name"))
            valid &= row.get("classname") == SUITE
            child = ET.SubElement(clean, "testcase", classname=SUITE, name=row.get("name") or "missing")
            totals["tests"] += 1
            for tag, count in [("failure", "failures"), ("error", "errors"), ("skipped", "skipped")]:
                found = row.find(tag)
                if found is not None:
                    totals[count] += 1
                    ET.SubElement(child, tag, message="Sensitive paired diagnostic omitted")
                    locations += re.findall(r"ProtectedOtpBootstrapNativePairProof\.java:[0-9]+", found.text or "")
        for name, number in totals.items():
            valid &= suite.get(name) is None or int(suite.get(name)) == number
    valid &= names == [CASE]
    for name, number in totals.items():
        clean.set(name, str(number))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding="utf-8", xml_declaration=True)
    return {**totals, "exact_method_inventory": valid, "failure_locations": sorted(set(locations)), "accepted": valid and totals == dict(tests=1, failures=0, errors=0, skipped=0)}

def delivered_code(messages):
    import email
    if len(messages) != 1:
        raise ValueError("Exactly one real SMTP message required")
    codes = set()
    for part in email.message_from_bytes(messages[0]).walk():
        if part.get_content_type() in {"text/plain", "text/html"}:
            text = part.get_payload(decode=True).decode(part.get_content_charset() or "utf-8")
            codes.update(re.findall(r"(?<![0-9])[0-9]{6}(?![0-9])", text))
    if len(codes) != 1:
        raise ValueError("Exactly one real delivered code required")
    return next(iter(codes))

def native(kc, report):
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Fresh Linux hosted native fixture required")
    available = int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemAvailable:")))
    if available < 4 * 1024 * 1024:
        raise ValueError("Four GiB headroom and two GiB reserve required")
    kind = os.environ.get("PAIR_KIND")
    if kind not in {"app", "email"}:
        raise ValueError("Closed pair kind required")
    module = kc / "keycloak-image/account-lifecycle-spi"
    otp = kc / "keycloak-image/otp-config-spi"
    jars = {"LIFECYCLE_C_TEST_JAR": module / "target/account-lifecycle-spi-1.0-SNAPSHOT.jar", "OTP_C_TEST_JAR": otp / "target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar"}
    if not all(path.is_file() for path in jars.values()):
        raise ValueError("Both actual paired providers required")
    hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in jars.items()}
    before = snapshot(kc)
    if before != json.loads(report.with_name("protected-otp-native-before.json").read_text()):
        raise ValueError("Paired source changed during builds")
    compiled_paths = ["target/test-classes/" + SUITE.replace(".", "/") + ".class", *["target/classes/de/caritas/cob/userservice/api/" + name + ".class" for name in ["adapters/keycloak/lifecycle/ProtectedOtpBootstrap", "adapters/keycloak/lifecycle/ProtectedAccountCodec", "adapters/keycloak/lifecycle/KeycloakProtectedAccounts", "adapters/keycloak/lifecycle/JdbcProtectedInvitationAcceptance", "adapters/keycloak/lifecycle/JdbcProtectedInvitationJournal", "adapters/keycloak/lifecycle/AccountLifecycleCodec", "config/auth/TaskActorTokenBroker"]]]
    if not all((ROOT / name).is_file() for name in compiled_paths):
        raise ValueError("Actual compiled paired User classes required")
    compiled = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in compiled_paths}
    sys.path.insert(0, str(module / "tests"))
    import lifecycle_fixture as lifecycle
    from protected_otp_bootstrap import BootstrapFixture
    if lifecycle.IMAGE != IMAGE:
        raise ValueError("Pinned native image mismatch")
    saved = {name: os.environ.get(name) for name in jars}
    for name, path in jars.items():
        os.environ[name] = str(path.resolve())
    receipt = {"kind": kind, "accepted": False, "executed": 0, "before": before, "jars": hashes, "image": IMAGE, "compiled_user_classes": compiled}
    phase = "native-fixture-setup"
    code_server = None
    try:
        BootstrapFixture.setUpClass()
        fixture = BootstrapFixture("runTest")
        for actor, (client, _) in lifecycle.ACTORS.items():
            links = fixture.admin("GET", "/clients?clientId=" + client)
            assert len(links) == 1
            assert lifecycle.SUBJECTS[actor] == fixture.admin("GET", "/clients/" + links[0]["id"] + "/service-account-user")["id"]
        scope = fixture.scope()
        scope["inviteId"] = "73"
        scope["validUntil"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 600))
        approval_id = str(uuid.uuid4())
        status, approved = lifecycle.call(fixture.base, "PUT", fixture.endpoint(approval_id), {"scope": scope}, fixture.human(record=False))
        assert status == 201 and approved["state"] == "AUTHORIZED"
        if kind == "email":
            realm_config = fixture.admin("GET", "")
            realm_config["emailTheme"] = "oriso"
            realm_config["smtpServer"] = {"host": "host.docker.internal", "port": str(BootstrapFixture.mail.server_address[1]), "from": "otp-fixture@oriso.org", "auth": "false", "ssl": "false", "starttls": "false"}
            fixture.admin("PUT", "", realm_config)
            assert fixture.admin("GET", "")["smtpServer"] == realm_config["smtpServer"]
            class CodeHandler(http.server.BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass
                def do_GET(self):
                    if self.path != "/one-delivered-code":
                        self.send_response(404); self.end_headers(); return
                    try:
                        body = delivered_code(BootstrapFixture.mail.messages).encode()
                    except (ValueError, UnicodeError):
                        self.send_response(503); self.end_headers(); return
                    self.send_response(200); self.send_header("Content-Type", "text/plain"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
            code_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), CodeHandler)
            threading.Thread(target=code_server.serve_forever, daemon=True).start()
        receipt["native_preconditions"] = {"five_exact_links": True, "actual_human_approval": True, "smtp_configured": kind == "email", "creator_issuance_double": True}
        environment = dict(os.environ)
        environment.update({"PAIR_KIND": kind, "PAIR_NATIVE_BASE": fixture.base, "PAIR_REALM": lifecycle.REALM, "PAIR_REALM_ID": fixture.realm_id, "PAIR_CONTEXT_KEY_B64": base64.b64encode(lifecycle.CONTEXT_KEY).decode(), "PAIR_APPROVAL_REQUEST": lifecycle.canonical({"scope": scope}).decode(), "PAIR_APPROVAL_RESPONSE": lifecycle.canonical(approved).decode(), "PAIR_CREATE_BEARER": fixture.token("ACCOUNT_CREATE"), "PAIR_WITNESS": str(report.with_name("protected-otp-native-witness.json").resolve()), "PAIR_HTTP_WITNESS": str(report.with_name("protected-otp-native-http.json").resolve())})
        if code_server:
            environment["PAIR_MAIL_CALLBACK"] = "http://127.0.0.1:" + str(code_server.server_address[1]) + "/one-delivered-code"
        for actor, (client, _) in lifecycle.ACTORS.items():
            environment.update({"PAIR_" + actor + "_CLIENT": client, "PAIR_" + actor + "_SECRET": "synthetic-lifecycle-" + actor, "PAIR_" + actor + "_SUBJECT": lifecycle.SUBJECTS[actor]})
        reports = ROOT / "target/surefire-reports"
        for path in reports.glob("TEST-*.xml"):
            path.unlink()
        phase = "actual-java-sql-native-pair"
        child = subprocess.run(["mvn", "-B", "-ntp", "-q", "surefire:test", "-Dtest=" + SUITE + "#" + CASE, "-DfailIfNoTests=true", "-Dsurefire.failIfNoSpecifiedTests=true", "-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2"], cwd=ROOT, env=environment, capture_output=True, timeout=180)
        result = sanitize(reports, report.with_suffix(".xml"))
        receipt.update(result)
        receipt["executed"] = result["tests"]
        receipt["java_exit"] = child.returncode
        receipt["accepted"] = result["accepted"] and child.returncode == 0
        witness = report.with_name("protected-otp-native-witness.json")
        if witness.is_file():
            observed = json.loads(witness.read_text())
            expected = {"ownerSqlAndNativePairComplete", "oneLostNativeReplyRecovered", "privateCompletionVerified", "creatorIssuanceDouble"}
            assert set(observed) == expected and all(value is True for value in observed.values())
            receipt["consumer_observations"] = observed
        receipt["accepted"] &= receipt.get("consumer_observations") is not None
        http_witness = report.with_name("protected-otp-native-http.json")
        if http_witness.is_file():
            observed_http = json.loads(http_witness.read_text())
            assert isinstance(observed_http, list) and len(observed_http) <= 100
            assert all(set(row) == {"phase", "status"} and row["phase"] in {"token", "stock-denial", "create", "owned-read", "prepare", "app", "email-start", "email-finish"} and type(row["status"]) is int and 100 <= row["status"] <= 599 for row in observed_http)
            receipt["http_observations"] = observed_http
        phase = "post-java-native-inspection"
        # Installer-only fresh fixture token; tested actor expiry/authority is never refreshed here.
        status, installer = lifecycle.call(fixture.base, "POST", "/realms/master/protocol/openid-connect/token", {"client_id": "admin-cli", "grant_type": "password", "username": "synthetic-installer", "password": lifecycle.INSTALLER}, form=True)
        assert status == 200
        fixture.installer = installer["access_token"]
        username = "enc." + base64.b32encode(scope["recipient"].encode()).decode().replace("=", ".")
        users = fixture.admin("GET", "/users?username=" + __import__('urllib.parse', fromlist=['quote']).quote(username.lower(), safe='') + "&exact=true")
        assert len(users) == 1
        user = users[0]
        credentials = fixture.admin("GET", "/users/" + user["id"] + "/credentials")
        selected = [row for row in credentials if row["type"] == ("MAIL_OTP_CM" if kind == "email" else "otp")]
        receipt["native_account_still_disabled"] = user.get("enabled") is False
        receipt["one_native_enrollment"] = len(selected) == 1
        receipt["actual_smtp_count"] = len(BootstrapFixture.mail.messages)
        receipt["native_mail_active"] = kind != "email" or (len(selected) == 1 and json.loads(selected[0]["credentialData"])["active"] is True)
        receipt["accepted"] &= receipt["native_account_still_disabled"] and receipt["one_native_enrollment"] and receipt["native_mail_active"] and receipt["actual_smtp_count"] == (1 if kind == "email" else 0)
        phase = "complete"
    except BaseException as error:
        receipt["exception_category"] = type(error).__name__
        receipt["accepted"] = False
        if isinstance(error, KeyboardInterrupt):
            raise
    finally:
        receipt["phase"] = phase
        try:
            if code_server:
                code_server.shutdown(); code_server.server_close()
            BootstrapFixture.doClassCleanups()
            assert not getattr(BootstrapFixture, "tearDown_exceptions", [])
        except BaseException as error:
            receipt["cleanup_category"] = type(error).__name__
            receipt["accepted"] = False
        for name, value in saved.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        receipt["after"] = snapshot(kc)
        receipt["source_stable"] = before == receipt["after"]
        receipt["jar_stable"] = hashes == {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in jars.items()}
        receipt["compiled_user_stable"] = compiled == {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in compiled_paths}
        receipt["accepted"] &= receipt["source_stable"] and receipt["jar_stable"] and receipt["compiled_user_stable"]
        save(report, receipt)
    if not receipt["accepted"]:
        raise SystemExit("Native protected pair did not satisfy every required gate; see sanitized evidence")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["snapshot", "native", "finalize"])
    parser.add_argument("--keycloak", type=Path, required=True)
    args = parser.parse_args()
    report = ROOT / "target/protected-otp-native/protected-otp-native-pair.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    if args.mode == "snapshot":
        save(report.with_name("protected-otp-native-before.json"), snapshot(args.keycloak.resolve()))
    elif args.mode == "native":
        native(args.keycloak.resolve(), report)
    else:
        value = json.loads(report.read_text()) if report.is_file() else {"accepted": False, "executed": 0, "phase": "fixture-or-build-incomplete"}
        if value.get("accepted") is not True or value.get("executed") != 1:
            raise SystemExit("Missing or incomplete actual native pair proof")

if __name__ == "__main__":
    main()
