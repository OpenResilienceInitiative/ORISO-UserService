#!/usr/bin/env python3
"""Independent actual tracked creation capture proof. No successful onboarding claim."""
import argparse
import contextlib
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
KC_COMMIT = '5d5db75faa1c36ff6d6ed2eb41f71ecd415e6aeb'
SUITE = 'de.caritas.cob.userservice.api.TrackedConsultantCreationNativePairProof'
CASE = 'trackedPublicCreationCapturesNativeIdentityBeforeFailureAndNeverRedispatches'
JAVA_SOURCE = 'src/test/java/' + SUITE.replace('.', '/') + '.java'
PREFIX = 'tracked-creation-native-'
FLAGS = {'productionClasspath', 'fiveActors', 'actualServletSecurity', 'actualJpa', 'scopedOwners',
         'creationSchema', 'bootNoEffect', 'matrixNonceReached', 'durableNativeCapture',
         'receiptById', 'receiptByKey', 'matchingReplay', 'changedBodyConflict',
         'unauthorizedConcealed', 'unknownConcealed', 'nativeCheckpoint', 'failureReceipt',
         'rolledBackWithoutConsultant', 'noMatrixRegistration', 'tenantPolicyReached', 'contextClosed'}
MIGRATIONS = ('db/changelog/changeset/20261023_account_creation_attempt/changeSet.xml',
              'db/changelog/changeset/20261029_account_creation_receipt/changeSet.xml')
MIGRATION_IDS = ('20261023-account-creation-attempt', '20261029-account-creation-receipt')
spec = importlib.util.spec_from_file_location('tracked_startup_helpers', ROOT / 'scripts/ci/application-startup-native-pair.py')
startup = importlib.util.module_from_spec(spec); spec.loader.exec_module(startup)
base = startup.base


def checked_witness(value):
    if not isinstance(value, dict) or set(value) != FLAGS or any(type(v) is not bool for v in value.values()):
        raise ValueError('Exact fixed boolean capture witness required')
    return all(value.values())


def install_tracked_runtime_audiences(fixture, lifecycle, issuer):
    """New proof only: replace the inherited native-denial audiences with User's runtime contract."""
    expected = {'tenantservice', 'agencyservice', 'appointmentservice'}
    old = {'userservice', 'oriso-account-lifecycle'}
    client, roles = lifecycle.ACTORS['RUNTIME']
    subject = lifecycle.SUBJECTS['RUNTIME']
    client_path = '/clients/' + fixture.client_ids['RUNTIME']
    before = fixture.admin('GET', client_path)
    account = fixture.admin('GET', client_path + '/service-account-user')
    mappings = fixture.admin('GET', '/users/' + subject + '/role-mappings')
    credentials = fixture.admin('GET', '/users/' + subject + '/credentials')
    if (before.get('clientId') != client or before.get('enabled') is not True
            or before.get('serviceAccountsEnabled') is not True or account.get('id') != subject
            or account.get('enabled') is not True):
        raise ValueError('Existing runtime fixture binding required')
    def claims_match(audiences):
        status, response = lifecycle.call(fixture.base, 'POST',
            '/realms/' + lifecycle.REALM + '/protocol/openid-connect/token',
            {'client_id': client, 'client_secret': 'synthetic-lifecycle-RUNTIME',
             'grant_type': 'client_credentials'}, form=True)
        if status != 200 or not isinstance(response, dict) or not isinstance(response.get('access_token'), str):
            raise ValueError('Actual runtime fixture grant required')
        token = response['access_token']; part = token.split('.')[1]
        claims = json.loads(__import__('base64').urlsafe_b64decode(part + '=' * (-len(part) % 4)))
        aud = claims.get('aud'); aud = [aud] if isinstance(aud, str) else aud
        now = time.time()
        if (claims.get('iss') != issuer or claims.get('sub') != subject or claims.get('azp') != client
                or not isinstance(aud, list) or len(aud) != len(audiences) or set(aud) != audiences
                or set(claims.get('realm_access', {}).get('roles', [])) != set(roles)
                or claims.get('resource_access') not in (None, {})
                or type(claims.get('iat')) is not int or type(claims.get('exp')) is not int
                or not 0 < claims['iat'] <= now < claims['exp']):
            raise ValueError('Exact runtime fixture token contract required')
    claims_match(old)
    mapper_path = client_path + '/protocol-mappers/models'
    original = fixture.admin('GET', mapper_path)
    audience = [m for m in original if m.get('protocolMapper') == 'oidc-audience-mapper']
    retained = [m for m in original if m.get('protocolMapper') != 'oidc-audience-mapper']
    if (len(audience) != 2 or {m.get('config', {}).get('included.custom.audience') for m in audience} != old
            or any(m.get('config', {}).get('access.token.claim') != 'true' for m in audience)):
        raise ValueError('Exact inherited runtime audience fixture required')
    without_id = lambda value: {k: v for k, v in value.items() if k != 'id'}
    template = without_id(audience[0])
    for mapper in audience:
        comparable = without_id(mapper); comparable['name'] = template['name']
        comparable['config'] = dict(mapper['config'])
        comparable['config']['included.custom.audience'] = template['config']['included.custom.audience']
        if comparable != template or mapper.get('protocol') != 'openid-connect':
            raise ValueError('Identical original runtime mapper metadata required')
    desired = []
    for aud in sorted(expected):
        mapper = copy.deepcopy(template); mapper['name'] = 'audience-' + aud
        mapper['config']['included.custom.audience'] = aud; desired.append(mapper)
    for mapper in audience: fixture.admin('DELETE', mapper_path + '/' + mapper['id'])
    for mapper in desired: fixture.admin('POST', mapper_path, mapper)
    installed = fixture.admin('GET', mapper_path)
    new_audience = [m for m in installed if m.get('protocolMapper') == 'oidc-audience-mapper']
    stable = lambda values: sorted(values, key=lambda value: value['id'])
    after = fixture.admin('GET', client_path)
    if (stable([m for m in installed if m.get('protocolMapper') != 'oidc-audience-mapper']) != stable(retained)
            or sorted([without_id(m) for m in new_audience], key=lambda m: m['name']) != desired
            or {k: v for k, v in before.items() if k != 'protocolMappers'}
                != {k: v for k, v in after.items() if k != 'protocolMappers'}
            or fixture.admin('GET', client_path + '/service-account-user') != account
            or fixture.admin('GET', '/users/' + subject + '/role-mappings') != mappings
            or fixture.admin('GET', '/users/' + subject + '/credentials') != credentials):
        raise ValueError('Runtime audience projection changed another binding')
    claims_match(expected)
    return {'inheritedNativeDenialAudiencesObserved': True, 'canonicalRuntimeAudiencesReadBack': True,
            'sameClientSubjectCredentialsRolesAndOtherMappers': True, 'freshNativeRuntimeTokenMatched': True}


