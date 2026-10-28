#!/usr/bin/env python3
"""Exact public protected service/JPA/native pair; emits no bearer, password, mail or raw logs."""
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
KC_COMMIT = "9b575dedb7eefaa509c023042ad534da6d1926cf"
SUITE = "de.caritas.cob.userservice.api.service.accountinvite.ProtectedAdminPublicNativePairProof"
CASE = "realPublicPlatformInvitationStagesJpaAndCompletesNativeAppBeforeAcknowledgingActivation"
TENANT_CASE = "realPublicTenantInvitationStagesJpaAndCompletesNativeAppBeforeAcknowledgingActivation"
EMAIL_CASE = "realPublicPlatformInvitationStagesJpaAndCompletesNativeEmailBeforeAcknowledgingActivation"
TENANT_EMAIL_CASE = "realPublicTenantInvitationStagesJpaAndCompletesNativeEmailBeforeAcknowledgingActivation"
LOST_COMMIT_CASE = "realPublicLostNativeCommitReplyReconcilesSavedReadyWithoutRepeatingActivation"
RESERVED_CASE = "realPublicReservedTenantInvitationVerifiesHeldTenantBeforeNativeAppActivation"
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

def sanitize(reports, output, expected_case=CASE):
    if expected_case not in {CASE, TENANT_CASE, EMAIL_CASE, TENANT_EMAIL_CASE, LOST_COMMIT_CASE, RESERVED_CASE}:
        raise ValueError("Unknown public native case")
    clean = ET.Element("testsuite", name=SUITE)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob("TEST-*.xml"))
    names, locations, frames, spring_frames, types, valid = [], [], [], [], [], len(files) == 1
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
                    locations += re.findall(r"ProtectedAdminPublicNativePairProof\.java:[0-9]+", found.text or "")
                    frames += closed_failure_frames((found.text or "").encode(), b"")
                    spring_frames += closed_spring_failure_frames(found.text or "")
                    category = found.get("type", "")
                    if len(category) <= 255 and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", category):
                        types.append(category)
                    for category in re.findall(r"Caused by: ([A-Za-z_$][A-Za-z0-9_$.]+):", found.text or ""):
                        if len(category) <= 255 and (category.startswith(("java.", "javax.", "jakarta.", "org.springframework.", "org.hibernate.", "de.caritas.")) or category == "org.opentest4j.AssertionFailedError"):
                            types.append(category)
        for name, number in totals.items():
            valid &= suite.get(name) is None or int(suite.get(name)) == number
    valid &= names == [expected_case]
    for name, number in totals.items():
        clean.set(name, str(number))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding="utf-8", xml_declaration=True)
    return {**totals, "exact_method_inventory": valid, "failure_locations": sorted(set(locations)), "closed_source_failure_frames": sorted(set(frames))[:50], "closed_spring_failure_frames": list(dict.fromkeys(spring_frames))[:8], "failure_types": sorted(set(types))[:10], "accepted": valid and totals == dict(tests=1, failures=0, errors=0, skipped=0)}

def registration_status(stdout):
    lines = [line for line in stdout.decode("utf-8", errors="replace").splitlines()
             if line.startswith("PROTECTED_PUBLIC_REGISTRATION_STATUS=")]
    if not lines:
        return None
    if len(lines) != 1 or not re.fullmatch(r"PROTECTED_PUBLIC_REGISTRATION_STATUS=[1-5][0-9]{2}", lines[0]):
        raise ValueError("Invalid closed registration status observation")
    return int(lines[0].split("=", 1)[1])

def checked_login_observation(value):
    categories = {"ACCEPTED", "ACCOUNT_NOT_FULLY_SET_UP", "INVALID_USER_CREDENTIALS", "MISSING_TOTP", "UNRECOGNIZED"}
    if (not isinstance(value, dict) or set(value) != {"status", "category", "sameTotpAsEnrollment"}
            or type(value["status"]) is not int or not 100 <= value["status"] <= 599
            or value["category"] not in categories or type(value["sameTotpAsEnrollment"]) is not bool
            or (value["status"] == 200) != (value["category"] == "ACCEPTED")):
        raise ValueError("Invalid closed native login observation")
    return dict(value)

