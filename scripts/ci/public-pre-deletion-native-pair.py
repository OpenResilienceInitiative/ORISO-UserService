#!/usr/bin/env python3
"""Three real public pre-deletion/private creator/JPA/native cases; no credentials or raw output retained."""
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
KC_COMMIT = "6ef53b161606a20bdba6fa0e9f9c3ebfcad99dd4"
SUITE = "de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionNativePairProof"
CASES = {"self-asker": "selfAskerRealCreatorDisableLostReplyExpiryAndColdOwnedCompletion",
         "admin-asker": "adminAskerRealCreatorDisableAndPublicOwnedCompletion",
         "admin-consultant": "adminConsultantRealHumanCreatorDisableAndPublicOwnedCompletion"}
IMAGE = "quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992"
WITNESS_FLAGS = {"actualJpa", "actualCreatorCommitted", "creatorProofRetained",
    "originalSqlBirthPreserved", "actualCurrentPrincipal", "realNativeRead", "realNativeDeactivate",
    "strictRealReceipt", "nativeDisabled", "sqlReadOnly", "intentApplied", "oneLocalCallback",
    "sameFrozenCommand", "lostReplyExpiredHumanRecovered"}
PLATFORM_HUMAN_ROLES = frozenset({'agency-admin', 'tenant-admin', 'user-admin'})


def checked_witness(value, kind):
    if (kind not in CASES or not isinstance(value, dict) or set(value) != WITNESS_FLAGS | {"kind"}
            or value["kind"] != kind or any(type(value[key]) is not bool for key in WITNESS_FLAGS)):
        raise ValueError("Exact nonsecret finite witness required")
    return (value['lostReplyExpiredHumanRecovered'] is (kind == 'self-asker')
            and all(value[key] for key in WITNESS_FLAGS - {'lostReplyExpiredHumanRecovered'}))


def checked_protocol(value, kind):
    keys = {'creatorCreate201', 'creatorOwnedRead200', 'creatorCommit200',
        'maintenanceRead200', 'deactivateAttempts', 'deactivate200', 'outcomeRead200', 'humanPasswordGrants200'}
    if (kind not in CASES or not isinstance(value, dict) or set(value) != keys
            or any(type(value[key]) is not int or not 0 <= value[key] <= 10 for key in keys)):
        raise ValueError('Exact finite nonsecret protocol counts required')
    return (all(value[key] == 1 for key in ['creatorCreate201', 'creatorOwnedRead200', 'creatorCommit200', 'maintenanceRead200'])
        and value['deactivate200'] == value['deactivateAttempts'] == (1 if kind == 'self-asker' else 2)
        and value['outcomeRead200'] == (1 if kind == 'self-asker' else 0)
        and value['humanPasswordGrants200'] == (2 if kind == 'self-asker' else 0))


def checked_installer_checks(checks):
    """Two original Bootstrap ready calls; renewal adds one ready/readback pair."""
    if not isinstance(checks, list) or not 2 <= len(checks) <= 1000: return False
    keys = {'phase', 'tokenCount', 'subjectClaimPresent', 'pinnedMasterIssuer',
        'userReadStatus', 'sessionReadStatus', 'sameActualEnabledMasterInstallerAndNativeSessionVerified'}
    for item in checks:
        if (not isinstance(item, dict) or set(item) != keys or item['phase'] not in {'NATIVE_READY', 'RENEWAL'}
                or type(item['tokenCount']) is not int or item['tokenCount'] != (1 if item['phase'] == 'NATIVE_READY' else 2)
                or not isinstance(item['subjectClaimPresent'], list)
                or len(item['subjectClaimPresent']) != item['tokenCount']
                or any(type(value) is not bool for value in item['subjectClaimPresent'])
                or item['pinnedMasterIssuer'] != [True] * item['tokenCount']
                or item['userReadStatus'] != 200 or item['sessionReadStatus'] != 200
                or item['sameActualEnabledMasterInstallerAndNativeSessionVerified'] is not True): return False
    phases = [item['phase'] for item in checks]
    return phases[:2] == ['NATIVE_READY', 'NATIVE_READY'] and phases[2:] == ['NATIVE_READY', 'RENEWAL'] * ((len(phases) - 2) // 2)


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
    if kind not in CASES: raise ValueError("Unknown finite public pre-deletion case")
    clean = ET.Element("testsuite", name=SUITE)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob("TEST-*.xml")); names = []; valid = len(files) == 1; frames = []; categories = set()
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
                    frames += re.findall(r"PublicPreDeletion(?:NativePairProof|JpaTest)\.java:[0-9]+", problem.text or "")
                    category = problem.get("type", "")
                    if re.fullmatch(r"(?:java|org\.springframework|org\.hibernate|org\.opentest4j|de\.caritas\.cob\.userservice)\.[A-Za-z0-9_.$]{1,180}", category):
                        categories.add(category)
        for key, count in counts.items():
            valid &= suite.get(key) is None or int(suite.get(key)) == count
    valid &= names == [CASES[kind]]
    for key, value in counts.items(): clean.set(key, str(value))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding="utf-8", xml_declaration=True)
    return {**counts, "exact_method_inventory": valid, "failure_locations": sorted(set(frames)), "failure_categories": sorted(categories),
            "accepted": valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)}


