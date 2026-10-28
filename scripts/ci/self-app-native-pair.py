#!/usr/bin/env python3
"""One real public User/native app pair; never writes credentials or raw logs."""
import argparse
import ast
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
import time
import threading
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
KC_COMMIT = "9b575dedb7eefaa509c023042ad534da6d1926cf"
SUITE = "de.caritas.cob.userservice.api.adapters.web.controller.UserSelfAppOtpNativePairProof"
CASE = "actualPublicUserAppLostNativeReplyReplaysOneCredentialAndCommitsOwnedInvite"
EMAIL_CASE = "actualPublicUserEmailLostSentReplyPreservesEarlierOriginalMachineExpiry"
DELETE_CASE = "actualPublicUserDeleteLostNativeReplyReopensOnlyOwnedGateOnce"
CASES = {"app": CASE, "email": EMAIL_CASE, "delete": DELETE_CASE}
IMAGE = "quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992"


def manifest(root):
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    return {n: hashlib.sha256((root / n).read_bytes()).hexdigest() for n in sorted(names) if n and (root / n).is_file()}


def snapshot(kc):
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
    if actual != os.environ.get("GITHUB_SHA"):
        raise ValueError("Actual User checkout differs from immutable run")
    native = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=kc).decode().strip()
    if native != KC_COMMIT:
        raise ValueError("Actual Keycloak checkout differs from immutable native pin")
    return {"user_commit": actual, "keycloak_commit": native, "user_sources": manifest(ROOT), "keycloak_sources": manifest(kc)}


def reviewed_self_operations(kind):
    if kind == "app":
        return ["account.read", "otp.read", "otp.app-setup"]
    if kind == "email":
        return ["account.read", "otp.read", "otp.email-start", "otp.email-finish"]
    if kind == "delete":
        return ["account.read", "otp.read", "otp.app-setup", "otp.delete"]
    raise ValueError("Unknown native pair kind")


def public_self_reads_complete(observed, kind="app"):
    if kind not in CASES:
        raise ValueError("Unknown native pair kind")
    original = (isinstance(observed, list)
            and observed.count({"phase": "public-self-read", "status": 200}) == 1
            and observed.count({"phase": "public-self-read-active", "status": 200}) == 1
            and observed.count({"phase": "native-otp-read-http", "status": 200}) == (3 if kind == "delete" else 2))
    return original and (kind != "delete" or (
        observed.count({"phase": "public-self-read-deleted", "status": 200}) == 1
        and observed.count({"phase": "public-delete-success", "status": 200}) == 1
        and observed.count({"phase": "native-delete-http", "status": 200}) == 2))


def fixture_installer_expired(bearer, now):
    # Fixture scheduling only, never authentication or tested actor authority.
    parts = bearer.split(".")
    if len(parts) != 3:
        raise ValueError("Invalid private fixture installer lifetime")
    claims = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    expiry = claims.get("exp")
    if not isinstance(expiry, int) or isinstance(expiry, bool) or expiry <= 0:
        raise ValueError("Invalid private fixture installer lifetime")
    return expiry <= now + 5


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def sanitize(reports, destination, pair_kind="app"):
    if pair_kind not in CASES: raise ValueError("Unknown native pair kind")
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    clean = ET.Element("testsuite", name=SUITE)
    paths = list(reports.glob("TEST-*.xml"))
    names = []
    locations = []
    valid = len(paths) == 1
    for path in paths:
        suite = ET.parse(path).getroot()
        valid &= suite.get("name") == SUITE
        local = dict.fromkeys(counts, 0)
        for row in suite.findall("testcase"):
            name = row.get("name")
            names.append(name)
            valid &= row.get("classname") == SUITE
            child = ET.SubElement(clean, "testcase", classname=SUITE, name=name or "missing")
            local["tests"] += 1
            for kind, key in [("failure", "failures"), ("error", "errors"), ("skipped", "skipped")]:
                if row.find(kind) is not None:
                    local[key] += 1
                    ET.SubElement(child, kind, message="Sensitive native pair diagnostic omitted")
                    locations.extend(re.findall(r"UserSelfAppOtpNativePairProof\.java:[0-9]+", row.find(kind).text or ""))
        for key, value in local.items():
            valid &= suite.get(key) is None or int(suite.get(key)) == value
            counts[key] += value
    valid &= names == [CASES[pair_kind]]
    accepted = valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)
    for key, value in counts.items():
        clean.set(key, str(value))
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(destination, encoding="utf-8", xml_declaration=True)
    return {**counts, "exact_inventory": valid, "accepted": accepted, "failure_source_locations": sorted(set(locations))}


