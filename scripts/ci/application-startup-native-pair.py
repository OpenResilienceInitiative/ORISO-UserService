#!/usr/bin/env python3
"""Actual full application startup; finite FENCED/OPEN evidence, no raw output retained."""
import argparse
import base64
import copy
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[2]
KC_COMMIT = '9b575dedb7eefaa509c023042ad534da6d1926cf'
SUITE = 'de.caritas.cob.userservice.api.UserServiceStartupNativePairProof'
CASE = 'actualServletApplicationReachesOnlyExpectedProductionStartupState'
JAVA_SOURCE = 'src/test/java/' + SUITE.replace('.', '/') + '.java'
COMMON_FLAGS = {'productionClasspath', 'actualApplication', 'testingProfile', 'fiveActors', 'realPolicies', 'contextClosed', 'noUnexpectedFailure'}
OPEN_FLAGS = {'contextStarted', 'servletReady', 'securityReady', 'jpaReady', 'scopedOwnersReady', 'bothSchedulersReady', 'noLegacyFallback', 'schemaReady'}

IMAGE = 'quay.io/keycloak/keycloak@sha256:9b0330756022422149aa6502eb2def8cd47c6e1b000c7c65cdb13e7c0133e992'
BEAN_TYPES = {
    'serviceIdentitySeparationCheck': 'config.auth.ServiceIdentitySeparationCheck',
    'configurationValidator': 'config.ConfigurationValidator',
    'securityConfig': 'config.auth.SecurityConfig',
    'jwtDecoder': 'org.springframework.security.oauth2.jwt.NimbusJwtDecoder',
    'filterChain': 'org.springframework.security.web.DefaultSecurityFilterChain',
    'taskActorConfiguration': 'config.auth.TaskActorConfiguration',
    'taskActorTokenBroker': 'config.auth.TaskActorTokenBroker',
    'taskAwareIdentityClientConfig': 'config.auth.TaskAwareIdentityClientConfig',
    'accountLifecycleCommands': 'adapters.keycloak.lifecycle.KeycloakAccountLifecycleCommands',
    'accountLifecycleCoordinator': 'adapters.keycloak.lifecycle.AccountLifecycleCoordinator',
    'ordinaryRegistrationLifecycle': 'adapters.keycloak.lifecycle.OrdinaryRegistrationLifecycle',
    'ordinaryAccountCreation': 'adapters.keycloak.lifecycle.OrdinaryAccountCreation',
    'claimedPasswordMaintenance': 'adapters.keycloak.lifecycle.ClaimedPasswordMaintenance',
    'selfPasswordMaintenance': 'adapters.keycloak.lifecycle.SelfPasswordMaintenance',
    'selfLocaleMaintenance': 'adapters.keycloak.maintenance.SelfLocaleMaintenance',
    'selfAppOtpMaintenance': 'adapters.keycloak.lifecycle.SelfAppOtpMaintenance',
    'humanAdminAccountMaintenance': 'service.identity.HumanAdminAccountMaintenance',
    'scheduledAskerMaintenance': 'workflow.delete.service.ScheduledAskerMaintenance',
    'scheduledConsultantMaintenance': 'workflow.delete.service.ScheduledConsultantMaintenance',
    'deleteUserAccountScheduler': 'workflow.delete.scheduler.DeleteUserAccountScheduler',
    'accountInactivityScheduler': 'workflow.accountinactivity.AccountInactivityScheduler',
    'accountLifecycleRetryScheduler': 'adapters.keycloak.lifecycle.AccountLifecycleRetryScheduler',
    'existingSetupPasswordClaims': 'adapters.persistence.password.JdbcExistingSetupPasswordClaims',
    'reviewedMaintenanceProfiles': 'adapters.keycloak.lifecycle.ReviewedMaintenanceProfiles',
    'protectedInvitationProfiles': 'service.accountinvite.ProtectedInvitationProfiles',
    'transactionManager': 'org.springframework.orm.jpa.JpaTransactionManager',
    'entityManagerFactory': 'org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean',
    'dataSource': 'com.zaxxer.hikari.HikariDataSource',
}
FAILURE_TYPES = {'BeanCreationException', 'UnsatisfiedDependencyException', 'IllegalStateException',
    'BeanCurrentlyInCreationException', 'BeanDefinitionStoreException', 'IllegalArgumentException',
    'NoSuchBeanDefinitionException', 'BeanInstantiationException', 'ScriptStatementFailedException',
    'JdbcSQLSyntaxErrorException', 'NoUniqueBeanDefinitionException', 'BeanNotOfRequiredTypeException',
    'AopConfigException', 'PlaceholderResolutionException', 'MissingRequiredPropertiesException',
    'ApplicationContextException', 'ClassNotFoundException', 'NoClassDefFoundError', 'NoSuchMethodException', 'OTHER'}
