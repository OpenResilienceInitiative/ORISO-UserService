#!/usr/bin/env python3
"""Three real private creator/JPA/deletion/native cases; no credentials or raw output retained."""
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
KC_COMMIT = "5d5db75faa1c36ff6d6ed2eb41f71ecd415e6aeb"
SUITE = "de.caritas.cob.userservice.api.workflow.delete.service.UserScheduledDeletionNativePairProof"
CASES = {"asker": "scheduledAskerRealCreatorDeleteLostReplyAndFinalJpa",
         "consultant": "scheduledConsultantRealHumanCreatorDeleteAndFinalJpa",
         "inactivity": "freshInactivityRealCreatorDeleteAndFinalJpa"}
IMAGE = "quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992"
WITNESS_FLAGS = {"actualJpa", "actualCreatorCommitted", "claimedOriginalSqlBirthPreserved",
    "canonicalAdmin", "realNativeRead", "realNativeDelete", "strictRealReceipt",
    "nativeAbsent", "sqlAbsent", "intentApplied", "oneLocalCallback",
    "sameFrozenCommand", "lostReplyRetainedBeforeRetry", "realCreatorProofRetained"}


def checked_witness(value, kind):
    if (kind not in CASES or not isinstance(value, dict) or set(value) != WITNESS_FLAGS | {"kind"}
            or value["kind"] != kind or any(type(value[key]) is not bool for key in WITNESS_FLAGS)):
        raise ValueError("Exact nonsecret finite witness required")
    return (value['lostReplyRetainedBeforeRetry'] is (kind == 'asker')
            and all(value[key] for key in WITNESS_FLAGS - {'lostReplyRetainedBeforeRetry'}))


def checked_protocol(value, kind):
    keys = {'creatorCreate201', 'creatorOwnedRead200', 'creatorCommit200',
        'cleanupRead200', 'cleanupDelete200', 'cleanupDeleteAttempts'}
    if (kind not in CASES or not isinstance(value, dict) or set(value) != keys
            or any(type(value[key]) is not int or not 0 <= value[key] <= 10 for key in keys)):
        raise ValueError('Exact finite nonsecret protocol counts required')
    return (all(value[key] == 1 for key in ['creatorCreate201', 'creatorOwnedRead200', 'creatorCommit200'])
        and value['cleanupRead200'] == (2 if kind == 'inactivity' else 1)
        and value['cleanupDelete200'] == value['cleanupDeleteAttempts'] == (2 if kind == 'asker' else 1))


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
    if kind not in CASES: raise ValueError("Unknown finite deletion case")
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
                    frames += re.findall(r"UserScheduledDeletionNativePairProof\.java:[0-9]+", problem.text or "")
        for key, count in counts.items():
            valid &= suite.get(key) is None or int(suite.get(key)) == count
    valid &= names == [CASES[kind]]
    for key, value in counts.items(): clean.set(key, str(value))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding="utf-8", xml_declaration=True)
    return {**counts, "exact_method_inventory": valid, "failure_locations": sorted(set(frames)),
            "accepted": valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)}