def checked_email_login_observation(value):
    if (not isinstance(value, dict) or set(value) != {"challengeStatus", "challengeType", "acceptedStatus"}
            or value["challengeType"] != "EMAIL"
            or any(type(value[key]) is not int or not 100 <= value[key] <= 599
                for key in ["challengeStatus", "acceptedStatus"])):
        raise ValueError("Invalid closed native email login observation")
    return dict(value)

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

def closed_failure_frames(stdout, stderr):
    # Read captured output only in memory; export source locations, never messages or SQL.
    text = (stdout + b"\n" + stderr).decode("utf-8", errors="replace")
    names = {"JdbcProtectedAccountActivation", "JdbcProtectedInvitationJournal",
        "JdbcProtectedInvitationAcceptance", "ProtectedAccountCodec", "ProtectedOtpBootstrap",
        "ProtectedAdminOnboardingService", "TenantAdminOnboardingController",
        "AccountInactivityService", "AccountInactivityEnrollmentService",
        "TenantCreationClient", "ReservedTenantBootstrap", "ProtectedPublicTenantFixture"}
    frames = re.findall(r"\b([A-Za-z]+)\.java:([0-9]{1,5})\b", text)
    return sorted({name + ".java:" + line for name, line in frames if name in names})[:50]

def closed_spring_failure_frames(text):
    frames = re.findall(r"(?m)^\s*at (org\.springframework\.[A-Za-z0-9_$.]+)\.([A-Za-z0-9_$]+)\(([A-Za-z0-9_$]+\.java):([0-9]{1,5})\)", text)
    return list(dict.fromkeys(name + "#" + method + "@" + source + ":" + line for name, method, source, line in frames))[:8]