base_spec = importlib.util.spec_from_file_location('existing_self_pair', ROOT / 'scripts/ci/self-maintenance-native-pair.py')
base = importlib.util.module_from_spec(base_spec)
base_spec.loader.exec_module(base)


def safe_child_input(raw):
    # Read captured child text only in memory; never export SQL, exception text, paths or values.
    text = raw.decode('utf-8', errors='replace')
    resources = {'database/UserServiceDatabase.sql': 'USER_FIXTURE',
        'database/AccountInactivityDatabase.sql': 'INACTIVITY_FIXTURE',
        'application-startup-native-journals.sql': 'C_JOURNAL_FIXTURE'}
    matches = []
    for number, location in re.findall(r'statement #([1-9][0-9]{0,4}) of (?:URL|class path resource) \[([^\]]+)\]', text):
        for name, kind in resources.items():
            if location.endswith(name): matches.append((kind, int(number)))
    if not matches or len(set(matches)) != 1: return {}
    kind, number = matches[0]
    codes = sorted({int(value) for value in re.findall(r'\[([0-9]{4,6})-[0-9]{3}\]', text)
        if int(value) in {23505, 42000, 42001, 42101, 42102, 42111, 42121, 42122, 42142, 42151, 90079}})
    return {'resource': kind, 'statement': number, 'vendorErrorCodes': codes[:10]}


def safe_dependency_path(raw):
    # Only identifiers independently present in tracked production Java, never arbitrary bean labels.
    known = set()
    for source in (ROOT / 'src/main/java').rglob('*.java'):
        text = source.read_text()
        known.update(name[0].lower() + name[1:] for name in re.findall(r'\bclass\s+([A-Z][A-Za-z0-9_]*)', text))
        known.update(re.findall(r'\b([a-z][A-Za-z0-9_]*)\s*\(', text))
    names = re.findall(r"bean with name '([A-Za-z][A-Za-z0-9_]*)'", raw.decode('utf-8', errors='replace'))
    result = []
    for name in names:
        if name in known and (not result or result[-1] != name): result.append(name)
    return result[:30]