def canonical_platform_configuration(configuration, subject, group):
    """Finite diagnostic source alignment with canonical Helm PLATFORM roles; no target edit."""
    value = copy.deepcopy(configuration)
    bindings = [item for item in value.get('bindings', []) if item.get('subject') == subject]
    profiles = [item for item in value.get('profiles', []) if item.get('id') == 'platform-v1']
    old = {'agency-admin', 'tenant-admin'}
    if (len(bindings) != 1 or len(profiles) != 1
            or bindings[0].get('authority') != 'PLATFORM'
            or bindings[0].get('authorizerTenantIds') != [0]
            or bindings[0].get('humanClientIds') != ['protected-human-web']
            or bindings[0].get('audienceSets') != [['userservice']]
            or bindings[0].get('protectedProfileIds') != ['platform-v1']
            or set(bindings[0].get('realmRoles', [])) != old
            or len(bindings[0]['realmRoles']) != len(old)
            or profiles[0].get('kind') != 'PLATFORM_ADMIN'
            or profiles[0].get('groupId') != group
            or profiles[0].get('groupPath') != '/protected-admins/platform-admins'
            or set(profiles[0].get('realmRoles', [])) != old
            or len(profiles[0]['realmRoles']) != len(old)):
        raise ValueError('Exact existing diagnostic PLATFORM source binding/profile required')
    bindings[0]['realmRoles'] = sorted(PLATFORM_HUMAN_ROLES)
    profiles[0]['realmRoles'] = sorted(PLATFORM_HUMAN_ROLES)
    return value



def install_canonical_platform_source(cls, helper, configuration, ordinary_policy):
    """Before configured launch, align actual source mappings and verify exact readbacks."""
    value = canonical_platform_configuration(configuration, cls.human_subject, cls.protected_group_id)
    subject = '/users/' + cls.human_subject
    group = '/groups/' + cls.protected_group_id
    old = {'agency-admin', 'tenant-admin'}
    if ({role['name'] for role in helper.admin('GET', subject + '/role-mappings/realm')} != old
            or {role['name'] for role in helper.admin('GET', group + '/role-mappings/realm')} != old):
        raise ValueError('Independent original native PLATFORM mappings changed')
    role = helper.admin('GET', '/roles/user-admin')
    if role.get('name') != 'user-admin' or role.get('composite') is not False:
        raise ValueError('Canonical application role must be non-composite')
    helper.admin('POST', subject + '/role-mappings/realm', [role])
    helper.admin('POST', group + '/role-mappings/realm', [role])
    if ({role['name'] for role in helper.admin('GET', subject + '/role-mappings/realm/composite')} != PLATFORM_HUMAN_ROLES
            or {role['name'] for role in helper.admin('GET', group + '/role-mappings/realm/composite')} != PLATFORM_HUMAN_ROLES):
        raise ValueError('Actual canonical PLATFORM mapping readbacks failed')
    clients = helper.admin('GET', '/clients?clientId=protected-human-web')
    if len(clients) != 1: raise ValueError('One actual human interactive client required')
    scope = {role['name'] for role in helper.admin('GET', '/clients/' + clients[0]['id'] + '/scope-mappings/realm')}
    expected = PLATFORM_HUMAN_ROLES | {role for profile in ordinary_policy['ordinaryProfiles'] for role in profile['realmRoles']}
    if scope != expected: raise ValueError('Exact original target role union plus canonical source client scope required')
    native = helper.admin('GET', subject)
    if native.get('attributes', {}).get('tenantId') != ['0'] or native.get('enabled') is not True or native.get('serviceAccountClientLink') is not None:
        raise ValueError('Actual existing human PLATFORM source required')
    cls.canonical_platform_source = {'roles': sorted(PLATFORM_HUMAN_ROLES),
        'directAndEffectiveHumanMappings': True, 'protectedSourceGroupMapping': True,
        'sourceBindingAndProfile': True, 'sameOriginalTargetClientScope': True}
    return value