def native(kc, report):
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Fresh Linux hosted native fixture required")
    available = int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemAvailable:")))
    if available < 4 * 1024 * 1024:
        raise ValueError("Four GiB headroom and two GiB reserve required")
    kind = os.environ.get("PAIR_KIND", "app")
    role = os.environ.get("PAIR_ROLE", "PLATFORM_ADMIN")
    if kind not in {"app", "email"} or role not in {"PLATFORM_ADMIN", "TENANT_ADMIN"}:
        raise ValueError("Only the four fixed protected onboarding flows are allowed")
    case = {("PLATFORM_ADMIN", "app"): CASE, ("TENANT_ADMIN", "app"): TENANT_CASE,
        ("PLATFORM_ADMIN", "email"): EMAIL_CASE, ("TENANT_ADMIN", "email"): TENANT_EMAIL_CASE}[(role, kind)]
    scenario = os.environ.get("PAIR_SCENARIO", "happy")
    if (scenario not in {"happy", "lost-commit", "reserved-new"}
            or (scenario == "lost-commit" and (role, kind) != ("PLATFORM_ADMIN", "app"))
            or (scenario == "reserved-new" and (role, kind) != ("TENANT_ADMIN", "app"))):
        raise ValueError("Unknown or mismatched fixed terminal reconciliation scenario")
    if scenario == "lost-commit":
        case = LOST_COMMIT_CASE
    elif scenario == "reserved-new":
        case = RESERVED_CASE
    expected_tenant = 0 if role == "PLATFORM_ADMIN" else 17
    module = kc / "keycloak-image/account-lifecycle-spi"
    otp = kc / "keycloak-image/otp-config-spi"
    jars = {"LIFECYCLE_C_TEST_JAR": module / "target/account-lifecycle-spi-1.0-SNAPSHOT.jar", "OTP_C_TEST_JAR": otp / "target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar"}
    if not all(path.is_file() for path in jars.values()):
        raise ValueError("Both actual paired providers required")
    hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in jars.items()}
    before = snapshot(kc)
    if before != json.loads(report.with_name("protected-public-native-before.json").read_text()):
        raise ValueError("Paired source changed during builds")
    compiled_paths = ["target/test-classes/" + SUITE.replace(".", "/") + ".class", *["target/classes/de/caritas/cob/userservice/api/" + name + ".class" for name in ["adapters/keycloak/lifecycle/ProtectedOtpBootstrap", "adapters/keycloak/lifecycle/ProtectedAccountCodec", "adapters/keycloak/lifecycle/KeycloakProtectedAccounts", "adapters/keycloak/lifecycle/JdbcProtectedInvitationAcceptance", "adapters/keycloak/lifecycle/JdbcProtectedInvitationJournal", "adapters/keycloak/lifecycle/AccountLifecycleCodec", "config/auth/TaskActorTokenBroker", "service/accountinvite/AccountInviteService",
        "service/accountinvite/ProtectedInvitationIssuance", "service/accountinvite/ProtectedInvitationIssuanceConfiguration",
        "service/accountinvite/onboarding/ProtectedAdminOnboardingService", "adapters/keycloak/lifecycle/JdbcProtectedAccountActivation",
        "adapters/web/controller/TenantAdminOnboardingController", "service/accountinvite/onboarding/ProtectedAdminRegistrationBodyAdvice"]]]
    compiled_paths += ["target/test-classes/de/caritas/cob/userservice/api/service/accountinvite/ProtectedPublicTenantFixture.class",
        "target/classes/de/caritas/cob/userservice/api/service/accountinvite/onboarding/TenantCreationClient.class",
        "target/classes/de/caritas/cob/userservice/api/service/accountinvite/onboarding/ReservedTenantBootstrap.class"]
    if not all((ROOT / name).is_file() for name in compiled_paths):
        raise ValueError("Actual compiled paired User classes required")
    compiled = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in compiled_paths}
    sys.path.insert(0, str(module / "tests"))
    import lifecycle_fixture as lifecycle
    from protected_otp_bootstrap import BootstrapFixture as ExistingBootstrapFixture

    class BootstrapFixture(ExistingBootstrapFixture):
        @classmethod
        def prepare_installation(cls, helper, configuration):
            if role == "PLATFORM_ADMIN":
                return configuration
            parent = helper.group_id("/protected-admins")
            helper.admin("POST", "/groups/" + parent + "/children", {"name": "tenant-admins"})
            cls.tenant_group_id = helper.group_id("/protected-admins/tenant-admins")
            tenant_role = helper.admin("GET", "/roles/tenant-admin")
            helper.admin("POST", "/groups/" + cls.tenant_group_id + "/role-mappings/realm", [tenant_role])
            profile = json.loads(json.dumps(configuration["profiles"][0]))
            profile.update(id="tenant-v1", kind="TENANT_ADMIN", tenantModes=["EXISTING"],
                groupId=cls.tenant_group_id, groupPath="/protected-admins/tenant-admins", realmRoles=["tenant-admin"])
            if scenario == "reserved-new":
                profile["tenantModes"] = ["EXISTING", "RESERVED_NEW"]
            configuration["profiles"].append(profile)
            configuration["bindings"][0]["protectedProfileIds"].append("tenant-v1")
            configuration["bindings"][0]["protectedProfileIds"].sort()
            return configuration
    if lifecycle.IMAGE != IMAGE:
        raise ValueError("Pinned native image mismatch")
    saved = {name: os.environ.get(name) for name in jars}
    for name, path in jars.items():
        os.environ[name] = str(path.resolve())
    receipt = {"kind": kind, "target_role": role, "scenario": scenario, "accepted": False, "executed": 0, "before": before, "jars": hashes, "image": IMAGE, "compiled_user_classes": compiled}
    phase = "native-fixture-setup"
    code_server = None
    try:
        BootstrapFixture.setUpClass()
        fixture = BootstrapFixture("runTest")
        actual = json.loads(subprocess.check_output(["docker", "inspect", fixture.name]))[0]
        native_env = dict(value.split("=", 1) for value in actual["Config"]["Env"])
        actual_jar = subprocess.check_output(["docker", "exec", fixture.name, "sha256sum", "/opt/keycloak/providers/account-lifecycle-spi.jar"], text=True).split()[0]
        if (actual["Config"]["Image"] != IMAGE or actual["HostConfig"]["Memory"] != 1024 ** 3
                or actual["HostConfig"]["NanoCpus"] != 2 * 10 ** 9
                or "-Xmx512m" not in native_env.get("JAVA_OPTS_APPEND", "")
                or actual["Config"]["Labels"].get("oriso-protected-receiver-test") != "true"
                or actual_jar != hashes["LIFECYCLE_C_TEST_JAR"]):
            raise ValueError("Actual native image/provider/owned resource limits mismatch")
        receipt["runtime"] = {"actualPinnedImage": True, "actualProviderSHA256": actual_jar,
            "hardBytes": 1024 ** 3, "cpuCount": 2, "heapMiB": 512,
            "ownedLabelVerified": True, "twoSequentialBoots": True}
        for actor, (client, _) in lifecycle.ACTORS.items():
            links = fixture.admin("GET", "/clients?clientId=" + client)
            assert len(links) == 1
            assert lifecycle.SUBJECTS[actor] == fixture.admin("GET", "/clients/" + links[0]["id"] + "/service-account-user")["id"]
        from protected_public_login import install, serve_codes
        receipt["login_flow"] = install(fixture, ROOT / "tests/fixtures/protected-public-login-flow-7ed.json")
        if kind == "email":
            code_server = serve_codes(fixture.mail, delivered_code)
        native_policy = fixture.admin("GET")
        assert native_policy["otpPolicyCodeReusable"] is False
        assert native_policy["otpPolicyPeriod"] == 30 and native_policy["otpPolicyDigits"] == 6
        assert native_policy["otpPolicyAlgorithm"] == "HmacSHA1"
        human_bearer = fixture.human(record=False)
        human_claims = json.loads(base64.urlsafe_b64decode(human_bearer.split(".")[1] + "=" * (-len(human_bearer.split(".")[1]) % 4)))
        assert human_claims["azp"] == "protected-human-web"
        recipient = "public-" + uuid.uuid4().hex + "@fixture.invalid"
        receipt["native_preconditions"] = {"five_exact_links": True,
            "actual_current_human_userinfo_session_client_tenant_grants": True,
            "real_checked_creator_and_admin_broker": True, "actual_single_use_totp_policy": True}
        environment = dict(os.environ)
        environment.update({"PAIR_KIND": kind, "PAIR_ROLE": role, "PAIR_SCENARIO": scenario, "PAIR_NATIVE_BASE": fixture.base,
            "PAIR_REALM": lifecycle.REALM, "PAIR_REALM_ID": fixture.realm_id,
            "PAIR_CONTEXT_KEY_B64": base64.b64encode(lifecycle.CONTEXT_KEY).decode(),
            "PAIR_RECIPIENT": recipient, "PAIR_HUMAN_SUBJECT": fixture.human_subject,
            "PAIR_HUMAN_CLIENT": human_claims["azp"],
            "PAIR_HUMAN_TOKEN": human_bearer,
            "PAIR_WITNESS": str(report.with_name("protected-public-native-witness.json").resolve()),
            "PAIR_HTTP_WITNESS": str(report.with_name("protected-public-native-http.json").resolve()),
            "PAIR_LOGIN_WITNESS": str(report.with_name("protected-public-native-login.json").resolve()),
            "PAIR_FRESH_LOGIN_WITNESS": str(report.with_name("protected-public-native-fresh-login.json").resolve()),
            "PAIR_EMAIL_LOGIN_WITNESS": str(report.with_name("protected-public-native-email-login.json").resolve()),
            "PAIR_TERMINAL_WITNESS": str(report.with_name("protected-public-native-terminal.json").resolve()),
            "PAIR_TENANT_WITNESS": str(report.with_name("protected-public-native-tenant.json").resolve())})
        if code_server:
            environment["PAIR_MAIL_CODES"] = "http://127.0.0.1:" + str(code_server.server_address[1])
        for actor, (client, _) in lifecycle.ACTORS.items():
            environment.update({"PAIR_" + actor + "_CLIENT": client, "PAIR_" + actor + "_SECRET": "synthetic-lifecycle-" + actor, "PAIR_" + actor + "_SUBJECT": lifecycle.SUBJECTS[actor]})
        reports = ROOT / "target/surefire-reports"
        for path in reports.glob("TEST-*.xml"):
            path.unlink()
        phase = "actual-java-sql-native-pair"
        child = subprocess.run(["mvn", "-B", "-ntp", "-q", "surefire:test", "-Dtest=" + SUITE + "#" + case, "-DfailIfNoTests=true", "-Dsurefire.failIfNoSpecifiedTests=true", "-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2"], cwd=ROOT, env=environment, capture_output=True, timeout=180)
        result = sanitize(reports, report.with_suffix(".xml"), case)
        receipt.update(result)
        receipt["closed_source_failure_frames"] = sorted(set(result["closed_source_failure_frames"] + closed_failure_frames(child.stdout, child.stderr)))[:50]
        receipt["registration_status"] = registration_status(child.stdout)
        receipt["executed"] = result["tests"]
        receipt["java_exit"] = child.returncode
        receipt["accepted"] = result["accepted"] and child.returncode == 0
        witness = report.with_name("protected-public-native-witness.json")
        if witness.is_file():
            observed = json.loads(witness.read_text())
            expected = {"realJpaOwner", "proposalBeforeHumanRpc", "encryptedInviteTokenBeforeDispatch",
                "productionProtectedBeans", "publicCoordinatorBeforeNativeCommit", "oneAdminAndInactivity",
                "localCommittedAfterNativeReceipt", "newRecipientLoginAfterOtpAndCommit"}
            assert set(observed) == expected and all(value is True for value in observed.values())
            receipt["consumer_observations"] = observed
        receipt["accepted"] &= receipt.get("consumer_observations") is not None
        http_witness = report.with_name("protected-public-native-http.json")
        if http_witness.is_file():
            observed_http = json.loads(http_witness.read_text())
            assert isinstance(observed_http, list) and len(observed_http) <= 100
            assert all(set(row) == {"phase", "status"} and row["phase"] in {"token", "approve", "status", "create", "owned-read", "prepare", "app", "email-start", "email-finish", "commit"} and type(row["status"]) is int and 100 <= row["status"] <= 599 for row in observed_http)
            receipt["http_observations"] = observed_http
        login_witness = report.with_name("protected-public-native-login.json")
        if login_witness.is_file():
            receipt["recipient_login"] = checked_login_observation(json.loads(login_witness.read_text()))
        fresh_login = report.with_name("protected-public-native-fresh-login.json")
        if fresh_login.is_file():
            receipt["recipient_fresh_login"] = checked_login_observation(json.loads(fresh_login.read_text()))
        if kind == "app":
            receipt["accepted"] &= receipt.get("recipient_login") == {"status": 400, "category": "INVALID_USER_CREDENTIALS", "sameTotpAsEnrollment": True}
            receipt["accepted"] &= receipt.get("recipient_fresh_login") == {"status": 200, "category": "ACCEPTED", "sameTotpAsEnrollment": False}
        else:
            email_login = report.with_name("protected-public-native-email-login.json")
            if email_login.is_file():
                receipt["recipient_email_login"] = checked_email_login_observation(json.loads(email_login.read_text()))
            receipt["accepted"] &= receipt.get("recipient_email_login") == {"challengeStatus": 400, "challengeType": "EMAIL", "acceptedStatus": 200}
        if scenario == "lost-commit":
            terminal = report.with_name("protected-public-native-terminal.json")
            expected = {"realNativeCommitReplyDiscarded", "publicUnavailableInsteadOfSuccess",
                "localReadyRetainedBeforeReconcile", "independentCurrentSetupStatusRead",
                "noRepeatedCreateOtpOrCommit", "cachedLocalTerminalReconcileEffectFree"}
            if terminal.is_file():
                observed = json.loads(terminal.read_text())
                assert set(observed) == expected and all(value is True for value in observed.values())
                receipt["terminal_reconciliation"] = observed
            receipt["accepted"] &= receipt.get("terminal_reconciliation") is not None
        if scenario == "reserved-new":
            tenant_witness = report.with_name("protected-public-native-tenant.json")
            expected = {"realTenantCallerAndNativeSetupBroker", "actualHeldPairAndDisplayedDpaBound",
                "privateTenantReceiptBeforeLocalStage", "sameRegistrationRetryNoSecondTenant",
                "reservationCapabilityAbsentFromFrozenTenantMetadata"}
            if tenant_witness.is_file():
                observed = json.loads(tenant_witness.read_text())
                assert set(observed) == expected and all(value is True for value in observed.values())
                receipt["reserved_tenant_join"] = observed
            receipt["accepted"] &= receipt.get("reserved_tenant_join") is not None
        phase = "post-java-native-inspection"
        # Installer-only fresh fixture token; tested actor expiry/authority is never refreshed here.
        status, installer = lifecycle.call(fixture.base, "POST", "/realms/master/protocol/openid-connect/token", {"client_id": "admin-cli", "grant_type": "password", "username": "synthetic-installer", "password": lifecycle.INSTALLER}, form=True)
        assert status == 200
        fixture.installer = installer["access_token"]
        username = "enc." + base64.b32encode(recipient.encode()).decode().replace("=", ".")
        users = fixture.admin("GET", "/users?username=" + __import__('urllib.parse', fromlist=['quote']).quote(username.lower(), safe='') + "&exact=true")
        assert len(users) == 1
        user = users[0]
        credentials = fixture.admin("GET", "/users/" + user["id"] + "/credentials")
        realm_facts = fixture.admin("GET")
        client_facts = fixture.admin("GET", "/clients?clientId=" + human_claims["azp"])
        assert len(client_facts) == 1
        types = sorted(row["type"] for row in credentials)
        assert all(value in {"password", "otp", "MAIL_OTP_CM"} for value in types)
        receipt["recipient_login_native_facts"] = {
            "loginWithEmailAllowed": realm_facts.get("loginWithEmailAllowed") is True,
            "registrationEmailAsUsername": realm_facts.get("registrationEmailAsUsername") is True,
            "verifyEmailRequired": realm_facts.get("verifyEmail") is True,
            "otpCodeReusable": realm_facts.get("otpPolicyCodeReusable") is True,
            "clientDirectAccessGrantsEnabled": client_facts[0].get("directAccessGrantsEnabled") is True,
            "requiredActionCount": len(user.get("requiredActions", [])),
            "emailVerified": user.get("emailVerified") is True,
            "emailMatchesRecipient": user.get("email") == recipient,
            "usernameMatchesTranscoder": user.get("username") == username.lower(),
            "credentialTypes": types,
        }
        selected = [row for row in credentials if row["type"] == ("MAIL_OTP_CM" if kind == "email" else "otp")]
        receipt["native_account_enabled_after_verified_2fa_commit"] = user.get("enabled") is True
        receipt["one_native_enrollment"] = len(selected) == 1
        receipt["actual_smtp_count"] = len(BootstrapFixture.mail.messages)
        receipt["native_tenant_zero"] = user.get("attributes", {}).get("tenantId") == ["0"]
        receipt["native_target_tenant_matches"] = user.get("attributes", {}).get("tenantId") == [str(expected_tenant)]
        groups = fixture.admin("GET", "/users/" + user["id"] + "/groups")
        receipt["exact_committed_profile_group"] = [group["id"] for group in groups] == [fixture.protected_group_id if role == "PLATFORM_ADMIN" else fixture.tenant_group_id]
        receipt["native_mail_active"] = kind == "app" or (len(selected) == 1 and json.loads(selected[0]["credentialData"]).get("active") is True)
        receipt["accepted"] &= receipt["native_account_enabled_after_verified_2fa_commit"] and receipt["one_native_enrollment"] and receipt["native_mail_active"] and receipt["actual_smtp_count"] == (2 if kind == "email" else 0)
        receipt["accepted"] &= receipt["native_target_tenant_matches"] and receipt["exact_committed_profile_group"]
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
                code_server.worker.join(timeout=5)
                assert not code_server.worker.is_alive()
            receipt["owned_code_server_removed"] = True
            BootstrapFixture.doClassCleanups()
            assert not getattr(BootstrapFixture, "tearDown_exceptions", [])
            containers = subprocess.check_output(["docker", "ps", "-aq", "--filter", "label=oriso-protected-receiver-test=true"], text=True).split()
            volumes = subprocess.check_output(["docker", "volume", "ls", "-q", "--filter", "label=oriso-protected-receiver-test=true"], text=True).split()
            assert not containers and not volumes
            receipt["owned_resources_removed"] = True
        except BaseException as error:
            receipt["cleanup_category"] = type(error).__name__
            receipt["accepted"] = False
        for name, value in saved.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        from protected_resources import evidence as resource_evidence
        receipt["resources"] = resource_evidence()
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
    report = ROOT / "target/protected-public-native/protected-public-native-pair.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    if args.mode == "snapshot":
        save(report.with_name("protected-public-native-before.json"), snapshot(args.keycloak.resolve()))
    elif args.mode == "native":
        native(args.keycloak.resolve(), report)
    else:
        value = json.loads(report.read_text()) if report.is_file() else {"accepted": False, "executed": 0, "phase": "fixture-or-build-incomplete"}
        if value.get("accepted") is not True or value.get("executed") != 1:
            raise SystemExit("Missing or incomplete actual native pair proof")

if __name__ == "__main__":
    main()