def checked_java_failure_frames(private_value, root, source_hashes):
    """Only source-bound JVM method/line metadata may leave the private child output."""
    categories = {'java.lang.IllegalStateException', 'java.lang.IllegalArgumentException',
        'java.lang.NullPointerException', 'org.springframework.transaction.CannotCreateTransactionException',
        'org.springframework.dao.InvalidDataAccessResourceUsageException',
        'org.springframework.dao.DataAccessResourceFailureException',
        'org.springframework.web.client.HttpClientErrorException$Forbidden',
        'org.springframework.web.client.HttpServerErrorException$InternalServerError',
        'org.springframework.web.client.ResourceAccessException',
        'org.springframework.security.access.AccessDeniedException',
        'de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException'}
    if not isinstance(private_value, dict) or set(private_value) != {'exceptionClasses', 'frames'}:
        raise ValueError('Closed private exception metadata required')
    types = private_value['exceptionClasses']; frames = private_value['frames']
    if (not isinstance(types, list) or len(types) > 4
            or any(type(v) is not str or len(v) > 256 for v in types)
            or not isinstance(frames, list) or len(frames) > 128):
        raise ValueError('Bounded private exception metadata required')
    found_categories = sorted(set(types) & categories)
    candidates = []
    for frame in frames:
        if not isinstance(frame, dict) or set(frame) != {'class', 'method', 'file', 'line'}:
            raise ValueError('Closed private stack frame required')
        cls, method, file, line = (frame[k] for k in ['class', 'method', 'file', 'line'])
        if (any(type(v) is not str or len(v) > 256 for v in [cls, method, file])
                or type(line) is not int or not 0 < line < 100000
                or not re.fullmatch(r'[A-Za-z_$][\w.$]*', cls)
                or not re.fullmatch(r'[\w$<>]+', method)
                or not re.fullmatch(r'[A-Za-z_$][\w$]*\.java', file)):
            continue
        outer = cls.split('$', 1)[0]
        paths = ['src/main/java/' + outer.replace('.', '/') + '.java']
        if outer == SUITE: paths.append(JAVA_SOURCE)
        source = next((path for path in paths if path in source_hashes), None)
        if source is None or Path(source).name != file: continue
        raw = (root / source).read_bytes()
        if hashlib.sha256(raw).hexdigest() != source_hashes[source]: continue
        if not 0 < int(line) <= len(raw.splitlines()): continue
        frame = (cls, method, file, int(line))
        if frame not in candidates: candidates.append(frame)
        if len(candidates) == 16: break
    accepted = []
    if candidates:
        classes = sorted({item[0] for item in candidates})
        try:
            result = subprocess.run(['javap', '-p', '-l', '-classpath',
                os.pathsep.join(str(root / path) for path in ['target/classes', 'target/test-classes']),
                *classes], capture_output=True, text=True, timeout=10, check=True)
            current_class = None; current_method = None; lines = set()
            for text in result.stdout.splitlines():
                declared = re.match(r'^(?:[\w]+\s+)*(?:class|interface|enum) ([\w.$]+)(?:[<\s{]|$)', text)
                if declared:
                    current_class = declared.group(1); current_method = None
                elif re.match(r'^  \S.*\([^)]*\)(?: throws [^;]+)?;$', text):
                    name = text.strip().split('(', 1)[0].split()[-1]
                    current_method = '<init>' if name == current_class else name
                elif text.strip() == 'static {};': current_method = '<clinit>'
                else:
                    number = re.match(r'^\s+line ([1-9][0-9]*): [0-9]+$', text)
                    if number and current_class in classes and current_method:
                        lines.add((current_class, current_method, int(number.group(1))))
            accepted = [{'class': cls, 'method': method, 'file': file, 'line': line}
                        for cls, method, file, line in candidates if (cls, method, line) in lines]
        except (OSError, subprocess.SubprocessError):
            pass  # Missing compiler metadata grants no source-frame claim.
    return {'exceptionCategories': found_categories, 'sourceFrames': accepted}