def test_output_manifest(folder):
    result = {}
    for path in sorted(folder.rglob('*')):
        if path.is_symlink(): raise ValueError('Compiled test symlink not supported')
        if path.is_file(): result[str(path.relative_to(folder))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


@contextlib.contextmanager
def isolated_test_classes(root, observation):
    folder = root / 'target/test-classes'
    quarantine = root / 'target/application-startup-test-class-quarantine'
    if not folder.is_dir() or folder.is_symlink() or quarantine.exists():
        raise ValueError('Exact disposable compiled test output required')
    prefix = SUITE.replace('.', '/')
    original = test_output_manifest(folder)
    retained = {name: digest for name, digest in original.items()
        if not name.endswith('.class') or name == prefix + '.class'
        or re.fullmatch(re.escape(prefix) + r'\$[0-9]+\.class', name)}
    if prefix + '.class' not in retained: raise ValueError('Actual compiled proof required')
    observation.update(originalTestManifest=original, retainedTestManifest=retained, restoredByteExact=False)
    folder.rename(quarantine)
    try:
        folder.mkdir()
        for name in retained:
            target = folder / name; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(quarantine / name, target)
        if test_output_manifest(folder) != retained: raise ValueError('Isolated compiled subset mismatch')
        yield
    finally:
        if folder.exists(): shutil.rmtree(folder)
        quarantine.rename(folder)
        restored = test_output_manifest(folder)
        observation.update(restoredTestManifest=restored, restoredByteExact=restored == original)
        if restored != original: raise ValueError('Exact original compiled test restoration failed')


def startup_fixture_catalogue(configuration, groups):
    # K's independent negative suite retains its deliberately ambiguous destination alias.
    # This startup fixture installs one coherent, explicit positive catalogue in both peers.
    approved = {'consultant-v1': '/human-roles/consultant',
                'consultant-basic-v1': '/human-roles/consultant-basic',
                'user-v1': '/human-roles/user',
                'agency-admin-v1': '/human-roles/agency-admin',
                'consultant-agency-admin-v1': '/human-roles/consultant-agency-admin',
                'consultant-agency-admin-basic-v1': '/human-roles/consultant-agency-admin-basic'}
    negative = 'combined-unacknowledged-v1'
    profiles = configuration['ordinaryProfiles']
    if len(profiles) != len(approved) + 1 or {p['id'] for p in profiles} != set(approved) | {negative}:
        raise ValueError('Exact startup fixture profile inventory is required')
    alias = next(p for p in profiles if p['id'] == negative)
    full = next(p for p in profiles if p['id'] == 'consultant-agency-admin-v1')
    if alias['groupPath'] != '/human-roles/consultant-agency-admin-unacknowledged' or alias['realmRoles'] != full['realmRoles']:
        raise ValueError('Unexpected native negative fixture alias')
    result = {}
    for profile in profiles:
        if profile['id'] == negative: continue
        path = approved[profile['id']]
        if profile['groupPath'] != path or path not in groups or set(groups[path]) != set(profile['realmRoles']):
            raise ValueError('Reviewed startup profile differs from installed lifecycle group')
        result[path] = groups[path]
    if len({frozenset(roles) for roles in result.values()}) != len(result):
        raise ValueError('Reviewed startup catalogue contains ambiguous role unions')
    installed = copy.deepcopy(configuration)
    installed['ordinaryProfiles'] = [p for p in installed['ordinaryProfiles'] if p['id'] != negative]
    if negative in json.dumps(installed):
        raise ValueError('Native negative alias unexpectedly participates in startup grants')
    return installed, result


def safe_failure_types(raw):
    # Fixed library types plus independently named production exception source files only.
    families = ['org.springframework.beans.factory.', 'org.springframework.beans.',
        'org.springframework.aop.framework.', 'org.springframework.util.', 'org.springframework.context.',
        'org.springframework.core.env.', 'java.lang.', 'java.net.', 'org.h2.jdbc.']
    known = {family + name for family in families for name in FAILURE_TYPES if name != 'OTHER'}
    for path in (ROOT / 'src/main/java').rglob('*Exception.java'):
        match = re.search(r'^package ([A-Za-z0-9_.]+);', path.read_text(), re.M)
        if match: known.add(match.group(1) + '.' + path.stem)
    text = raw.decode('utf-8', errors='replace')
    values = re.findall(r'(?<![A-Za-z0-9_])(?:org|java)\.[A-Za-z0-9_.]+(?:Exception|Error)(?![A-Za-z0-9_])', text)
    return list(dict.fromkeys(value for value in values if value in known))[:30]


def checked_witness(value, state):
    flags = COMMON_FLAGS | OPEN_FLAGS | {'exactFence'}
    if (state not in {'FENCED', 'OPEN'} or not isinstance(value, dict)
            or set(value) != flags | {'state', 'observedBeans', 'failureTypes'}
            or value['state'] != state or any(type(value[key]) is not bool for key in flags)
            or not isinstance(value['observedBeans'], list)
            or value['observedBeans'] != sorted(set(value['observedBeans']))
            or any(key not in BEAN_TYPES for key in value['observedBeans'])
            or not isinstance(value['failureTypes'], list)
            or any(key not in FAILURE_TYPES for key in value['failureTypes'])):
        raise ValueError('Exact finite startup witness required')
    return (all(value[key] for key in COMMON_FLAGS)
            and value['exactFence'] == (state == 'FENCED')
            and all(value[key] == (state == 'OPEN') for key in OPEN_FLAGS)
            and (state != 'OPEN' or not value['failureTypes'])
            and (state != 'FENCED' or (len(value['failureTypes']) >= 2
                and value['failureTypes'][-1] == 'IllegalStateException'
                and all(kind in {'BeanCreationException', 'UnsatisfiedDependencyException', 'IllegalStateException'} for kind in value['failureTypes']))))


def sanitize(reports, output):
    clean = ET.Element('testsuite', name=SUITE)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob('TEST-*.xml')); names = []; valid = len(files) == 1; frames = []
    for path in files:
        suite = ET.parse(path).getroot(); valid &= suite.get('name') == SUITE
        for case in suite.findall('testcase'):
            names.append(case.get('name')); valid &= case.get('classname') == SUITE
            child = ET.SubElement(clean, 'testcase', classname=SUITE, name=CASE if case.get('name') == CASE else 'unexpected')
            counts['tests'] += 1
            for tag, key in [('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')]:
                problem = case.find(tag)
                if problem is not None:
                    counts[key] += 1
                    ET.SubElement(child, tag, message='Sensitive startup diagnostic omitted')
                    frames += re.findall(r'UserServiceStartupNativePairProof\.java:[0-9]+', problem.text or '')
        for key, count in counts.items():
            valid &= suite.get(key) is None or int(suite.get(key)) == count
    valid &= names == [CASE]
    for key, value in counts.items(): clean.set(key, str(value))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding='utf-8', xml_declaration=True)
    return {**counts, 'exact_method_inventory': valid, 'failure_locations': sorted(set(frames)),
            'accepted': valid and counts == dict(tests=1, failures=0, errors=0, skipped=0)}