def verify_canonical_platform_human(fixture, lifecycle, issuer, password):
    """The inherited two-role human validator is deliberately not widened for this fixture."""
    status, reply = lifecycle.call(fixture.base, 'POST', '/realms/' + lifecycle.REALM + '/protocol/openid-connect/token',
        {'client_id': 'protected-human-web', 'grant_type': 'password', 'username': 'reviewed-human', 'password': password, 'scope': 'openid'}, form=True)
    if status != 200: raise ValueError('Fresh native canonical PLATFORM human login required')
    bearer = reply['access_token']; encoded = bearer.split('.')[1]
    claims = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
    if (claims.get('sub') != fixture.human_subject or claims.get('iss') != issuer
            or claims.get('azp') != 'protected-human-web' or claims.get('tenantId') != '0'
            or set(claims.get('aud', []) if isinstance(claims.get('aud'), list) else [claims.get('aud')]) != {'userservice'}
            or set(claims.get('realm_access', {}).get('roles', [])) != PLATFORM_HUMAN_ROLES
            or type(claims.get('exp')) is not int or claims['exp'] <= time.time() or not claims.get('sid')):
        raise ValueError('Exact actual canonical PLATFORM JWT required')
    status, current = lifecycle.call(fixture.base, 'GET', '/realms/' + lifecycle.REALM + '/protocol/openid-connect/userinfo', token=bearer)
    sessions = fixture.admin('GET', '/users/' + fixture.human_subject + '/sessions')
    if status != 200 or current.get('sub') != fixture.human_subject or not any(session['id'] == claims['sid'] for session in sessions):
        raise ValueError('Actual current human UserInfo and native session required')
    return bearer