def checked_nonce_diagnostic(value):
    states = {
        'dispatch': {'PREPARED', 'DISPATCH_UNKNOWN', 'NATIVE_ID_CAPTURED'},
        'workflow': {'NOT_STARTED', 'IN_PROGRESS', 'RETURNED', 'FAILED', 'UNKNOWN'},
        'row': {'NOT_ATTEMPTED', 'COMMIT_UNKNOWN', 'COMMITTED', 'ROLLED_BACK'},
        'reason': {'NONE', 'NATIVE_OUTCOME_UNKNOWN', 'NATIVE_CAPTURE_FAILED', 'MANDATORY_ATTRIBUTES_FAILED',
                   'WORKFLOW_FAILED', 'ROW_COMMIT_FAILED', 'ONBOARDING_FAILED', 'JOURNAL_UNAVAILABLE'}}
    booleans = {'responseDone', 'receiptHeaderPresent', 'rowReadSucceeded'}
    counts = {'singleTenantReads', 'policyReads', 'nonceReads'}
    fields = set(states) | booleans | counts | {'responseStatus', 'rowCount'}
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError('Closed nonce diagnostic required')
    if any(type(value[k]) is not bool for k in booleans):
        raise ValueError('Fixed nonce diagnostic booleans required')
    status = value['responseStatus']
    if type(status) is not int or not (status == 0 or 100 <= status <= 599):
        raise ValueError('Bounded response status required')
    if not value['responseDone'] and (status != 0 or value['receiptHeaderPresent']):
        raise ValueError('Pending response cannot report receipt')
    if status == 0 and value['receiptHeaderPresent']:
        raise ValueError('Unavailable response cannot report receipt')
    if any(type(value[k]) is not int or not 0 <= value[k] <= 100 for k in counts):
        raise ValueError('Bounded nonce diagnostic counts required')
    if type(value['rowCount']) is not str or value['rowCount'] not in {'0', '1', 'MANY'}:
        raise ValueError('Fixed row cardinality required')
    for key, allowed in states.items():
        if type(value[key]) is not str or value[key] not in allowed | {'ABSENT', 'INVALID'}:
            raise ValueError('Fixed journal phase required')
    if not value['rowReadSucceeded'] and value['rowCount'] != '0':
        raise ValueError('Failed journal read has no cardinality observation')
    if value['rowCount'] != '1' and any(value[k] != 'ABSENT' for k in states):
        raise ValueError('Only one observed row has phases')
    return dict(value)


def exact_create_audit(before, after, account, realm, client, subject):
    if any(not isinstance(e.get('id'), str) or not e['id'] for e in [*before, *after]): return False
    old = {e['id']: e for e in before}; new = {e['id']: e for e in after}
    if len(old) != len(before) or len(new) != len(after): return False
    if any(new.get(key) != value for key, value in old.items()): return False
    added = [e for key, e in new.items() if key not in old]
    if len(added) != 1: return False
    event = added[0]; auth = event.get('authDetails', {})
    return (event.get('operationType') == 'CREATE' and event.get('resourceType') == 'USER'
        and event.get('realmId') == realm and event.get('resourcePath') == 'accounts/' + account
        and event.get('representation') in (None, '') and isinstance(auth, dict)
        and auth.get('realmId') == realm and auth.get('clientId') == client and auth.get('userId') == subject)



