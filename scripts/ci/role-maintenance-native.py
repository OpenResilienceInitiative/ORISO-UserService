"""One fixed ADD_AGENCY public/JPA/native helper for the existing maintenance native runner."""
import base64
import copy
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
from unittest.mock import patch
import xml.etree.ElementTree as ET

SUITE = 'de.caritas.cob.userservice.api.service.accountinvite.UserRoleMaintenanceNativePairProof'
CASE = 'publicCombinedAgencyRoleKeepsNativeAndSqlOwnershipAcrossLossRevocationAndRestart'
KIND = 'role-agency'
FLAGS = {'actualJpa', 'actualPublicRoleCaller', 'actualCurrentHuman', 'canonicalAdmin',
    'preparedBeforeRead', 'nativeReplyLostAfter200', 'sameFrozenCommand',
    'nativeAcknowledgedBeforeLocal', 'oneLocalCallback', 'combinedRolesPreserved',
    'wholeSqlPreserved', 'savedReplay200', 'readAfterWithdrawal200',
    'savedAfterWithdrawal403', 'sourceDriftBeforeRpcDenied', 'coldRestartSameReceipt',
    'restoredReplay200'}


def checked_witness(value):
    if (not isinstance(value, dict) or set(value) != FLAGS | {'kind'} or value['kind'] != KIND
            or any(type(value[key]) is not bool for key in FLAGS)):
        raise ValueError('Exact closed role-agency witness required')
    return all(value[key] for key in FLAGS)


def withdrawn_policy(original):
    """Only the explicit logical human transition grant changes; read/profile/incarnation stay."""
    result = copy.deepcopy(original)
    if not isinstance(result, dict) or not isinstance(result.get('adminOperations'), list):
        raise ValueError('Actual installed ordinary policy required')
    changed = 0
    for grant in result['adminOperations']:
        operations = grant.get('operations')
        if not isinstance(operations, list) or len(operations) != len(set(operations)):
            raise ValueError('Unique explicit operation array required')
        if 'account.profile-transition' in operations:
            operations.remove('account.profile-transition'); changed += 1
        if 'account.read' not in operations:
            raise ValueError('Independent read grant must remain')
    if changed == 0:
        raise ValueError('Actual transition grant must exist before withdrawal')
    restored = copy.deepcopy(result)
    restored['adminOperations'] = copy.deepcopy(original['adminOperations'])
    if restored != original:
        raise ValueError('Withdrawal changed unrelated installed source')
    return result


def sanitize(reports, output):
    clean = ET.Element('testsuite', name=SUITE)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    names = []; frames = []; valid = True
    paths = sorted(Path(reports).glob('TEST-*.xml'))
    valid &= len(paths) == 1
    for path in paths:
        suite = ET.parse(path).getroot(); valid &= suite.get('name') == SUITE
        for case in suite.findall('testcase'):
            names.append(case.get('name')); valid &= case.get('classname') == SUITE
            child = ET.SubElement(clean, 'testcase', classname=SUITE, name=case.get('name') or 'missing')
            counts['tests'] += 1
            for tag, key in [('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')]:
                problem = case.find(tag)
                if problem is not None:
                    counts[key] += 1
                    ET.SubElement(child, tag, message='Sensitive paired diagnostic omitted')
                    frames += re.findall(r'UserRoleMaintenanceNativePairProof\.java:[0-9]+', problem.text or '')
        for key, count in counts.items():
            valid &= suite.get(key) is None or int(suite.get(key)) == count
    valid &= names == [CASE]
    for key, value in counts.items(): clean.set(key, str(value))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding='utf-8', xml_declaration=True)
    return {**counts, 'exact_method_inventory': valid, 'failure_locations': sorted(set(frames)),
        'accepted': valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)}