def delivered_code(messages):
    from email import policy as email_policy
    from email.parser import BytesParser
    if len(messages) != 1: raise ValueError("Exactly one actual native message required")
    message = BytesParser(policy=email_policy.default).parsebytes(messages[0])
    codes = set()
    for part in message.walk():
        if part.get_content_type() in {"text/plain", "text/html"}:
            text = part.get_payload(decode=True).decode(part.get_content_charset() or "utf-8")
            codes.update(re.findall(r"(?<![0-9])[0-9]{6}(?![0-9])", text))
    if len(codes) != 1: raise ValueError("Exactly one actual delivered code required")
    return next(iter(codes))


def native(kc, report):
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Only the fresh isolated Linux hosted job may start native pairing")
    available = int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemAvailable:")))
    if available < 4 * 1024 * 1024:
        raise ValueError("Native pairing requires four GiB headroom and two GiB reserve")
    kind = os.environ.get("PAIR_KIND", "app")
    if kind not in CASES:
        raise ValueError("Unknown actual public pair kind")
    module = kc / "keycloak-image/account-lifecycle-spi"
    otp = kc / "keycloak-image/otp-config-spi"
    jars = {"LIFECYCLE_C_TEST_JAR": module / "target/account-lifecycle-spi-1.0-SNAPSHOT.jar", "OTP_C_TEST_JAR": otp / "target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar"}
    if not all(p.is_file() for p in jars.values()):
        raise ValueError("Both actual built provider JARs are required")
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    compiled_paths = [
        "target/test-classes/" + SUITE.replace(".", "/") + ".class",
        *["target/classes/de/caritas/cob/userservice/api/" + name + ".class" for name in [
            "adapters/web/controller/UserController", "adapters/web/controller/UserTwoFactorAuthControllerDelegate",
            "adapters/keycloak/lifecycle/SelfAppOtpMaintenance", "adapters/keycloak/lifecycle/AccountLifecycleCodec",
            "config/auth/TaskActorTokenBroker", "IdentityManager", "service/accountinvite/AccountInviteService",
            "adapters/web/controller/UserAccountControllerDelegate", "adapters/web/mapping/UserDtoMapper",
            "adapters/keycloak/lifecycle/SelfOtpReadWire",
        ]],
    ]
    if not all((ROOT / name).is_file() for name in compiled_paths):
        raise ValueError("Every actual public compiled User class is required")
    compiled = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in compiled_paths}
    before = snapshot(kc)
    expected = json.loads(report.with_name("self-app-native-before.json").read_text())
    if before != expected:
        raise ValueError("Source changed after the required build snapshot")
    sys.path.insert(0, str(module / "tests"))
    import lifecycle_fixture as lifecycle
    from maintenance_human_fixture import CLIENT, ISSUER, HumanMaintenanceFixture
    if lifecycle.IMAGE != IMAGE:
        raise ValueError("Native fixture image differs from fixed pin")

    class UserPair(HumanMaintenanceFixture, unittest.TestCase):
        @classmethod
        def setUpClass(cls):
            if kind == "email":
                source = otp / "tests/scenario_c_otp_fixture.py"
                spec = importlib.util.spec_from_file_location("pair_mail_fixture", source)
                fixture_module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(fixture_module)
                cls.mail = fixture_module.MailSink(("0.0.0.0", 0), fixture_module.MailSink.Handler)
                cls.mail.messages = []
                cls.addClassCleanup(cls.mail.server_close)
                cls.addClassCleanup(cls.mail.shutdown)
                threading.Thread(target=cls.mail.serve_forever, daemon=True).start()
                # Java receives the actual SMTP code only in memory over this owned loopback
                # fixture. No file, output, receipt, product endpoint or extra runtime actor.
                class CodeHandler(http.server.BaseHTTPRequestHandler):
                    def log_message(self, *args): pass
                    def do_GET(self):
                        if self.path != "/one-delivered-code":
                            self.send_response(404); self.end_headers(); return
                        try: body = delivered_code(cls.mail.messages).encode()
                        except (ValueError, UnicodeError):
                            self.send_response(503); self.end_headers(); return
                        self.send_response(200); self.send_header("Content-Type", "text/plain")
                        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
                cls.code_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), CodeHandler)
                cls.addClassCleanup(cls.code_server.server_close)
                cls.addClassCleanup(cls.code_server.shutdown)
                threading.Thread(target=cls.code_server.serve_forever, daemon=True).start()
            super().setUpClass()

        @classmethod
        def reviewed_human_profile(cls):
            return {"kind": "ASKER", "group": "user", "roles": ["user"], "id": "user-v1"}

        @classmethod
        def reviewed_self_operations(cls):
            return reviewed_self_operations(kind)

        @classmethod
        def extra_launch_arguments(cls):
            return ["-v", str(jars["OTP_C_TEST_JAR"].resolve()) + ":/opt/keycloak/providers/otp-config-spi.jar:ro", "-e", "IDENTITY_ACCOUNT_ADMIN_CLIENT_ID=backend-account-admin", "-e", "ORISO_APP_BASE_URL=https://otp-fixture.internal", "-e", "EMAIL_BRANDING_NAME=OTP Fixture", "-e", "EMAIL_LEGAL_ORGANISATION_NAME=Fixture Operator", "-v", str(kc / "keycloak-image/themes/oriso") + ":/opt/keycloak/themes/oriso:ro", "--add-host", "host.docker.internal:host-gateway"]

    phase = "native-fixture-setup"
    receipt = {"kind": kind, "case": CASES[kind], "phase": phase, "accepted": False, "executed": 0, "genuine_behavior_red": False, "before": before, "jars": hashes, "image": IMAGE, "compiled_user_classes": compiled}
    saved_environment = {key: os.environ.get(key) for key in jars}
    for key, path in jars.items():
        os.environ[key] = str(path.resolve())
    try:
        UserPair.setUpClass()
        fixture = UserPair("runTest")
        fixture.human()
        if kind == "email":
            realm_config = fixture.admin("GET", "")
            realm_config["emailTheme"] = "oriso"
            realm_config["smtpServer"] = {"host": "host.docker.internal", "port": str(UserPair.mail.server_address[1]), "from": "otp-fixture@oriso.org", "auth": "false", "ssl": "false", "starttls": "false"}
            fixture.admin("PUT", "", realm_config)
            assert fixture.admin("GET", "")["smtpServer"] == realm_config["smtpServer"]
            clients = fixture.admin("GET", "/clients?clientId=" + lifecycle.ACTORS["ACCOUNT_ADMIN"][0])
            assert len(clients) == 1
            native_client = clients[0]
            native_client["attributes"] = {**native_client.get("attributes", {}), "access.token.lifespan": "90"}
            fixture.admin("PUT", "/clients/" + native_client["id"], native_client)
            assert fixture.admin("GET", "/clients/" + native_client["id"])["attributes"]["access.token.lifespan"] == "90"
        assert fixture.creation_input["identity"]["scope"]["registrationKind"] == "ASKER"
        assert fixture.admin("GET", "/users/" + fixture.account_id + "/credentials")
        assert not any(c["type"] == "otp" for c in fixture.admin("GET", "/users/" + fixture.account_id + "/credentials"))
        otp_policy = fixture.admin("GET", "")
        assert otp_policy["otpPolicyType"] == "totp"
        assert otp_policy["otpPolicyAlgorithm"] == "HmacSHA1"
        assert otp_policy["otpPolicyDigits"] == 6
        assert otp_policy["otpPolicyPeriod"] == 30
        machine = fixture.token("ACCOUNT_ADMIN")
        assert lifecycle.call(fixture.base, "GET", "/admin/realms/" + lifecycle.REALM + "/users/" + fixture.account_id, token=machine)[0] == 403
        receipt["native_preconditions"] = {"actual_user_registration": True, "current_user_group_role": True, "actual_native_human_session": True, "canonical_admin3": True, "stock_native_denied": True, "no_app_credential": True, "actual_totp_sha1_6_30_policy": True, "reviewed_otp_read_operation": "otp.read" in UserPair.reviewed_self_operations()}
        phase = "actual-public-java-native-pair"
        environment = dict(os.environ)
        environment.update({"PAIR_KIND": kind, "PAIR_NATIVE_BASE": fixture.base, "PAIR_REALM": lifecycle.REALM, "PAIR_REALM_ID": fixture.realm_id, "PAIR_ACCOUNT_ID": fixture.account_id, "PAIR_USERNAME": fixture.creation_input["account"]["username"], "PAIR_PASSWORD": fixture.creation_input["account"]["password"], "PAIR_HUMAN_CLIENT": CLIENT, "PAIR_CONTEXT_KEY_B64": base64.b64encode(lifecycle.CONTEXT_KEY).decode(), "PAIR_WITNESS": str(report.with_name("self-app-native-witness.json").resolve())})
        if kind == "email":
            environment["PAIR_MAIL_CALLBACK"] = "http://127.0.0.1:" + str(UserPair.code_server.server_address[1]) + "/one-delivered-code"
        for actor, (client, roles) in lifecycle.ACTORS.items():
            environment.update({"PAIR_" + actor + "_CLIENT": client, "PAIR_" + actor + "_SECRET": "synthetic-lifecycle-" + actor, "PAIR_" + actor + "_SUBJECT": lifecycle.SUBJECTS[actor]})
        reports = ROOT / "target/surefire-reports"
        for path in reports.glob("TEST-*.xml"):
            path.unlink()
        child = subprocess.run(["mvn", "-B", "-ntp", "-q", "surefire:test", "-Dtest=" + SUITE + "#" + CASES[kind], "-DfailIfNoTests=true", "-Dsurefire.failIfNoSpecifiedTests=true", "-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2"], cwd=ROOT, env=environment, capture_output=True, timeout=180)
        result = sanitize(reports, report.with_suffix(".xml"), kind)
        receipt.update(result)
        witness = report.with_name("self-app-native-witness.json")
        if witness.is_file():
            observed = json.loads(witness.read_text())
            if not isinstance(observed, list) or len(observed) > 100 or any(set(row) != {"phase", "status"} or not re.fullmatch(r"[a-z-]{1,50}", row["phase"]) or not isinstance(row["status"], int) or row["status"] < 0 or row["status"] > 599 for row in observed):
                raise ValueError("Invalid nonsecret native pair witness")
            receipt["public_phases"] = observed
        receipt["executed"] = result["tests"]
        receipt["java_exit"] = child.returncode
        receipt["public_self_read_before_and_after"] = public_self_reads_complete(receipt.get("public_phases"), kind)
        receipt["accepted"] = result["accepted"] and child.returncode == 0 and receipt["public_self_read_before_and_after"]
        phase = "post-java-native-inspection"
        expired_installer = fixture_installer_expired(fixture.installer, time.time())
        receipt["fixture_installer_actual_expiry_requires_refresh"] = expired_installer
        if expired_installer:
            status, issued = lifecycle.call(fixture.base, "POST", "/realms/master/protocol/openid-connect/token",
                {"client_id": "admin-cli", "grant_type": "password", "username": "synthetic-installer",
                 "password": lifecycle.INSTALLER}, form=True)
            receipt["fixture_installer_refresh_http_status"] = status
            assert status == 200
            fixture.installer = issued["access_token"]
            assert not fixture_installer_expired(fixture.installer, time.time())
        status, credentials = lifecycle.call(fixture.base, "GET",
            "/admin/realms/" + lifecycle.REALM + "/users/" + fixture.account_id + "/credentials",
            token=fixture.installer)
        receipt["native_credential_inspection_http_status"] = status
        assert status == 200

        if kind == "email":
            receipt["single_native_mail_credential"] = len([c for c in credentials if c["type"] == "MAIL_OTP_CM"]) == 1
            receipt["single_actual_smtp"] = len(UserPair.mail.messages) == 1
            status, native_user = lifecycle.call(fixture.base, "GET",
                "/admin/realms/" + lifecycle.REALM + "/users/" + fixture.account_id,
                token=fixture.installer)
            receipt["native_user_inspection_http_status"] = status
            assert status == 200
            mail = [c for c in credentials if c["type"] == "MAIL_OTP_CM"]
            receipt["native_email_finished"] = native_user.get("email") == "pair-pending@oriso.org" and native_user.get("emailVerified") is True
            receipt["native_mail_active"] = len(mail) == 1 and json.loads(mail[0]["credentialData"])["active"] is True
            receipt["accepted"] &= receipt["single_native_mail_credential"] and receipt["single_actual_smtp"] and receipt["native_email_finished"] and receipt["native_mail_active"]
        elif kind == "delete":
            receipt["native_otp_credentials_absent_after_delete"] = not any(c["type"] in {"otp", "MAIL_OTP_CM"} for c in credentials)
            receipt["accepted"] &= receipt["native_otp_credentials_absent_after_delete"]
        else:
            receipt["single_native_app_credential"] = len([c for c in credentials if c["type"] == "otp"]) == 1
            receipt["accepted"] &= receipt["single_native_app_credential"]
        # Never persist child output: it may contain JWTs, codes, passwords or native responses.
        child = None
        phase = "complete"
    except BaseException as error:
        receipt["exception_category"] = type(error).__name__
        receipt["accepted"] = False
        if isinstance(error, KeyboardInterrupt):
            raise
    finally:
        receipt["phase"] = phase
        receipt["native_fixture_phases"] = UserPair.phase_receipts
        try:
            UserPair.doClassCleanups()
            if getattr(UserPair, "tearDown_exceptions", []):
                receipt["cleanup_failure_count"] = len(UserPair.tearDown_exceptions)
                receipt["accepted"] = False
        except BaseException as error:
            receipt["cleanup_category"] = type(error).__name__
            receipt["accepted"] = False
        for key, value in saved_environment.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        receipt["after"] = snapshot(kc)
        receipt["source_stable"] = before == receipt["after"]
        receipt["jar_stable"] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        receipt["compiled_user_stable"] = compiled == {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in compiled_paths}
        receipt["accepted"] &= receipt["source_stable"] and receipt["jar_stable"] and receipt["compiled_user_stable"]
        save(report, receipt)
    return 0 if receipt["accepted"] else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["snapshot", "native", "finalize"])
    parser.add_argument("--keycloak", type=Path, required=True)
    parser.add_argument("--report", type=Path, default=ROOT / "target/self-app-native/self-app-native-result.json")
    args = parser.parse_args()
    if args.mode == "snapshot":
        save(args.report.with_name("self-app-native-before.json"), snapshot(args.keycloak))
        return 0
    if args.mode == "native":
        return native(args.keycloak.resolve(), args.report)
    if not args.report.exists():
        save(args.report, {"phase": "not-executed", "executed": 0, "accepted": False, "genuine_behavior_red": False})
    return 0 if json.loads(args.report.read_text()).get("accepted") is True else 1


if __name__ == "__main__":
    raise SystemExit(main())