def final_creation_audit(before, after, account, realm, create_client, create_subject, admin_client, admin_subject):
    """After rollback only the original CREATE and at most its owned compensation DELETE are valid.

    K recover emits no event; terminal replay emits none. UPDATE means commit, which this
    failed local saga must never reach. All original events and exact authority bindings remain.
    """
    if any(not isinstance(e.get('id'), str) or not e['id'] for e in [*before, *after]): return False
    old = {e['id']: e for e in before}; new = {e['id']: e for e in after}
    if len(old) != len(before) or len(new) != len(after): return False
    if any(new.get(key) != value for key, value in old.items()): return False
    added = [e for key, e in new.items() if key not in old]
    creates = [e for e in added if e.get('operationType') == 'CREATE']
    if not exact_create_audit(before, [*before, *creates], account, realm, create_client, create_subject): return False
    cleanup = [e for e in added if e.get('operationType') != 'CREATE']
    if len(cleanup) > 1: return False
    for event in cleanup:
        auth = event.get('authDetails', {})
        if (event.get('operationType') != 'DELETE' or event.get('resourceType') != 'USER'
                or event.get('realmId') != realm or event.get('resourcePath') != 'accounts/' + account
                or event.get('representation') not in (None, '') or not isinstance(auth, dict)
                or auth.get('realmId') != realm or auth.get('clientId') != admin_client
                or auth.get('userId') != admin_subject): return False
    return True


def exact_capture(before, after, captured, pending):
    # Native ID/body values remain private; callers publish this boolean only.
    if set(captured) != {'account', 'username', 'email', 'operation'}: return False
    account = captured['account']
    if account in before or set(after) != set(before) | {account}: return False
    if any(after[key].get('user') != value.get('user') for key, value in before.items()): return False
    target = after[account]; user = target.get('user', {})
    roles = target.get('roles', {})
    return (user.get('id') == account and user.get('username') == captured['username']
        and user.get('email') == captured['email'] and user.get('firstName') == 'Tracked'
        and user.get('lastName') == 'Fixture' and user.get('enabled') is False
        and user.get('emailVerified') is True and isinstance(user.get('createdTimestamp'), int)
        and user['createdTimestamp'] > 0 and not user.get('federationLink') and not user.get('serviceAccountClientId')
        and user.get('attributes', {}).get('tenantId') == ['11']
        and not roles.get('realmMappings') and not roles.get('clientMappings')
        and [g.get('id') for g in target.get('groups', [])] == [pending])


def schema_sources(root):
    resources = root / 'src/main/resources'
    master = ET.parse(resources / 'db/changelog/userservice-master.xml').getroot()
    included = [node.get('file') for node in master]
    if any(included.count(path) != 1 for path in MIGRATIONS): raise ValueError('Exact receipt migration includes required')
    if included.index(MIGRATIONS[0]) >= included.index(MIGRATIONS[1]): raise ValueError('Receipt migration order differs')
    result = {}
    for path, expected_id in zip(MIGRATIONS, MIGRATION_IDS):
        source = resources / path
        change = ET.parse(source).getroot()
        ids = [node.get('id') for node in change if node.tag.endswith('changeSet')]
        if ids != [expected_id]: raise ValueError('Exact receipt changeset required')
        result['src/main/resources/' + path] = hashlib.sha256(source.read_bytes()).hexdigest()
    return result


@contextlib.contextmanager
def isolated_classes(root, receipt):
    directory = root / 'target/test-classes'; binary = SUITE.replace('.', '/')
    original = {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in directory.rglob('*.class')}
    keep = {name for name in original if name == binary + '.class' or name.startswith(binary + '$')}
    if binary + '.class' not in keep: raise ValueError('Compiled capture proof absent')
    with tempfile.TemporaryDirectory(prefix='tracked-class-quarantine-') as private:
        moved = Path(private)
        try:
            for name in original.keys() - keep:
                destination = moved / name; destination.parent.mkdir(parents=True, exist_ok=True)
                (directory / name).rename(destination)
            receipt['retainedClasses'] = sorted(keep)
            yield
        finally:
            for path in moved.rglob('*.class'):
                target = directory / path.relative_to(moved); target.parent.mkdir(parents=True, exist_ok=True)
                path.rename(target)
            receipt['restoredByteExact'] = original == {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
                                                       for p in directory.rglob('*.class')}