def native(kc, report, driver):
    """Called only by the existing source-bound runner; no CLI, deployment or alternative protocol."""
    if platform.system() != 'Linux' or os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('PAIR_KIND') != KIND:
        raise ValueError('Fresh exact hosted Linux role-agency fixture required')
    import hashlib
    before = driver.snapshot(kc)
    if before != json.loads(report.with_name('self-maintenance-native-before.json').read_text()):
        raise ValueError('Both built source checkouts must match original frame')
    available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))
    if available < 4 * 1024 * 1024: raise ValueError('Existing four GiB headroom/two GiB reserve required')
    root = driver.ROOT; module = kc / 'keycloak-image/account-lifecycle-spi'; otp = kc / 'keycloak-image/otp-config-spi'
    jars = {'LIFECYCLE_C_TEST_JAR': module / 'target/account-lifecycle-spi-1.0-SNAPSHOT.jar',
        'OTP_C_TEST_JAR': otp / 'target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar'}
    if not all(path.is_file() for path in jars.values()): raise ValueError('Actual paired provider jars required')
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    classes = ['target/test-classes/' + SUITE.replace('.', '/') + '.class'] + [
        'target/classes/de/caritas/cob/userservice/api/' + path + '.class' for path in [
            'adapters/web/controller/ConsultantRoleController', 'service/accountinvite/AccountRoleAddition',
            'service/accountinvite/AccountRoleAddition$ScopedAddition', 'service/accountinvite/CounsellorAgencyAdminGrantService',
            'service/accountinvite/AccountInviteAccessPolicy', 'service/identity/HumanAdminAccountMaintenance',
            'service/identity/ReviewedOrdinaryGroups', 'service/identity/ConsultantProfileSqlSnapshot',
            'adapters/keycloak/maintenance/KeycloakHumanAdminMaintenance', 'adapters/keycloak/lifecycle/AccountLifecycleCodec',
            'config/auth/TaskActorTokenBroker']]
    if not all((root / path).is_file() for path in classes): raise ValueError('Actual compiled public/owned caller required')
    compiled = {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in classes}
    sys.path.insert(0, str(module / 'tests'))
    import lifecycle_fixture as lifecycle
    from protected_otp_bootstrap import BootstrapFixture
    from protected_account_operations import OperationFixture, ProtectedOperationRead
    from ordinary_admin_operations import install_policy
    from profile_transition_operations import committed_kind
    from test_protected_invitations import ISSUER, HUMAN_PASSWORD
    if lifecycle.IMAGE != driver.IMAGE: raise ValueError('Exact pinned native image required')
    frames = driver.source_frame_catalog([Path(__file__), *[module / 'tests' / name for name in [
        'lifecycle_fixture.py', 'test_protected_invitations.py', 'protected_otp_bootstrap.py',
        'protected_account_operations.py', 'ordinary_admin_operations.py', 'profile_transition_operations.py']]])

    class RolePair(BootstrapFixture):
        native_issuer = ISSUER
        installer_claims = staticmethod(OperationFixture.installer_claims)
        check_installer_identity = OperationFixture.check_installer_identity

        @classmethod
        def prepare_installation(cls, helper, configuration):
            # Reuse the actual ordinary profiles/grants. No OTP READY observer or diagnostic write.
            install_policy(cls, helper, configuration)
            return configuration

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

    receipt = {'kind': KIND, 'case': CASE, 'phase': 'fixture-setup', 'accepted': False,
        'executed': 0, 'genuine_behavior_red': False, 'before': before, 'jars': hashes,
        'compiled_user_classes': compiled, 'image': driver.IMAGE}
    original_env = {key: os.environ.get(key) for key in jars}
    for key, path in jars.items(): os.environ[key] = str(path.resolve())
    control = None; stage = ['ORIGINAL']; lock = threading.Lock(); controls = []; launches = []
    original_run = subprocess.run
    def capture(args, *positional, **keywords):
        if isinstance(args, list) and args[:2] == ['docker', 'run']: launches.append(args.copy())
        return original_run(args, *positional, **keywords)
    try:
        with patch.object(subprocess, 'run', side_effect=capture): RolePair.setUpClass()
        if not launches: raise ValueError('Actual configured native launch absent')
        configured = launches[-1]
        fixture = RolePair('runTest'); fixture.human(record=False)
        account, creation = committed_kind(fixture, fixture, 'CONSULTANT_AGENCY_ADMIN', 'consultant-agency-admin')
        fixture.account_id = account; fixture.creation_input = creation
        native_user = fixture.admin('GET', '/users/' + account)
        original_native = copy.deepcopy(native_user)
        realm = fixture.admin('GET', ''); realm['adminEventsEnabled'] = True; realm['adminEventsDetailsEnabled'] = False
        fixture.admin('PUT', '', realm)
        prefix = 'ORISO_ACCOUNT_MAINTENANCE_CONFIG='
        installed = [arg for arg in configured if arg.startswith(prefix)]
        if len(installed) != 1: raise ValueError('One actual installed ordinary policy required')
        original_policy = json.loads(installed[0][len(prefix):]); withdrawn = withdrawn_policy(original_policy)

        def restart(action):
            with lock:
                if (action, stage[0]) not in {('withdraw', 'ORIGINAL'), ('restore', 'WITHDRAWN')}:
                    raise ValueError('Finite grant control transition required')
                policy = withdrawn if action == 'withdraw' else original_policy
                command = [prefix + json.dumps(policy, separators=(',', ':')) if arg.startswith(prefix) else arg for arg in configured]
                if action == 'restore' and command != configured: raise ValueError('Exact original launch must be restored')
                before_identity = fixture.admin('GET', '/users/' + account)
                subprocess.run(['docker', 'stop', '-t', '5', RolePair.name], check=True, capture_output=True, timeout=30)
                subprocess.run(['docker', 'rm', RolePair.name], check=True, capture_output=True, timeout=30)
                subprocess.run(command, check=True, capture_output=True, timeout=30)
                RolePair.refresh_endpoint('role-grant-' + action); RolePair.ready(); fixture.installer = RolePair.installer
                if before_identity != fixture.admin('GET', '/users/' + account): raise ValueError('Native original incarnation changed')
                stage[0] = 'WITHDRAWN' if action == 'withdraw' else 'RESTORED'
                controls.append({'action': action, 'sameOriginalProfileAndNativeIncarnation': True,
                    'samePersistedOwnedContainer': True, 'onlyLogicalAdminTransitionGrantChanged': True})
                return {'nativeBase': RolePair.base, 'stage': stage[0]}

        class Control(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                if self.path not in {'/withdraw', '/restore'} or self.headers.get('Content-Length', '0') != '0':
                    self.send_response(400); self.end_headers(); return
                try: value = restart(self.path[1:]); status = 200
                except BaseException as error:
                    receipt['control_failure_category'] = type(error).__name__
                    receipt['control_failure_frames'] = driver.sanitized_failure_frames(error, frames)
                    value = {}; status = 503
                raw = json.dumps(value).encode(); self.send_response(status)
                self.send_header('Content-Type', 'application/json'); self.end_headers(); self.wfile.write(raw)
        control = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Control)
        threading.Thread(target=control.serve_forever, daemon=True).start()
        environment = dict(os.environ)
        environment.update({'PAIR_KIND': KIND, 'PAIR_NATIVE_BASE': fixture.base, 'PAIR_REALM': lifecycle.REALM,
            'PAIR_REALM_ID': fixture.realm_id, 'PAIR_ISSUER': ISSUER, 'PAIR_ACCOUNT_ID': account,
            'PAIR_USERNAME': native_user['username'], 'PAIR_EMAIL': native_user['email'],
            'PAIR_FIRST_NAME': native_user['firstName'], 'PAIR_LAST_NAME': native_user['lastName'],
            'PAIR_TENANT': native_user['attributes']['tenantId'][0], 'PAIR_HUMAN_SUBJECT': fixture.human_subject,
            'PAIR_HUMAN_USERNAME': 'reviewed-human', 'PAIR_HUMAN_PASSWORD': HUMAN_PASSWORD,
            'PAIR_CONTEXT_KEY_B64': base64.b64encode(lifecycle.CONTEXT_KEY).decode(),
            'PAIR_CONTROL_BASE': 'http://127.0.0.1:' + str(control.server_address[1]),
            'PAIR_WITNESS': str(report.with_name('role-maintenance-native-witness.json').resolve())})
        for actor, (client, _) in lifecycle.ACTORS.items():
            environment.update({'PAIR_' + actor + '_CLIENT': client,
                'PAIR_' + actor + '_SECRET': 'synthetic-lifecycle-' + actor,
                'PAIR_' + actor + '_SUBJECT': lifecycle.SUBJECTS[actor]})
        reports = root / 'target/surefire-reports'
        for path in reports.glob('TEST-*.xml'): path.unlink()
        receipt['phase'] = 'actual-public-jpa-native-role-pair'
        child = subprocess.run(['mvn', '-B', '-ntp', '-q', 'surefire:test', '-Dtest=' + SUITE + '#' + CASE,
            '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2'], cwd=root,
            env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
        receipt['child_exit'] = child.returncode
        inventory = sanitize(reports, report.with_suffix('.xml')); receipt.update(inventory); receipt['executed'] = inventory['tests']
        # Raw native tokens, request bodies and diagnostic messages never enter exported proof.
        for path in reports.glob('*'):
            if path.is_file(): path.unlink()
        witness = json.loads(Path(environment['PAIR_WITNESS']).read_text())
        receipt['java_witness_complete'] = checked_witness(witness)
        if stage[0] != 'RESTORED' or len(controls) != 2: raise ValueError('Both finite native grant transitions required')
        if original_native != fixture.admin('GET', '/users/' + account): raise ValueError('Combined native account scope/profile changed')
        events = fixture.admin('GET', '/admin-events?resourcePath=accounts/' + account + '&max=100')
        actual = [event for event in events if event.get('resourcePath') == 'accounts/' + account
            and event.get('operationType') == 'UPDATE' and event.get('resourceType') == 'USER'
            and event.get('authDetails', {}).get('clientId') == lifecycle.ACTORS['ACCOUNT_ADMIN'][0]
            and event.get('authDetails', {}).get('userId') == lifecycle.SUBJECTS['ACCOUNT_ADMIN']]
        receipt['one_actual_native_effect_event'] = len(actual) == 1 and all(event.get('representation') is None for event in actual)
        receipt['controls'] = controls
        receipt['installer_identity_checks'] = ProtectedOperationRead.observation.get('installerIdentityChecks', [])
        identity = receipt['installer_identity_checks']
        receipt['installer_identity_verified_every_restart'] = len(identity) >= 4 and all(check.get('sameActualEnabledMasterInstallerAndNativeSessionVerified') is True for check in identity)
        receipt['accepted'] = inventory['accepted'] and child.returncode == 0 and receipt['java_witness_complete'] and receipt['one_actual_native_effect_event'] and receipt['installer_identity_verified_every_restart']
        receipt['phase'] = 'complete'
    except BaseException as error:
        receipt['exception_category'] = type(error).__name__; receipt['accepted'] = False
        receipt['failure_frames'] = driver.sanitized_failure_frames(error, frames)
        if isinstance(error, KeyboardInterrupt): raise
    finally:
        if control is not None: control.shutdown(); control.server_close()
        RolePair.doClassCleanups()
        receipt['cleanup_verified'] = not RolePair.tearDown_exceptions
        for key, value in original_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        after = driver.snapshot(kc)
        receipt['source_stable'] = before == after
        receipt['jar_stable'] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        receipt['compiled_user_stable'] = compiled == {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in classes}
        receipt['accepted'] &= receipt['cleanup_verified'] and receipt['source_stable'] and receipt['jar_stable'] and receipt['compiled_user_stable']
        driver.save(report, receipt)
    return receipt