def native(kc, report):
    if platform.system() != 'Linux' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Fresh hosted Linux native fixture required')
    available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))
    if available < 4 * 1024 * 1024: raise ValueError('Four GiB headroom with two GiB reserve required')
    kind = os.environ.get('PAIR_KIND')
    if kind not in CASES: raise ValueError('Unknown finite public pre-deletion pair')
    module = kc / 'keycloak-image/account-lifecycle-spi'; otp = kc / 'keycloak-image/otp-config-spi'
    jars = {'LIFECYCLE_C_TEST_JAR': module / 'target/account-lifecycle-spi-1.0-SNAPSHOT.jar',
        'OTP_C_TEST_JAR': otp / 'target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar'}
    if not all(path.is_file() for path in jars.values()): raise ValueError('Actual paired provider JARs required')
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    classes = ['target/test-classes/' + name.replace('.', '/') + '.class' for name in [SUITE,
        'de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionJpaTest']] + [
        'target/classes/de/caritas/cob/userservice/api/' + name + '.class' for name in [
            'adapters/keycloak/lifecycle/OrdinaryRegistrationLifecycle',
            'adapters/keycloak/lifecycle/OrdinaryAccountCreation',
            'adapters/keycloak/lifecycle/JdbcAccountLifecycleJournal',
            'adapters/keycloak/lifecycle/JdbcOwnedLifecycleEffects',
            'adapters/keycloak/lifecycle/KeycloakAccountLifecycleCommands',
            'adapters/keycloak/lifecycle/AccountLifecycleCodec', 'config/auth/TaskActorTokenBroker',
            'config/auth/SecurityConfig', 'config/auth/RoleAuthorizationAuthorityMapper',
            'adapters/keycloak/KeycloakService', 'adapters/keycloak/KeycloakAuthClient', 'IdentityManager',
            'workflow/delete/service/PublicPreDeletionMaintenance',
            'workflow/delete/service/PreDeletionRelatedCustody', 'service/user/UserAccountService',
            'admin/facade/AskerUserAdminFacade', 'admin/service/consultant/ConsultantAdminService',
            'admin/service/consultant/delete/ConsultantPreDeletionService', 'service/chat/MembersOnlyRevocation']]
    if not all((ROOT / path).is_file() for path in classes): raise ValueError('Actual compiled creator/public/owner classes required')
    # Private issued frames/callback implementations are compiled as nested classes, too.
    classes = sorted(set(classes) | {str(nested.relative_to(ROOT)) for name in classes
        for nested in (ROOT / name).parent.glob((ROOT / name).stem + '$*.class')})
    compiled = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
    before = snapshot(kc)
    if before != json.loads(report.with_name('public-pre-deletion-native-before.json').read_text()):
        raise ValueError('Source changed after required build snapshot')
    sys.path.insert(0, str(module / 'tests'))
    import lifecycle_fixture as lifecycle
    from protected_resources import HOSTED_PROFILE, evidence as resource_evidence
    from protected_otp_bootstrap import BootstrapFixture
    from protected_account_operations import OperationFixture, ProtectedOperationRead
    from ordinary_admin_operations import install_policy
    from test_protected_invitations import ISSUER, HUMAN_PASSWORD
    if lifecycle.IMAGE != IMAGE: raise ValueError('Exact native image required')
    frames = source_frame_catalog([Path(__file__), *[module / 'tests' / name for name in [
        'lifecycle_fixture.py', 'test_protected_invitations.py', 'protected_otp_bootstrap.py',
        'protected_resources.py', 'protected_account_operations.py', 'ordinary_admin_operations.py']]])

    class PublicPair(BootstrapFixture):
        native_issuer = ISSUER
        installer_claims = staticmethod(OperationFixture.installer_claims)
        check_installer_identity = OperationFixture.check_installer_identity

        @classmethod
        def prepare_installation(cls, helper, configuration):
            install_policy(cls, helper, configuration)
            policy = copy.deepcopy(cls.ordinary_installed_configuration)
            return install_canonical_platform_source(cls, helper, configuration, policy)

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

    receipt = {'kind': kind, 'case': CASES[kind], 'phase': 'fixture-setup', 'accepted': False,
        'executed': 0, 'genuine_behavior_red': False, 'before': before, 'jars': hashes,
        'compiled_user_classes': compiled, 'image': IMAGE}
    original_env = {key: os.environ.get(key) for key in [*jars, 'PROTECTED_PROOF_RESOURCE_PROFILE']}
    for key, path in jars.items(): os.environ[key] = str(path.resolve())
    os.environ['PROTECTED_PROOF_RESOURCE_PROFILE'] = HOSTED_PROFILE
    control = None; targets = []; observations = []; originals = {}; window = {}; lock = threading.Lock()
    try:
        PublicPair.setUpClass(); fixture = PublicPair('runTest')
        raw_human = verify_canonical_platform_human(fixture, lifecycle, ISSUER, HUMAN_PASSWORD)
        native_human = fixture.admin('GET', '/users/' + fixture.human_subject)
        receipt['canonical_platform_source'] = copy.deepcopy(PublicPair.canonical_platform_source)
        realm = fixture.admin('GET', ''); realm['adminEventsEnabled'] = True
        realm['adminEventsDetailsEnabled'] = False; fixture.admin('PUT', '', realm)

        class Control(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if not 0 < length <= 256: raise ValueError('Fixed bounded observation DATA required')
                    body = json.loads(self.rfile.read(length))
                    with lock:
                        if self.path in {'/self-window/open', '/self-window/close'}:
                            if body != {} or kind != 'self-asker': raise ValueError('Exact SELF time fixture only')
                            if self.path.endswith('/open'):
                                if window: raise ValueError('Window already used')
                                clients = fixture.admin('GET', '/clients?clientId=protected-human-web')
                                if len(clients) != 1: raise ValueError('Actual client required')
                                original = copy.deepcopy(clients[0]); changed = copy.deepcopy(original)
                                changed.setdefault('attributes', {})['access.token.lifespan'] = '15'
                                fixture.admin('PUT', '/clients/' + original['id'], changed)
                                window.update(original=original, restored=False)
                            else:
                                if not window or window['restored']: raise ValueError('Actual original window required')
                                fixture.admin('PUT', '/clients/' + window['original']['id'], window['original'])
                                window['restored'] = True
                            self.send_response(200); self.end_headers(); return
                        if (self.path != '/observe' or set(body) != {'accountId'}
                                or not isinstance(body['accountId'], str)
                                or not re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', body['accountId'])):
                            raise ValueError('Canonical target DATA required')
                        account = body['accountId']
                        if targets and targets != [account]: raise ValueError('Original observed target fixed')
                        if not targets: targets.append(account)
                        current = fixture.admin('GET', '/users/' + account)
                        roles = fixture.admin('GET', '/users/' + account + '/role-mappings/realm/composite')
                        groups = fixture.admin('GET', '/users/' + account + '/groups')
                        if not originals:
                            if current.get('enabled') is not True or type(current.get('createdTimestamp')) is not int or current['createdTimestamp'] <= 0:
                                raise ValueError('Actual original enabled native incarnation required')
                            originals['user'] = copy.deepcopy(current)
                        expected = copy.deepcopy(originals['user']); expected['enabled'] = current.get('enabled')
                        profile = 'consultant' if kind == 'admin-consultant' else 'user'
                        exact = (current == expected and current.get('attributes', {}).get('tenantId') == ['11']
                            and {role['name'] for role in roles} == ({'consultant', 'group-chat-consultant'} if profile == 'consultant' else {'user'})
                            and {group['path'] for group in groups} == {'/human-roles/' + profile})
                        events = fixture.admin('GET', '/admin-events?resourcePath=accounts/' + account + '&max=100')
                        updated = [event for event in events if event.get('resourcePath') == 'accounts/' + account
                            and event.get('operationType') == 'UPDATE' and event.get('resourceType') == 'USER'
                            and event.get('authDetails', {}).get('clientId') == lifecycle.ACTORS['ACCOUNT_ADMIN'][0]
                            and event.get('authDetails', {}).get('userId') == lifecycle.SUBJECTS['ACCOUNT_ADMIN']]
                        result = {'exists': True, 'enabled': current.get('enabled'), 'updateEvents': len(updated),
                            'exactOriginalProjection': exact,
                            'noRepresentation': all(event.get('representation') is None for event in updated)}
                        observations.append(result); raw = json.dumps(result).encode()
                    self.send_response(200); self.send_header('Content-Type', 'application/json')
                    self.end_headers(); self.wfile.write(raw)
                except BaseException:
                    self.send_response(503); self.end_headers()

        control = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Control)
        threading.Thread(target=control.serve_forever, daemon=True).start()
        environment = dict(os.environ)
        environment.update({'PAIR_KIND': kind, 'PAIR_NATIVE_BASE': fixture.base, 'PAIR_REALM': lifecycle.REALM,
            'PAIR_REALM_ID': fixture.realm_id, 'PAIR_HUMAN_ID': fixture.human_subject,
            'PAIR_HUMAN_USERNAME': native_human['username'], 'PAIR_HUMAN_EMAIL': native_human['email'],
            'PAIR_HUMAN_TOKEN': raw_human, 'PAIR_HUMAN_CLIENT': 'protected-human-web',
            'PAIR_CONTEXT_KEY_B64': base64.b64encode(lifecycle.CONTEXT_KEY).decode(),
            'PAIR_CONTROL_BASE': 'http://127.0.0.1:' + str(control.server_address[1]),
            'PAIR_PROTOCOL_WITNESS': str(report.with_name('public-pre-deletion-native-protocol.json').resolve()),
            'PAIR_WITNESS': str(report.with_name('public-pre-deletion-native-witness.json').resolve())})
        for actor, (client, _) in lifecycle.ACTORS.items():
            environment.update({'PAIR_' + actor + '_CLIENT': client,
                'PAIR_' + actor + '_SECRET': 'synthetic-lifecycle-' + actor,
                'PAIR_' + actor + '_SUBJECT': lifecycle.SUBJECTS[actor]})
        reports = ROOT / 'target/surefire-reports'
        for path in reports.glob('TEST-*.xml'): path.unlink()
        receipt['phase'] = 'actual-public-private-creator-jpa-native-deactivate'
        child = subprocess.run(['mvn', '-B', '-ntp', '-q', 'surefire:test', '-Dtest=' + SUITE + '#' + CASES[kind],
            '-DfailIfNoTests=true', '-Dsurefire.failIfNoSpecifiedTests=true',
            '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2'],
            cwd=ROOT, env=environment, capture_output=True, timeout=180)
        inventory = sanitize(reports, report.with_suffix('.xml'), kind); receipt.update(inventory)
        receipt['executed'] = inventory['tests']; receipt['child_exit'] = child.returncode; child = None
        witness_file = report.with_name('public-pre-deletion-native-witness.json')
        witness = json.loads(witness_file.read_text()) if witness_file.exists() else {}
        try: receipt['java_witness_complete'] = checked_witness(witness, kind)
        except ValueError:
            if witness_file.exists(): save(witness_file, {'witnessSchemaRejected': True})
            raise
        receipt['genuine_behavior_red'] = (inventory['exact_method_inventory']
            and inventory['tests'] == inventory['failures'] == 1 and inventory['errors'] == inventory['skipped'] == 0
            and all(witness[key] for key in ['actualJpa', 'actualCreatorCommitted', 'actualCurrentPrincipal']))
        protocol_file = report.with_name('public-pre-deletion-native-protocol.json')
        try:
            protocol = json.loads(protocol_file.read_text())
            receipt['protocol_counts_complete'] = checked_protocol(protocol, kind)
            receipt['protocol_counts'] = protocol
        except (OSError, ValueError, TypeError):
            save(protocol_file, {'protocolSchemaRejected': True}); raise ValueError('Protocol count witness rejected')
        receipt['native_observations'] = observations
        receipt['one_actual_native_effect_event'] = (len(observations) >= 2
            and observations[0]['enabled'] is True and observations[-1]['enabled'] is False
            and observations[-1]['updateEvents'] - observations[0]['updateEvents'] == 1
            and all(value['exists'] and value['exactOriginalProjection'] and value['noRepresentation'] for value in observations))
        receipt['installer_identity_checks'] = ProtectedOperationRead.observation.get('installerIdentityChecks', [])
        receipt['installer_identity_verified'] = checked_installer_checks(receipt['installer_identity_checks'])
        receipt['coverage_limits'] = {'httpSecurityFilterChain': False, 'browser': False,
            'externalDelivery': False, 'pairedPublicGrantWithdrawalRestoration': False,
            'restrictedOrdinaryOperator': False}
        receipt['self_time_fixture_original_restored'] = kind != 'self-asker' or bool(window and window['restored'])
        receipt['accepted'] = (inventory['accepted'] and receipt['child_exit'] == 0 and receipt['java_witness_complete']
            and receipt['protocol_counts_complete'] and receipt['one_actual_native_effect_event']
            and receipt['installer_identity_verified'] and receipt['self_time_fixture_original_restored'])
        receipt['phase'] = 'complete'
    except BaseException as error:
        receipt['exception_category'] = type(error).__name__; receipt['accepted'] = False
        receipt['failure_frames'] = sanitized_failure_frames(error, frames)
        if isinstance(error, KeyboardInterrupt): raise
    finally:
        if control is not None: control.shutdown(); control.server_close()
        if window and not window['restored']:
            try:
                fixture.admin('PUT', '/clients/' + window['original']['id'], window['original'])
                window['restored'] = True
            except BaseException: receipt['self_time_fixture_restore_failed'] = True; receipt['accepted'] = False
        try:
            PublicPair.doClassCleanups(); receipt['cleanup_verified'] = not PublicPair.tearDown_exceptions
        except BaseException as error:
            receipt['cleanup_category'] = type(error).__name__; receipt['cleanup_verified'] = False
        receipt['native_resource_gate'] = resource_evidence()
        for key, value in original_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        receipt['after'] = snapshot(kc); receipt['source_stable'] = before == receipt['after']
        receipt['jar_stable'] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        receipt['compiled_user_stable'] = compiled == {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
        receipt['accepted'] &= receipt['cleanup_verified'] and receipt['source_stable'] and receipt['jar_stable'] and receipt['compiled_user_stable']
        save(report, receipt)
    return 0 if receipt['accepted'] else 1


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('mode', choices=['snapshot', 'native', 'finalize'])
    parser.add_argument('--keycloak', type=Path, required=True)
    parser.add_argument('--report', type=Path, default=ROOT / 'target/public-pre-deletion-native/public-pre-deletion-native-result.json')
    args = parser.parse_args()
    if args.mode == 'snapshot': save(args.report.with_name('public-pre-deletion-native-before.json'), snapshot(args.keycloak)); return 0
    if args.mode == 'native': return native(args.keycloak.resolve(), args.report)
    if not args.report.exists(): save(args.report, {'phase': 'not-executed', 'executed': 0, 'accepted': False, 'genuine_behavior_red': False})
    return 0 if json.loads(args.report.read_text()).get('accepted') is True else 1


if __name__ == '__main__': raise SystemExit(main())