def sanitize(reports, output):
    clean = ET.Element('testsuite', name=SUITE); counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob('TEST-*.xml')); valid = len(files) == 1; names = []; frames = []
    for path in files:
        suite = ET.parse(path).getroot(); valid &= suite.get('name') == SUITE
        for case in suite.findall('testcase'):
            names.append(case.get('name')); valid &= case.get('classname') == SUITE
            child = ET.SubElement(clean, 'testcase', classname=SUITE, name=CASE if case.get('name') == CASE else 'unexpected')
            counts['tests'] += 1
            for tag, count in [('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')]:
                issue = case.find(tag)
                if issue is not None:
                    counts[count] += 1
                    ET.SubElement(child, tag, message='Private capture diagnostics omitted')
                    frames += re.findall(r'TrackedConsultantCreationNativePairProof\.java:[0-9]+', issue.text or '')
    valid &= names == [CASE]
    for key, value in counts.items(): clean.set(key, str(value))
    ET.ElementTree(clean).write(output, encoding='utf-8', xml_declaration=True)
    return {**counts, 'exactInventory': valid, 'sourceFrames': sorted(set(frames)),
            'accepted': valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)}


def private_json(path, value):
    pending = path.with_suffix('.pending')
    fd = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream: json.dump(value, stream)
    pending.rename(path)