def journal_fixture(report):
    # Exact shipped C SQL, master include order. Hibernate-owned entity tables are retained.
    resources = ROOT / 'src/main/resources'
    master = ET.parse(resources / 'db/changelog/userservice-master.xml').getroot()
    paths = []
    for node in master:
        name = node.get('file', '')
        if re.search(r'/2026102[123]_', name):
            change = ET.parse(resources / name).getroot()
            for child in change.iter():
                if child.tag.endswith('sqlFile'):
                    path = child.get('path')
                    source = (resources / name).parent / path if child.get('relativeToChangelogFile') == 'true' else resources / path
                    paths.append(source)
    if not paths: raise ValueError('Actual C migrations required')
    result = []; split_sources = {}
    for path in paths:
        sql = path.read_text()
        plain = re.sub(r'--[^\n]*', '', sql)
        if re.search(r'\b(?:ENGINE|CHARSET|COLLATE|UNSIGNED|ON UPDATE|DELIMITER|MODIFY|CHANGE)\b', plain, re.I):
            raise ValueError('Unreviewed H2 fixture dialect pattern')
        if re.search(r',\s*ADD COLUMN', sql, re.I):
            # Same exact source/type-preserving H2 adaptation as the qualified real JPA fixture.
            if path.parent.name != '20261023_inactivity_claimed_work_367' or path.name != 'migrate.sql':
                raise ValueError('Unreviewed multi-column migration shape')
            columns = re.sub(r'(?s)^.*?ALTER TABLE account_inactivity\s+ADD COLUMN\s+', '', sql)
            columns = re.sub(r';\s*$', '', columns)
            columns = re.split(r',\s*ADD COLUMN\s+', columns)
            if len(columns) != 13 or not columns[-1].strip().startswith('suspension_source_json LONGTEXT NULL'):
                raise ValueError('Exact thirteen existing custody columns required')
            split_sources[str(path.relative_to(ROOT))] = len(columns)
            sql = '\n'.join('ALTER TABLE account_inactivity ADD COLUMN ' + column.strip() + ';' for column in columns)
        # Fixture compatibility only: JPA may already own some tables/columns.
        sql = re.sub(r'CREATE TABLE (?!IF NOT EXISTS)', 'CREATE TABLE IF NOT EXISTS ', sql, flags=re.I)
        sql = re.sub(r'CREATE INDEX (?!IF NOT EXISTS)', 'CREATE INDEX IF NOT EXISTS ', sql, flags=re.I)
        sql = re.sub(r'ADD COLUMN (?!IF NOT EXISTS)', 'ADD COLUMN IF NOT EXISTS ', sql, flags=re.I)
        sql = re.sub(r'ADD CONSTRAINT (?!IF NOT EXISTS)', 'ADD CONSTRAINT IF NOT EXISTS ', sql, flags=re.I)
        result.append(sql)
    output = report.with_name('application-startup-native-journals.sql')
    output.write_text('\n'.join(result))
    base.save(report.with_name('application-startup-native-schema-source.json'), {
        'mode': 'H2_TESTING_ONLY_NOT_MARIADB_REPLAY',
        'sources': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths},
        'fixtureHash': hashlib.sha256(output.read_bytes()).hexdigest(),
        'exactSourceMultiAddSplit': split_sources,
        'sourceOrder': [str(path.relative_to(ROOT)) for path in paths],
        'preflight': {'inputs': len(paths), 'staticPatternScanOnly': True, 'otherDialectPatternsAbsent': True},
        'adaptations': ['CREATE_TABLE_IF_ABSENT', 'CREATE_INDEX_IF_ABSENT', 'ADD_COLUMN_IF_ABSENT', 'ADD_CONSTRAINT_IF_ABSENT', 'EXACT_SOURCE_THIRTEEN_COLUMN_H2_SPLIT']})
    return output