def native(kc, report):
    if platform.system() != 'Linux' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Fresh hosted Linux native fixture required')
    available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))
    if available < 4 * 1024 * 1024: raise ValueError('Four GiB headroom with two GiB reserve required')
    kind = os.environ.get('PAIR_KIND')
    if kind not in CASES: raise ValueError('Unknown finite native deletion pair')
    module = kc / 'keycloak-image/account-lifecycle-spi'; otp = kc / 'keycloak-image/otp-config-spi'
    jars = {'LIFECYCLE_C_TEST_JAR': module / 'target/account-lifecycle-spi-1.0-SNAPSHOT.jar',
            'OTP_C_TEST_JAR': otp / 'target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar'}
    if not all(path.is_file() for path in jars.values()): raise ValueError('Actual paired provider JARs required')
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    classes = ['target/test-classes/' + SUITE.replace('.', '/') + '.class'] + [
        'target/test-classes/de/caritas/cob/userservice/api/workflow/delete/service/NativePair' + kind + 'ActionsFixture.class'
        for kind in ['Asker', 'Consultant']] + [
        'target/classes/de/caritas/cob/userservice/api/' + path + '.class' for path in [
            'adapters/keycloak/lifecycle/OrdinaryRegistrationLifecycle',
            'adapters/keycloak/lifecycle/OrdinaryAccountCreation',
            'adapters/keycloak/lifecycle/JdbcAccountLifecycleJournal',
            'adapters/keycloak/lifecycle/KeycloakAccountLifecycleCommands',
            'adapters/keycloak/lifecycle/AccountLifecycleCodec', 'config/auth/TaskActorTokenBroker',
            'workflow/delete/service/ScheduledAskerMaintenance',
            'workflow/delete/service/ScheduledConsultantMaintenance',
            'workflow/delete/service/DeleteUserAccountService',
            'workflow/accountinactivity/AccountInactivityService']]
    if not all((ROOT / path).is_file() for path in classes): raise ValueError('Actual compiled creator/owner/consumer classes required')
    compiled = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
    before = snapshot(kc)
    if before != json.loads(report.with_name('scheduled-deletion-native-before.json').read_text()):
        raise ValueError('Source changed after required build snapshot')
    sys.path.insert(0, str(module / 'tests'))
    import lifecycle_fixture as lifecycle
    from maintenance_human_fixture import CLIENT, ISSUER, HumanMaintenanceFixture
    from protected_account_operations import OperationFixture, ProtectedOperationRead
    if lifecycle.IMAGE != IMAGE: raise ValueError('Exact native image required')
    frame_catalog = source_frame_catalog([Path(__file__), *[module / 'tests' / name for name in
        ['lifecycle_fixture.py', 'maintenance_human_fixture.py', 'protected_account_operations.py']]])

    class UserPair(HumanMaintenanceFixture, unittest.TestCase):
        native_issuer = ISSUER
        installer_claims = staticmethod(OperationFixture.installer_claims)
        check_installer_identity = OperationFixture.check_installer_identity

        @classmethod
        def ready(cls):
            super().ready()
            cls('runTest').check_installer_identity([cls.installer], 'NATIVE_READY')

        def admin(self, method, path='', body=None):
            previous = self.installer; claims = self.installer_claims(previous)
            if type(claims.get('exp')) is not int: raise ValueError('Installer expiry required')
            if claims['exp'] <= time.time() + 30:
                type(self).ready(); self.installer = type(self).installer
                self.check_installer_identity([previous, self.installer], 'RENEWAL')
            return super().admin(method, path, body)

        @classmethod
        def reviewed_human_profile(cls):
            return {'kind': 'AGENCY_ADMIN', 'group': 'agency-admin',
                'roles': ['restricted-agency-admin', 'user-admin'], 'id': 'agency-admin-v1'}

        @classmethod
        def reviewed_self_operations(cls): return ['account.read']

        @classmethod
        def extra_launch_arguments(cls):
            return ['-v', str(jars['OTP_C_TEST_JAR'].resolve()) + ':/opt/keycloak/providers/otp-config-spi.jar:ro',
                '-e', 'IDENTITY_ACCOUNT_ADMIN_CLIENT_ID=backend-account-admin',
                '-e', 'ORISO_APP_BASE_URL=https://otp-fixture.internal', '-e', 'EMAIL_BRANDING_NAME=OTP Fixture',
                '-e', 'EMAIL_LEGAL_ORGANISATION_NAME=Fixture Operator',
                '-v', str(kc / 'keycloak-image/themes/oriso') + ':/opt/keycloak/themes/oriso:ro',
                '--add-host', 'host.docker.internal:host-gateway']

    receipt = {'kind': kind, 'case': CASES[kind], 'phase': 'fixture-setup', 'accepted': False,
        'executed': 0, 'genuine_behavior_red': False, 'before': before, 'jars': hashes,
        'compiled_user_classes': compiled, 'image': IMAGE}
    original_env = {key: os.environ.get(key) for key in jars}
    for key, path in jars.items(): os.environ[key] = str(path.resolve())
    control = None; targets = []; observations = []; lock = threading.Lock()
    try:
        UserPair.setUpClass(); fixture = UserPair('runTest'); raw_human = fixture.human()
        native_human = fixture.admin('GET', '/users/' + fixture.account_id)
        prefix = 'ORISO_ACCOUNT_MAINTENANCE_CONFIG='
        installed = [arg for arg in UserPair.configured_command if arg.startswith(prefix)]
        assert len(installed) == 1
        policy = json.loads(installed[0][len(prefix):]); parent = fixture.group_id('/human-roles')
        for profile, group, roles in [('user-v1', 'user', ['user']),
                ('consultant-v1', 'consultant', ['consultant', 'group-chat-consultant'])]:
            policy['ordinaryProfiles'].append({'id': profile, 'version': 1,
                'groupId': fixture.group_id('/human-roles/' + group),
                'groupPath': '/human-roles/' + group, 'groupParentId': parent,
                'realmRoles': roles, 'clientRoles': {}, 'allowUnassignedTenant': False,
                'selfOperations': ['account.read']})
        policy['workflowOperations'] = [{'kind': 'INACTIVITY',
            'targetProfiles': ['user-v1', 'consultant-v1'],
            'operations': ['account.read', 'account.deactivate', 'account.reactivate']}]
        command = [prefix + json.dumps(policy, separators=(',', ':')) if arg.startswith(prefix) else arg
            for arg in UserPair.configured_command]
        subprocess.run(['docker', 'stop', '-t', '5', UserPair.name], check=True, capture_output=True, timeout=30)
        subprocess.run(['docker', 'rm', UserPair.name], check=True, capture_output=True, timeout=30)
        subprocess.run(command, check=True, capture_output=True, timeout=30)
        UserPair.refresh_endpoint('reviewed-deletion-profiles'); UserPair.ready(); fixture.installer = UserPair.installer
        assert native_human == fixture.admin('GET', '/users/' + fixture.account_id)
        raw_human = fixture.human()
        realm = fixture.admin('GET', ''); realm['adminEventsEnabled'] = True
        realm['adminEventsDetailsEnabled'] = False; fixture.admin('PUT', '', realm)

        class Control(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if self.path != '/observe' or not 0 < length <= 256: raise ValueError('Fixed observation only')
                    body = json.loads(self.rfile.read(length))
                    if set(body) != {'accountId'} or not re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', body['accountId']):
                        raise ValueError('Canonical target DATA required')
                    with lock:
                        account = body['accountId']
                        if targets and targets != [account]: raise ValueError('Original observed target fixed')
                        if not targets: targets.append(account)
                        fixture.admin('GET', '')  # same disposable installer/session preflight
                        status, current = lifecycle.call(fixture.base, 'GET', '/admin/realms/' + lifecycle.REALM + '/users/' + account, token=fixture.installer)
                        if status not in (200, 404): raise ValueError('Native observation unavailable')
                        events = fixture.admin('GET', '/admin-events?resourcePath=accounts/' + account + '&max=100')
                        deleted = [event for event in events if event.get('resourcePath') == 'accounts/' + account
                            and event.get('operationType') == 'DELETE' and event.get('resourceType') == 'USER'
                            and event.get('authDetails', {}).get('clientId') == lifecycle.ACTORS['ACCOUNT_ADMIN'][0]
                            and event.get('authDetails', {}).get('userId') == lifecycle.SUBJECTS['ACCOUNT_ADMIN']]
                        result = {'exists': status == 200, 'deleteEvents': len(deleted),
                            'noRepresentation': all(event.get('representation') is None for event in deleted)}
                        if status == 200:
                            roles = fixture.admin('GET', '/users/' + account + '/role-mappings/realm/composite')
                            groups = fixture.admin('GET', '/users/' + account + '/groups')
                            result['exactOriginalProjection'] = (current.get('enabled') is True
                                and current.get('attributes', {}).get('tenantId') == ['11']
                                and {role['name'] for role in roles} == ({'consultant', 'group-chat-consultant'} if kind == 'consultant' else {'user'})
                                and {group['path'] for group in groups} == {'/human-roles/' + ('consultant' if kind == 'consultant' else 'user')})
                        observations.append(result); raw = json.dumps(result).encode()
                    self.send_response(200); self.send_header('Content-Type', 'application/json')
                    self.end_headers(); self.wfile.write(raw)
                except BaseException:
                    self.send_response(503); self.end_headers()

        control = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Control)
        threading.Thread(target=control.serve_forever, daemon=True).start()
        environment = dict(os.environ)
        environment.update({'PAIR_KIND': kind, 'PAIR_NATIVE_BASE': fixture.base, 'PAIR_REALM': lifecycle.REALM,
            'PAIR_REALM_ID': fixture.realm_id, 'PAIR_HUMAN_ID': fixture.account_id,
            'PAIR_HUMAN_USERNAME': native_human['username'], 'PAIR_HUMAN_EMAIL': native_human['email'],
            'PAIR_HUMAN_TOKEN': raw_human, 'PAIR_HUMAN_CLIENT': CLIENT,
            'PAIR_CONTEXT_KEY_B64': base64.b64encode(lifecycle.CONTEXT_KEY).decode(),
            'PAIR_CONTROL_BASE': 'http://127.0.0.1:' + str(control.server_address[1]),
            'PAIR_TIME_WITNESS': str(report.with_name('scheduled-deletion-native-time-fixture.json').resolve()),
            'PAIR_PROTOCOL_WITNESS': str(report.with_name('scheduled-deletion-native-protocol.json').resolve()),
            'PAIR_WITNESS': str(report.with_name('scheduled-deletion-native-witness.json').resolve())})
        for actor, (client, _) in lifecycle.ACTORS.items():
            environment.update({'PAIR_' + actor + '_CLIENT': client,
                'PAIR_' + actor + '_SECRET': 'synthetic-lifecycle-' + actor,
                'PAIR_' + actor + '_SUBJECT': lifecycle.SUBJECTS[actor]})
        reports = ROOT / 'target/surefire-reports'
        for path in reports.glob('TEST-*.xml'): path.unlink()
        receipt['phase'] = 'actual-private-creator-jpa-native-delete'
        child = subprocess.run(['mvn', '-B', '-ntp', '-q', 'surefire:test', '-Dtest=' + SUITE + '#' + CASES[kind],
            '-DfailIfNoTests=true', '-Dsurefire.failIfNoSpecifiedTests=true',
            '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2'],
            cwd=ROOT, env=environment, capture_output=True, timeout=180)
        inventory = sanitize(reports, report.with_suffix('.xml'), kind); receipt.update(inventory)
        receipt['executed'] = inventory['tests']; receipt['child_exit'] = child.returncode; child = None
        witness_file = report.with_name('scheduled-deletion-native-witness.json')
        witness = json.loads(witness_file.read_text()) if witness_file.exists() else {}
        try: receipt['java_witness_complete'] = checked_witness(witness, kind)
        except ValueError:
            if witness_file.exists(): save(witness_file, {'witnessSchemaRejected': True})
            raise
        receipt['genuine_behavior_red'] = (inventory['exact_method_inventory']
            and inventory['tests'] == inventory['failures'] == 1 and inventory['errors'] == inventory['skipped'] == 0
            and all(witness[key] for key in ['actualJpa', 'canonicalAdmin', 'actualCreatorCommitted']))
        protocol_file = report.with_name('scheduled-deletion-native-protocol.json')
        try:
            protocol = json.loads(protocol_file.read_text())
            receipt['protocol_counts_complete'] = checked_protocol(protocol, kind)
            receipt['protocol_counts'] = protocol
        except (OSError, ValueError, TypeError):
            save(protocol_file, {'protocolSchemaRejected': True}); raise ValueError('Protocol count witness rejected')
        if kind == 'inactivity' and report.with_name('scheduled-deletion-native-time-fixture.json').exists():
            time_file = report.with_name('scheduled-deletion-native-time-fixture.json')
            value = json.loads(time_file.read_text())
            if (set(value) != {'mode', 'userCreateDateBefore', 'userCreateDateAfter', 'lastActivity', 'dueAt'}
                    or value['mode'] != 'EXPLICIT_ELAPSED_TIME_FIXTURE_BEFORE_ANY_CLAIM'
                    or value['userCreateDateAfter'] != value['lastActivity']
                    or any(not isinstance(value[key], str) or not re.fullmatch(r'[0-9TZ:.+-]{20,40}', value[key])
                           for key in ['userCreateDateBefore', 'userCreateDateAfter', 'lastActivity', 'dueAt'])):
                save(time_file, {'timeFixtureSchemaRejected': True}); raise ValueError('Closed elapsed-time DATA required')
            receipt['explicit_time_fixture'] = value
        receipt['native_observations'] = observations
        receipt['one_actual_native_effect_event'] = bool(observations) and observations[-1] == {
            'exists': False, 'deleteEvents': 1, 'noRepresentation': True}
        receipt['installer_identity_checks'] = ProtectedOperationRead.observation.get('installerIdentityChecks', [])
        receipt['phase_receipts'] = UserPair.phase_receipts
        receipt['endpoint_receipts'] = UserPair.endpoint_receipts
        receipt['installer_identity_verified'] = bool(receipt['installer_identity_checks']) and all(
            check.get('sameActualEnabledMasterInstallerAndNativeSessionVerified') is True for check in receipt['installer_identity_checks'])
        receipt['accepted'] = (inventory['accepted'] and receipt['child_exit'] == 0 and receipt['java_witness_complete']
            and receipt['protocol_counts_complete']
            and (kind != 'inactivity' or 'explicit_time_fixture' in receipt)
            and receipt['one_actual_native_effect_event'] and receipt['installer_identity_verified'])
        receipt['phase'] = 'complete'
    except BaseException as error:
        receipt['exception_category'] = type(error).__name__; receipt['accepted'] = False
        receipt['failure_frames'] = sanitized_failure_frames(error, frame_catalog)
        if isinstance(error, KeyboardInterrupt): raise
    finally:
        if control is not None: control.shutdown(); control.server_close()
        try:
            UserPair.doClassCleanups()
            if getattr(UserPair, 'tearDown_exceptions', []): receipt['accepted'] = False; receipt['cleanup_failure_count'] = len(UserPair.tearDown_exceptions)
        except BaseException as error: receipt['cleanup_category'] = type(error).__name__; receipt['accepted'] = False
        for key, value in original_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        receipt['after'] = snapshot(kc); receipt['source_stable'] = before == receipt['after']
        receipt['jar_stable'] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        receipt['compiled_user_stable'] = compiled == {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
        receipt['accepted'] &= receipt['source_stable'] and receipt['jar_stable'] and receipt['compiled_user_stable']
        save(report, receipt)
    return 0 if receipt['accepted'] else 1


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('mode', choices=['snapshot', 'native', 'finalize'])
    parser.add_argument('--keycloak', type=Path, required=True)
    parser.add_argument('--report', type=Path, default=ROOT / 'target/scheduled-deletion-native/scheduled-deletion-native-result.json')
    args = parser.parse_args()
    if args.mode == 'snapshot': save(args.report.with_name('scheduled-deletion-native-before.json'), snapshot(args.keycloak)); return 0
    if args.mode == 'native': return native(args.keycloak.resolve(), args.report)
    if not args.report.exists(): save(args.report, {'phase': 'not-executed', 'executed': 0, 'accepted': False, 'genuine_behavior_red': False})
    return 0 if json.loads(args.report.read_text()).get('accepted') is True else 1


if __name__ == '__main__': raise SystemExit(main())