def native(kc, report):
    if platform.system() != 'Linux' or os.environ.get('GITHUB_ACTIONS') != 'true': raise ValueError('Fresh hosted Linux required')
    if os.environ.get('STARTUP_CURRENT_CLIENT_BROWSER') is not None: raise ValueError('Independent capture job only')
    module = kc / 'keycloak-image/account-lifecycle-spi'
    jars = {'LIFECYCLE_C_TEST_JAR': module / 'target/account-lifecycle-spi-1.0-SNAPSHOT.jar',
            'OTP_C_TEST_JAR': kc / 'keycloak-image/otp-config-spi/target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar',
            'SCOPED_OTP_READY_FIXTURE_JAR': module / 'target/scoped-otp-ready-fixture.jar'}
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    before = base.snapshot(kc)
    if before != json.loads(report.with_name(PREFIX + 'before.json').read_text()): raise ValueError('Source changed after snapshot')
    proof_class = ROOT / 'target/test-classes' / (SUITE.replace('.', '/') + '.class')
    classes = [proof_class, *proof_class.parent.glob(proof_class.stem + '$*.class'), *[
        ROOT / 'target/classes/de/caritas/cob/userservice/api' / (name + '.class') for name in [
            'UserServiceApplication', 'adapters/web/controller/UserAdminController',
            'admin/service/consultant/create/AccountCreationAttemptJournal',
            'admin/service/consultant/create/CreateConsultantSaga',
            'service/creationoutcome/CreationOperationWriter', 'adapters/persistence/creation/JdbcCreationOperationStore',
            'adapters/keycloak/lifecycle/OrdinaryAccountCreation',
            'adapters/keycloak/lifecycle/AccountLifecycleCoordinator',
            'adapters/keycloak/lifecycle/JdbcAccountLifecycleJournal']]]
    compiled = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in classes}
    sys.path.insert(0, str(module / 'tests'))
    import lifecycle_fixture as lifecycle
    from protected_resources import evidence, require_active
    from test_protected_invitations import ISSUER, HUMAN_PASSWORD
    if lifecycle.IMAGE != startup.IMAGE: raise ValueError('Exact native image required')
    role_spec = importlib.util.spec_from_file_location('tracked_role_source', ROOT / 'scripts/ci/role-maintenance-native.py')
    role = importlib.util.module_from_spec(role_spec); role_spec.loader.exec_module(role)
    Fixture = startup.startup_fixture_class(kc, jars, False, role)
    saved_env = {key: os.environ.get(key) for key in jars}
    for key, path in jars.items(): os.environ[key] = str(path.resolve())
    catalog = base.source_frame_catalog([Path(__file__), ROOT / 'scripts/ci/application-startup-native-pair.py',
        *[module / 'tests' / name for name in ['lifecycle_fixture.py', 'protected_account_operations.py', 'protected_peer_readiness.py']]])
    receipt = {'phase': 'fixture-setup', 'accepted': False, 'executed': 0, 'before': before,
               'jars': hashes, 'compiled': compiled, 'schema': schema_sources(ROOT), 'image': startup.IMAGE,
               'claim': 'PUBLIC_NATIVE_CAPTURE_FAILURE_RECEIPT_REPLAY_ONLY',
               'limits': ['NO_SUCCESSFUL_ONBOARDING', 'NO_MATRIX_REGISTRATION_OR_MAIL', 'H2_NOT_MARIADB',
                          'FINITE_TENANT_HTTP_NOT_NATIVE_TENANT', 'NO_STABLE_FINAL_OPEN_CLAIM'],
               'environmentOrigins': {'native': 'Existing five identities/sole key and identical installed policies',
                   'tenant': 'Owned exact single-tenant11 and recovery/inactivity policy GET responses; no licensing path',
                   'matrix': 'Owned nonce GET latch below unchanged10s client timeout, then503; no registration POST',
                   'sql': 'Fresh fixed H2 mem database; guarded empty creation table replaced by two actual XML migrations',
                   'actorData': 'Existing primary SUPER and existing SELF user;18month/rev1 SQL activity fixture only'}}
    try:
        schema = startup.journal_fixture(report)
        Fixture.setUpClass(); fixture = Fixture('runTest')
        receipt['runtimeAudienceFixture'] = install_tracked_runtime_audiences(fixture, lifecycle, ISSUER)
        def settings():
            values = json.loads(subprocess.run(['docker', 'inspect', '--format', '{{json .Config.Env}}', fixture.name],
                                check=True, capture_output=True, text=True, timeout=15).stdout)
            return dict(value.split('=', 1) for value in values)
        installed = settings(); protected = installed['ORISO_PROTECTED_INVITATIONS_CONFIG']
        ordinary = installed['ORISO_ACCOUNT_MAINTENANCE_CONFIG']; groups = installed['ORISO_ACCOUNT_LIFECYCLE_GROUPS']
        def declarations():
            actual = settings()
            if (json.loads(protected) != Fixture.startup_protected_configuration
                    or json.loads(ordinary) != Fixture.ordinary_installed_configuration
                    or json.loads(groups) != Fixture.startup_reviewed_groups): return False
            if any(actual[key] != installed[key] for key in ['ORISO_PROTECTED_INVITATIONS_CONFIG',
                        'ORISO_ACCOUNT_MAINTENANCE_CONFIG', 'ORISO_ACCOUNT_LIFECYCLE_GROUPS']): return False
            for profile in [*json.loads(protected)['profiles'], *json.loads(ordinary)['ordinaryProfiles']]:
                group = profile.get('groupId') or fixture.group_id(profile['groupPath'])
                roles = fixture.admin('GET', '/groups/' + group + '/role-mappings/realm/composite')
                if {r['name'] for r in roles} != set(profile['realmRoles']): return False
            return True
        receipt['declarationsBefore'] = declarations()
        if not receipt['declarationsBefore']: raise ValueError('Exact installed declarations required')
        bearer = role.verify_canonical_platform_human(fixture, lifecycle, ISSUER, HUMAN_PASSWORD)
        primary = fixture.admin('GET', '/users/' + fixture.human_subject)
        from adopted_self_operations import login
        other_id, other_password = Fixture.adopted_self_cases['user']
        other_native = fixture.admin('GET', '/users/' + other_id)
        other_bearer = login(fixture, fixture, other_native, other_password)
        realm = fixture.admin('GET', ''); realm['adminEventsEnabled'] = True
        realm['adminEventsDetailsEnabled'] = False; fixture.admin('PUT', '', realm)
        def accounts():
            users = fixture.admin('GET', '/users?max=500')
            if not 0 < len(users) < 500: raise ValueError('Bounded native inventory required')
            return {user['id']: {'user': user,
                'groups': fixture.admin('GET', '/users/' + user['id'] + '/groups'),
                'roles': fixture.admin('GET', '/users/' + user['id'] + '/role-mappings'),
                'credentials': fixture.admin('GET', '/users/' + user['id'] + '/credentials')} for user in users}
        pending_id = fixture.group_id('/pending-provisioning')
        original = accounts(); events = fixture.admin('GET', '/admin-events?max=500')
        env = dict(os.environ)
        env.update({'TASK_IDENTITY_MODEL': 'FIVE_ACTORS', 'ORISO_ACCOUNT_LIFECYCLE_ENABLED': 'true',
            'ORISO_ACCOUNT_LIFECYCLE_REALM': lifecycle.REALM, 'KEYCLOAK_REALM': lifecycle.REALM,
            'KEYCLOAK_REALM_INTERNAL_ID': fixture.realm_id,
            'KEYCLOAK_AUTH_SERVER_URL': ISSUER.removesuffix('/realms/' + lifecycle.REALM),
            'ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL': fixture.base,
            'IDENTITY_OPENID_CONNECT_URL': fixture.base + '/realms/' + lifecycle.REALM + '/protocol/openid-connect',
            'ORISO_TASK_CONTEXT_KEY': __import__('base64').b64encode(lifecycle.CONTEXT_KEY).decode(),
            'ORISO_ACCOUNT_LIFECYCLE_GROUPS': groups, 'ORISO_IDENTITY_HUMAN_CLIENT_IDS': '["protected-human-web"]',
            'ORISO_ACCOUNT_MAINTENANCE_CONFIG': ordinary, 'ORISO_PROTECTED_INVITATIONS_CONFIG': protected,
            'ORISO_PROTECTED_INVITATION_PROFILE_IDS': json.dumps({'TENANT_ADMIN': 'tenant-peer-v1', 'PLATFORM_ADMIN': 'platform-v1'}),
            'STARTUP_JOURNAL_FIXTURE': str(schema.resolve()), 'MATRIX_SERVER_NAME': 'startup-fixture.invalid',
            'SPRING_DATASOURCE_URL': f'jdbc:h2:mem:tracked-native-{uuid.uuid4()};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY',
            'ACCOUNT_INACTIVITY_ENABLED': 'true', 'TRACKED_CREATION_WITNESS': str(report.with_name(PREFIX + 'witness.json').resolve()),
            'STARTUP_BEAN_CATALOG': json.dumps(startup.BEAN_TYPES),
            'STARTUP_TEST_CLASSES': str((ROOT / 'target/test-classes').resolve()),
            'STARTUP_PRODUCTION_CLASSES': str((ROOT / 'target/classes').resolve())})
        for actor, (client, _) in lifecycle.ACTORS.items():
            env.update({'IDENTITY_' + actor + '_CLIENT_ID': client, 'IDENTITY_' + actor + '_CLIENT_SECRET': 'synthetic-lifecycle-' + actor,
                        'IDENTITY_' + actor + '_SERVICE_SUBJECT': lifecycle.SUBJECTS[actor]})
        reports = ROOT / 'target/surefire-reports'
        for path in reports.glob('TEST-*.xml'): path.unlink()
        require_active('before-tracked-public-creation')
        receipt['phase'] = 'actual-public-creation'; receipt['classIsolation'] = {}
        phases = {'bootNoEffect': False, 'captureExact': False, 'auditExact': False, 'failure': False}
        with tempfile.TemporaryDirectory(prefix='oriso-tracked-private-') as private:
            exchange = Path(private); os.chmod(exchange, 0o700); env['TRACKED_CREATION_PRIVATE_EXCHANGE'] = str(exchange)
            finished = threading.Event(); captured_target = {}
            def await_marker(name, deadline):
                while not (exchange / name).exists():
                    if finished.wait(0.025) or time.monotonic() >= deadline: raise TimeoutError('Private proof phase not reached')
                return exchange / name
            def observe():
                try:
                    deadline = time.monotonic() + 175
                    marker = await_marker('boot-ready', deadline)
                    if marker.read_text() != 'BOOT_READY': raise ValueError('Exact boot marker required')
                    phases['bootNoEffect'] = original == accounts() and events == fixture.admin('GET', '/admin-events?max=500')
                    if not phases['bootNoEffect']: raise ValueError('Boot changed native state')
                    private_json(exchange / 'boot-verified.json', {'bootNoEffect': True, 'primary': primary,
                                 'primaryBearer': bearer, 'otherBearer': other_bearer, 'other': other_native})
                    captured = json.loads(await_marker('captured.json', deadline).read_text())
                    captured_target.update(captured)
                    users = fixture.admin('GET', '/users?max=500')
                    if not 0 < len(users) < 500: raise ValueError('Bounded checkpoint inventory required')
                    checkpoint = {user['id']: {'user': user} for user in users}
                    target = captured['account']
                    if target in checkpoint:
                        checkpoint[target].update(groups=fixture.admin('GET', '/users/' + target + '/groups'),
                            roles=fixture.admin('GET', '/users/' + target + '/role-mappings'),
                            credentials=fixture.admin('GET', '/users/' + target + '/credentials'))
                    phases['captureExact'] = exact_capture(original, checkpoint, captured, pending_id)
                    phases['auditExact'] = exact_create_audit(events, fixture.admin('GET', '/admin-events?max=500'),
                        captured['account'], fixture.realm_id, lifecycle.ACTORS['ACCOUNT_CREATE'][0], lifecycle.SUBJECTS['ACCOUNT_CREATE'])
                    if not phases['captureExact'] or not phases['auditExact']: raise ValueError('Exact captured native checkpoint required')
                    private_json(exchange / 'capture-verified.json', {'nativeCheckpoint': True})
                except BaseException:
                    phases['failure'] = True
            observer = threading.Thread(target=observe, daemon=True); observer.start()
            try:
                with isolated_classes(ROOT, receipt['classIsolation']):
                    child = subprocess.run(['mvn', '-B', '-ntp', '-q', 'surefire:test', '-Dtest=' + SUITE + '#' + CASE,
                        '-DfailIfNoTests=true', '-Dsurefire.failIfNoSpecifiedTests=true',
                        '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2'],
                        cwd=ROOT, env=env, capture_output=True, timeout=180)
                receipt['childExit'] = child.returncode; child = None
            finally:
                finished.set(); observer.join(timeout=5)
            if observer.is_alive(): raise ValueError('Native observer did not stop')
            diagnostic = exchange / 'nonce-diagnostic.json'
            if diagnostic.exists():
                if diagnostic.stat().st_size > 4096: raise ValueError('Bounded nonce diagnostic required')
                receipt['nonceDiagnostic'] = checked_nonce_diagnostic(json.loads(diagnostic.read_text()))
            failure = exchange / 'java-failure.json'
            if failure.exists():
                if failure.stat().st_size > 65536: raise ValueError('Bounded private exception metadata required')
                receipt['javaFailure'] = checked_java_failure_frames(
                    json.loads(failure.read_text()), ROOT, before['user_sources'])
        receipt['nativeCheckpoint'] = phases
        inventory = sanitize(reports, report.with_suffix('.xml')); receipt['inventory'] = inventory; receipt['executed'] = inventory['tests']
        witness = json.loads(report.with_name(PREFIX + 'witness.json').read_text())
        receipt['witnessComplete'] = checked_witness(witness)
        require_active('after-tracked-public-creation')
        receipt['declarationsAfter'] = declarations(); receipt['resourceObservations'] = evidence()
        final = accounts(); final_events = fixture.admin('GET', '/admin-events?max=500')
        receipt['originalAccountsUnchangedAfter'] = (all(final.get(key) == value for key, value in original.items())
            and set(final) <= set(original) | {captured_target['account']})
        # Recovery may run after release. Never present terminal target state as OPEN or compensation.
        receipt['exactlyOneNativeCreateAfter'] = final_creation_audit(events, final_events, captured_target['account'],
            fixture.realm_id, lifecycle.ACTORS['ACCOUNT_CREATE'][0], lifecycle.SUBJECTS['ACCOUNT_CREATE'],
            lifecycle.ACTORS['ACCOUNT_ADMIN'][0], lifecycle.SUBJECTS['ACCOUNT_ADMIN'])
        original_events = {event['id']: event for event in events}
        final_by_id = {event.get('id'): event for event in final_events}
        receipt['originalEventsRetained'] = (len(final_by_id) == len(final_events)
            and all(final_by_id.get(key) == value for key, value in original_events.items()))
        receipt['accepted'] = (inventory['accepted'] and receipt['childExit'] == 0 and receipt['witnessComplete']
            and all(phases[k] for k in ['bootNoEffect', 'captureExact', 'auditExact']) and not phases['failure']
            and receipt['declarationsAfter'] and receipt['originalAccountsUnchangedAfter']
            and receipt['exactlyOneNativeCreateAfter'] and receipt['originalEventsRetained']
            and receipt['classIsolation']['restoredByteExact'])
        receipt['phase'] = 'complete'
    except BaseException as error:
        receipt['exceptionCategory'] = type(error).__name__; receipt['accepted'] = False
        receipt['failureFrames'] = base.sanitized_failure_frames(error, catalog)
        if isinstance(error, KeyboardInterrupt): raise
    finally:
        try:
            Fixture.doClassCleanups()
            if getattr(Fixture, 'tearDown_exceptions', []): receipt['accepted'] = False
        except BaseException:
            receipt['cleanupFailed'] = True; receipt['accepted'] = False
        for key, value in saved_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        receipt['after'] = base.snapshot(kc); receipt['sourceStable'] = before == receipt['after']
        receipt['jarStable'] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        receipt['compiledStable'] = compiled == {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in classes}
        receipt['accepted'] &= receipt['sourceStable'] and receipt['jarStable'] and receipt['compiledStable']
        base.save(report, receipt)
    return 0 if receipt['accepted'] else 1


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('mode', choices=['snapshot', 'native', 'finalize'])
    parser.add_argument('--keycloak', type=Path, required=True)
    parser.add_argument('--report', type=Path, default=ROOT / 'target/tracked-creation-native/tracked-creation-native-result.json')
    args = parser.parse_args()
    if args.mode == 'snapshot':
        if subprocess.check_output(['git', '-C', str(args.keycloak), 'rev-parse', 'HEAD'], text=True).strip() != KC_COMMIT:
            raise ValueError('Exact reviewed native source required')
        base.save(args.report.with_name(PREFIX + 'before.json'), base.snapshot(args.keycloak)); return 0
    if args.mode == 'native': return native(args.keycloak.resolve(), args.report)
    if not args.report.exists(): base.save(args.report, {'phase': 'not-executed', 'executed': 0, 'accepted': False})
    return 0 if json.loads(args.report.read_text()).get('accepted') is True else 1


if __name__ == '__main__': raise SystemExit(main())