def native(kc, report):
    if platform.system() != 'Linux' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Fresh hosted Linux fixture required')
    state = os.environ.get('STARTUP_EXPECTED_STATE')
    if state not in {'FENCED', 'OPEN'}: raise ValueError('Finite expected startup state required')
    module = kc / 'keycloak-image/account-lifecycle-spi'; otp = kc / 'keycloak-image/otp-config-spi'
    jars = {'LIFECYCLE_C_TEST_JAR': module / 'target/account-lifecycle-spi-1.0-SNAPSHOT.jar',
            'OTP_C_TEST_JAR': otp / 'target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar'}
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    classes = ['target/test-classes/' + SUITE.replace('.', '/') + '.class'] + [
        'target/classes/de/caritas/cob/userservice/api/' + path + '.class' for path in [
        'UserServiceApplication', 'config/auth/ServiceIdentitySeparationCheck', 'config/auth/SecurityConfig',
        'config/auth/TaskActorConfiguration', 'config/auth/TaskActorTokenBroker',
        'adapters/keycloak/lifecycle/AccountLifecycleCallerConfiguration',
        'adapters/keycloak/lifecycle/ReviewedMaintenanceProfiles',
        'workflow/delete/service/ScheduledAskerMaintenance', 'workflow/delete/service/ScheduledConsultantMaintenance',
        'workflow/delete/scheduler/DeleteUserAccountScheduler', 'workflow/accountinactivity/AccountInactivityScheduler']]
    compiled = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
    before = base.snapshot(kc)
    if before != json.loads(report.with_name('application-startup-native-before.json').read_text()):
        raise ValueError('Source changed after build snapshot')
    sys.path.insert(0, str(module / 'tests'))
    import lifecycle_fixture as lifecycle
    from protected_peer_readiness import PeerFixture, PROFILE
    from protected_account_operations import OperationFixture, ProtectedOperationRead
    from ordinary_admin_operations import install_policy
    from test_protected_invitations import ISSUER
    from protected_resources import evidence, require_active
    if lifecycle.IMAGE != IMAGE: raise ValueError('Exact image required')
    catalog = base.source_frame_catalog([Path(__file__), *[module / 'tests' / name for name in [
        'lifecycle_fixture.py', 'test_protected_invitations.py', 'protected_peer_readiness.py', 'ordinary_admin_operations.py']]])

    class StartupFixture(PeerFixture):
        native_issuer = ISSUER
        installer_claims = staticmethod(OperationFixture.installer_claims)
        check_installer_identity = OperationFixture.check_installer_identity

        @classmethod
        def ready(cls):
            super().ready()
            cls('runTest').check_installer_identity([cls.installer], 'NATIVE_READY')

        def admin(self, method, path='', body=None):
            previous = self.installer
            if self.installer_claims(previous)['exp'] <= time.time() + 30:
                type(self).ready(); self.installer = type(self).installer
                self.check_installer_identity([previous, self.installer], 'RENEWAL')
            return super().admin(method, path, body)

        @classmethod
        def prepare_installation(cls, helper, configuration):
            value = super().prepare_installation(helper, configuration)
            install_policy(cls, helper, value)
            command = cls.command.copy()
            group_index = [i for i, argument in enumerate(command) if argument.startswith('ORISO_ACCOUNT_LIFECYCLE_GROUPS=')]
            policy_index = [i for i, argument in enumerate(command) if argument.startswith('ORISO_ACCOUNT_MAINTENANCE_CONFIG=')]
            if len(group_index) != 1 or len(policy_index) != 1: raise ValueError('Exact startup policy environment is required')
            installed, groups = startup_fixture_catalogue(cls.ordinary_installed_configuration,
                json.loads(command[group_index[0]].split('=', 1)[1]))
            command[group_index[0]] = 'ORISO_ACCOUNT_LIFECYCLE_GROUPS=' + json.dumps(groups, separators=(',', ':'))
            command[policy_index[0]] = 'ORISO_ACCOUNT_MAINTENANCE_CONFIG=' + json.dumps(installed, separators=(',', ':'))
            cls.ordinary_installed_configuration = installed
            cls.startup_reviewed_groups = groups
            index = command.index(IMAGE)
            command[index:index] = ['-v', str(jars['OTP_C_TEST_JAR'].resolve()) + ':/opt/keycloak/providers/otp-config-spi.jar:ro',
                '-e', 'IDENTITY_ACCOUNT_ADMIN_CLIENT_ID=backend-account-admin',
                '-e', 'ORISO_APP_BASE_URL=https://otp-fixture.internal', '-e', 'EMAIL_BRANDING_NAME=OTP Fixture',
                '-e', 'EMAIL_LEGAL_ORGANISATION_NAME=Fixture Operator',
                '-v', str(kc / 'keycloak-image/themes/oriso') + ':/opt/keycloak/themes/oriso:ro']
            cls.command = command
            cls.startup_protected_configuration = copy.deepcopy(value)
            return value

    receipt = {'expected_state': state, 'phase': 'fixture-setup', 'accepted': False, 'executed': 0,
        'genuine_behavior_red': False, 'before': before, 'jars': hashes, 'compiled_user_classes': compiled, 'image': IMAGE}
    original_env = {key: os.environ.get(key) for key in jars}
    for key, path in jars.items(): os.environ[key] = str(path.resolve())
    try:
        schema = journal_fixture(report)
        StartupFixture.setUpClass(); fixture = StartupFixture('runTest')
        installed = json.loads(subprocess.run(['docker', 'inspect', '--format', '{{json .Config.Env}}', fixture.name],
            capture_output=True, check=True, text=True, timeout=15).stdout)
        settings = dict(value.split('=', 1) for value in installed)
        protected = settings['ORISO_PROTECTED_INVITATIONS_CONFIG']
        ordinary = settings['ORISO_ACCOUNT_MAINTENANCE_CONFIG']
        assert json.loads(protected) == StartupFixture.startup_protected_configuration
        assert json.loads(ordinary) == StartupFixture.ordinary_installed_configuration
        groups = json.loads(settings['ORISO_ACCOUNT_LIFECYCLE_GROUPS'])
        assert groups == StartupFixture.startup_reviewed_groups
        assert groups == {profile['groupPath']: profile['realmRoles'] for profile in json.loads(ordinary)['ordinaryProfiles']}
        realm = fixture.admin('GET', ''); realm['adminEventsEnabled'] = True
        realm['adminEventsDetailsEnabled'] = False; fixture.admin('PUT', '', realm)

        def native_accounts():
            users = fixture.admin('GET', '/users?max=500')
            if not 0 < len(users) < 500: raise ValueError('Bounded native inventory required')
            return {user['id']: {'user': user,
                'groups': fixture.admin('GET', '/users/' + user['id'] + '/groups'),
                'roles': fixture.admin('GET', '/users/' + user['id'] + '/role-mappings'),
                'credentials': fixture.admin('GET', '/users/' + user['id'] + '/credentials')} for user in users}

        original_accounts = native_accounts(); original_events = fixture.admin('GET', '/admin-events?max=500')
        environment = dict(os.environ)
        environment.update({'TASK_IDENTITY_MODEL': 'FIVE_ACTORS', 'ORISO_ACCOUNT_LIFECYCLE_ENABLED': 'true',
            'ORISO_ACCOUNT_LIFECYCLE_REALM': lifecycle.REALM, 'KEYCLOAK_REALM': lifecycle.REALM,
            'KEYCLOAK_REALM_INTERNAL_ID': fixture.realm_id,
            'KEYCLOAK_AUTH_SERVER_URL': ISSUER.removesuffix('/realms/' + lifecycle.REALM),
            'ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL': fixture.base,
            'IDENTITY_OPENID_CONNECT_URL': fixture.base + '/realms/' + lifecycle.REALM + '/protocol/openid-connect',
            'ORISO_TASK_CONTEXT_KEY': base64.b64encode(lifecycle.CONTEXT_KEY).decode(),
            'ORISO_ACCOUNT_LIFECYCLE_GROUPS': json.dumps(groups, separators=(',', ':')),
            'ORISO_IDENTITY_HUMAN_CLIENT_IDS': '["protected-human-web"]',
            'ORISO_ACCOUNT_MAINTENANCE_CONFIG': ordinary, 'ORISO_PROTECTED_INVITATIONS_CONFIG': protected,
            'ORISO_PROTECTED_INVITATION_PROFILE_IDS': json.dumps({'TENANT_ADMIN': PROFILE, 'PLATFORM_ADMIN': 'platform-v1'}),
            'STARTUP_JOURNAL_FIXTURE': str(schema.resolve()),
            'STARTUP_WITNESS': str(report.with_name('application-startup-native-witness.json').resolve()),
            'STARTUP_BEAN_CATALOG': json.dumps(BEAN_TYPES),
            'STARTUP_FAILURE_TYPES': json.dumps(sorted(FAILURE_TYPES)),
            'STARTUP_TEST_CLASSES': str((ROOT / 'target/test-classes').resolve()),
            'STARTUP_PRODUCTION_CLASSES': str((ROOT / 'target/classes').resolve())})
        for actor, (client, _) in lifecycle.ACTORS.items():
            environment.update({'IDENTITY_' + actor + '_CLIENT_ID': client,
                'IDENTITY_' + actor + '_CLIENT_SECRET': 'synthetic-lifecycle-' + actor,
                'IDENTITY_' + actor + '_SERVICE_SUBJECT': lifecycle.SUBJECTS[actor]})
        base.save(report.with_name('application-startup-native-environment-origins.json'), {
            'mode': 'DISPOSABLE_CHART_SHAPED_TESTING_NOT_DEPLOYED',
            'nativeInstalledIdenticalPolicyContents': ['ORISO_ACCOUNT_MAINTENANCE_CONFIG', 'ORISO_PROTECTED_INVITATIONS_CONFIG'],
            'nativeActualBindings': ['IDENTITY_' + actor + suffix for actor in lifecycle.ACTORS for suffix in ['_CLIENT_ID', '_CLIENT_SECRET', '_SERVICE_SUBJECT']],
            'soleExistingTaskKey': ['ORISO_TASK_CONTEXT_KEY'],
            'nativeActualRealmAndEndpoints': ['KEYCLOAK_REALM', 'KEYCLOAK_REALM_INTERNAL_ID', 'KEYCLOAK_AUTH_SERVER_URL', 'ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL', 'IDENTITY_OPENID_CONNECT_URL'],
            'testingClasspathOnlyQuarantineNotNativeActors': ['identity.technical-user.client-id', 'identity.technical-user.client-secret', 'keycloak.config.admin-client-id', 'keycloak.config.admin-client-secret', 'keycloak.config.admin-service-subject'],
            'schema': ['Hibernate testing create-drop', 'existing UserServiceDatabase.sql', 'existing AccountInactivityDatabase.sql', 'exact shipped C migration H2 fixture']})
        reports = ROOT / 'target/surefire-reports'
        for path in reports.glob('TEST-*.xml'): path.unlink()
        require_active('before-full-user-startup')
        receipt['phase'] = 'actual-full-application-startup'
        receipt['test_classpath'] = {}
        with isolated_test_classes(ROOT, receipt['test_classpath']):
            child = subprocess.run(['mvn', '-B', '-ntp', '-q', 'surefire:test', '-Dtest=' + SUITE + '#' + CASE,
            '-DfailIfNoTests=true', '-Dsurefire.failIfNoSpecifiedTests=true',
            '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2'],
            cwd=ROOT, env=environment, capture_output=True, timeout=180)
        inventory = sanitize(reports, report.with_suffix('.xml')); receipt.update(inventory)
        receipt['executed'] = inventory['tests']; receipt['child_exit'] = child.returncode
        receipt['sql_input'] = safe_child_input(child.stdout + b'\n' + child.stderr)
        receipt['dependency_path'] = safe_dependency_path(child.stdout + b'\n' + child.stderr)
        receipt['failure_types'] = safe_failure_types(child.stdout + b'\n' + child.stderr); child = None
        witness_file = report.with_name('application-startup-native-witness.json')
        witness = json.loads(witness_file.read_text()) if witness_file.exists() else {}
        try: receipt['java_witness_complete'] = checked_witness(witness, state)
        except ValueError:
            if witness_file.exists(): base.save(witness_file, {'witnessSchemaRejected': True})
            raise
        require_active('after-full-user-startup')
        receipt['native_account_count_before'] = len(original_accounts)
        after_accounts = native_accounts()
        receipt['native_account_count_after'] = len(after_accounts)
        receipt['native_accounts_unchanged'] = original_accounts == after_accounts
        receipt['native_admin_events_unchanged'] = original_events == fixture.admin('GET', '/admin-events?max=500')
        receipt['installer_identity_checks'] = ProtectedOperationRead.observation.get('installerIdentityChecks', [])
        receipt['native_startup_telemetry'] = {
            'nativeImageIds': sorted(set(StartupFixture.native_image_ids)),
            'endpointPhases': [{'phase': entry['phase'], 'changed': entry['changed']} for entry in StartupFixture.endpoint_receipts],
            'actualSeedHumanChecks': [{key: entry[key] for key in ['stage', 'enabledNativeUser', 'enabledInteractiveClient', 'serviceAccountLinkAbsent', 'currentNativeSession', 'nativeUserinfoValidatedToken', 'actualCanonicalSubject']} for entry in StartupFixture.preconditions]}
        receipt['resource_observations'] = evidence()
        receipt['accepted'] = (inventory['accepted'] and receipt['child_exit'] == 0 and receipt['java_witness_complete']
            and receipt['native_accounts_unchanged'] and receipt['native_admin_events_unchanged']
            and receipt['test_classpath']['restoredByteExact'])
        receipt['phase'] = 'complete'
    except BaseException as error:
        receipt['exception_category'] = type(error).__name__; receipt['accepted'] = False
        receipt['failure_frames'] = base.sanitized_failure_frames(error, catalog)
        if isinstance(error, KeyboardInterrupt): raise
    finally:
        try:
            StartupFixture.doClassCleanups()
            if getattr(StartupFixture, 'tearDown_exceptions', []):
                receipt['accepted'] = False; receipt['cleanup_failure_count'] = len(StartupFixture.tearDown_exceptions)
        except BaseException as error:
            receipt['cleanup_category'] = type(error).__name__; receipt['accepted'] = False
        for key, value in original_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        receipt['after'] = base.snapshot(kc); receipt['source_stable'] = before == receipt['after']
        receipt['jar_stable'] = hashes == {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
        receipt['compiled_user_stable'] = compiled == {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in classes}
        receipt['accepted'] &= receipt['source_stable'] and receipt['jar_stable'] and receipt['compiled_user_stable']
        base.save(report, receipt)
    return 0 if receipt['accepted'] else 1


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('mode', choices=['snapshot', 'native', 'finalize'])
    parser.add_argument('--keycloak', type=Path, required=True)
    parser.add_argument('--report', type=Path, default=ROOT / 'target/application-startup-native/application-startup-native-result.json')
    args = parser.parse_args()
    if args.mode == 'snapshot':
        base.save(args.report.with_name('application-startup-native-before.json'), base.snapshot(args.keycloak)); return 0
    if args.mode == 'native': return native(args.keycloak.resolve(), args.report)
    if not args.report.exists():
        base.save(args.report, {'phase': 'not-executed', 'executed': 0, 'accepted': False, 'genuine_behavior_red': False})
    return 0 if json.loads(args.report.read_text()).get('accepted') is True else 1


if __name__ == '__main__': raise SystemExit(main())
