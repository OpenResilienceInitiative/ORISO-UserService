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
import tempfile
import threading
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[2]
KC_COMMIT = '05fefa772dda8b248be9531e0552aa9910e1b71e'
SUITE = 'de.caritas.cob.userservice.api.UserServiceStartupNativePairProof'
BROWSER_HELPER = 'de.caritas.cob.userservice.api.CurrentClientBrowserBridge'
BROWSER_FLAGS = {'frontendLocale', 'ownConsultantRemoval', 'peerAdminRemoval', 'sourceAndCleanup'}
CASE = 'actualServletApplicationReachesOnlyExpectedProductionStartupState'
JAVA_SOURCE = 'src/test/java/' + SUITE.replace('.', '/') + '.java'
COMMON_FLAGS = {'productionClasspath', 'actualApplication', 'testingProfile', 'fiveActors', 'realPolicies', 'contextClosed', 'noUnexpectedFailure'}
OPEN_FLAGS = {'contextStarted', 'servletReady', 'securityReady', 'jpaReady', 'scopedOwnersReady', 'bothSchedulersReady', 'noLegacyFallback', 'schemaReady', 'bootNoEffect', 'authenticatedMetadata', 'selfLocaleApplied', 'selfLocaleReplay', 'consultantInverseRetention', 'consultantThenAdmin', 'adminThenConsultant', 'pureTenantTwoChildren'}

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


def startup_locale_profile(original):
    """Declare only this fixture's original/desired locale, as in the existing SELF native fixture."""
    result = copy.deepcopy(original)
    locale = {'name': 'locale', 'multivalued': False,
        'permissions': {'view': ['admin', 'user'], 'edit': ['admin', 'user']},
        'validations': {'pattern': {'pattern': 'en|de'}}}
    matches = [attribute for attribute in result['attributes'] if attribute.get('name') == 'locale']
    if matches and matches != [locale]: raise ValueError('Unexpected managed locale declaration')
    if not matches: result['attributes'].append(locale)
    return result


def verified_locale_profile(actual, expected):
    return actual == expected and expected == startup_locale_profile(expected)


def exact_journey_audit(before, after, labels, realm, client, subject):
    """Seven UPDATE effects and the existing scheduled Consultant DELETE; replay adds none."""
    names = ('self', 'consultantThenAdmin', 'adminThenConsultant', 'retainedAdmin', 'pureTenant', 'primary')
    if set(labels) != set(names) or len(set(labels.values())) != len(names): return False
    if any(not isinstance(event.get('id'), str) or not event['id'] for event in [*before, *after]): return False
    original = {event['id']: event for event in before}; final = {event['id']: event for event in after}
    if len(original) != len(before) or len(final) != len(after): return False
    if any(final.get(key) != event for key, event in original.items()): return False
    resources = {'accounts/' + account: name for name, account in labels.items()}
    counts = {}
    for event in after:
        if event['id'] in original: continue
        name = resources.get(event.get('resourcePath')); auth = event.get('authDetails', {})
        if (name is None or event.get('realmId') != realm or event.get('resourceType') != 'USER'
                or not isinstance(auth, dict) or auth.get('realmId') != realm
                or auth.get('clientId') != client or auth.get('userId') != subject
                or event.get('representation') not in (None, '')): return False
        key = (name, event.get('operationType')); counts[key] = counts.get(key, 0) + 1
    return counts == {('self', 'UPDATE'): 1, ('consultantThenAdmin', 'UPDATE'): 1,
        ('adminThenConsultant', 'UPDATE'): 2, ('adminThenConsultant', 'DELETE'): 1,
        ('retainedAdmin', 'UPDATE'): 1, ('pureTenant', 'UPDATE'): 2}


def native_effect_diagnostics(expected, actual, labels, before_events, after_events, realm, client, subject):
    """Diagnostics only: fixed labels/equality flags, never native values or acceptance authority."""
    names = ('self', 'consultantThenAdmin', 'adminThenConsultant', 'retainedAdmin', 'pureTenant', 'primary')
    if set(labels) != set(names) or len(set(labels.values())) != len(names):
        raise ValueError('Invalid native diagnostic target labels')
    user_fields = ('id', 'username', 'firstName', 'lastName', 'email', 'emailVerified', 'enabled',
        'createdTimestamp', 'totp', 'disableableCredentialTypes', 'requiredActions', 'notBefore',
        'access', 'attributes', 'federationLink', 'serviceAccountClientId')
    targets = {}
    for name in names:
        account = labels[name]
        old, new = expected.get(account, {}), actual.get(account, {})
        old_user, new_user = old.get('user', {}), new.get('user', {})
        old_attributes, new_attributes = old_user.get('attributes', {}), new_user.get('attributes', {})
        old_groups, new_groups = old.get('groups', []), new.get('groups', [])
        targets[name] = {
            'expectedPresent': account in expected, 'actualPresent': account in actual,
            'userFieldsEqual': {field: old_user.get(field) == new_user.get(field) for field in user_fields},
            'otherUserFieldsEqual': {k: v for k, v in old_user.items() if k not in user_fields}
                == {k: v for k, v in new_user.items() if k not in user_fields},
            'attributeFieldsEqual': {field: old_attributes.get(field) == new_attributes.get(field)
                for field in ('tenantId', 'locale')},
            'otherAttributesEqual': {k: v for k, v in old_attributes.items() if k not in ('tenantId', 'locale')}
                == {k: v for k, v in new_attributes.items() if k not in ('tenantId', 'locale')},
            'groupsExactlyEqual': old_groups == new_groups,
            'groupIdsEqual': {g.get('id') for g in old_groups} == {g.get('id') for g in new_groups},
            'groupPathsEqual': {g.get('path') for g in old_groups} == {g.get('path') for g in new_groups},
            'rolesExactlyEqual': old.get('roles') == new.get('roles'),
            'credentialsExactlyEqual': old.get('credentials') == new.get('credentials')}
    selected = set(labels.values())
    other_expected = {k: v for k, v in expected.items() if k not in selected}
    other_actual = {k: v for k, v in actual.items() if k not in selected}
    original = {event.get('id'): event for event in before_events}
    after = {event.get('id'): event for event in after_events}
    added = [event for event in after_events if event.get('id') not in original]
    resources = {'accounts/' + labels[name]: name for name in names}
    counts = {name: 0 for name in (*names, 'other')}
    canonical = True
    for event in added:
        name = resources.get(event.get('resourcePath'), 'other'); counts[name] += 1
        auth = event.get('authDetails', {})
        canonical = canonical and name != 'other' and event.get('realmId') == realm \
            and event.get('operationType') == 'UPDATE' and event.get('resourceType') == 'USER' \
            and auth.get('realmId') == realm and auth.get('clientId') == client and auth.get('userId') == subject
    return {'targets': targets, 'otherExpectedCount': len(other_expected), 'otherActualCount': len(other_actual),
        'otherAccountsExactlyEqual': other_expected == other_actual,
        'originalAuditEventsRetainedExactly': all(after.get(key) == value for key, value in original.items()),
        'auditIdsUnique': len(original) == len(before_events) and len(after) == len(after_events),
        'newAuditCount': len(added), 'newAuditCounts': counts,
        'newAuditCanonicalActorAndResource': bool(canonical),
        'newAuditWithoutRepresentation': all(not event.get('representation') for event in added)}


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
        if not name.endswith('.class') or name in {prefix + '.class', BROWSER_HELPER.replace('.', '/') + '.class'}
        or re.fullmatch(re.escape(prefix) + r'\$[0-9]+\.class', name)}
    if not {prefix + '.class', BROWSER_HELPER.replace('.', '/') + '.class'} <= set(retained):
        raise ValueError('Exact compiled proof and private bridge required')
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
    values = re.findall(r'(?<![A-Za-z0-9_])(?:org|java|de)\.[A-Za-z0-9_.]+(?:Exception|Error)(?![A-Za-z0-9_])', text)
    return list(dict.fromkeys(value for value in values if value in known))[:30]


def safe_source_frames(raw):
    # Class/method/file/line only; never retain exception messages, arguments, or full log lines.
    sources = {}
    for path in (ROOT / 'src/main/java/de/caritas').rglob('*.java'):
        content = path.read_text()
        package = re.search(r'^package ([A-Za-z0-9_.]+);', content, re.M)
        if package: sources[package.group(1) + '.' + path.stem] = (path.name, content, len(content.splitlines()))
    frames = []
    for owner, method, filename, line in re.findall(
            r'\bat (de\.caritas\.[A-Za-z0-9_.$]+)\.([A-Za-z_][A-Za-z0-9_]*)\(([A-Za-z0-9_]+\.java):([0-9]{1,6})\)',
            raw.decode('utf-8', errors='replace')):
        outer = owner.split('$', 1)[0]
        source = sources.get(outer)
        if (source is None or filename != source[0] or not 1 <= int(line) <= source[2]
                or not re.search(r'\b' + re.escape(method) + r'\s*\(', source[1])): continue
        # Emit the source class, not arbitrary runtime subclass labels.
        frame = {'class': outer, 'method': method, 'file': filename, 'line': int(line)}
        if frame not in frames: frames.append(frame)
        if len(frames) >= 60: break
    return frames


def checked_tenant_native(value):
    if not isinstance(value, dict) or set(value) != {'tenant', 'profile'}:
        raise ValueError('Fixed tenant child diagnostics required')
    for child in value.values():
        if (not isinstance(child, dict) or set(child) != {'dispatches', 'statuses'}
                or type(child['dispatches']) is not int or not 0 <= child['dispatches'] <= 16
                or not isinstance(child['statuses'], list) or len(child['statuses']) > child['dispatches']
                or any(type(status) is not int or status not in {200, 400, 401, 403, 404, 409, 500, 502, 503}
                       for status in child['statuses'])):
            raise ValueError('Bounded native status/count diagnostics required')


def startup_protected_catalogue(configuration):
    """Omit only the native negative-test alias, identically before both peer installations."""
    value = copy.deepcopy(configuration)
    ids = ['platform-v1', 'tenant-peer-v1', 'tenant-other-v1', 'tenant-consultant-v2', 'platform-consultant-v2']
    profiles = value.get('profiles', [])
    if sorted(p.get('id', '') for p in profiles) != sorted(ids):
        raise ValueError('Exact five protected fixture profiles required')
    peer = next(p for p in profiles if p['id'] == 'tenant-peer-v1')
    alias = next(p for p in profiles if p['id'] == 'tenant-other-v1')
    if (alias != {**peer, 'id': 'tenant-other-v1'} or peer.get('kind') != 'TENANT_ADMIN'
            or peer.get('version') != 1 or peer.get('realmRoles') != ['tenant-admin']
            or peer.get('groupPath') != '/protected-admins/tenant-peers' or not peer.get('groupId')):
        raise ValueError('Exact adversarial protected alias required')
    bindings = value.get('bindings', [])
    if len(bindings) != 2 or any(binding.get('protectedProfileIds', []).count('tenant-other-v1') != 1 for binding in bindings):
        raise ValueError('Exact two inherited adversarial target references required')
    profiles.remove(alias)
    for binding in bindings: binding['protectedProfileIds'].remove('tenant-other-v1')
    def contains_alias(node):
        if isinstance(node, dict): return any(contains_alias(k) or contains_alias(v) for k, v in node.items())
        if isinstance(node, list): return any(contains_alias(v) for v in node)
        return node == 'tenant-other-v1'
    if contains_alias(value): raise ValueError('Unexpected adversarial profile reference')
    return value


def startup_source_projection(configuration, subject):
    value = copy.deepcopy(configuration)
    sources = [item for item in value['bindings'] if item['subject'] == subject]
    if len(sources) != 1 or sources[0]['authority'] != 'PLATFORM' or 'platform-v1' not in sources[0]['protectedProfileIds']:
        raise ValueError('Exact existing primary PLATFORM source required')
    sources[0]['protectedProfileIds'] = ['platform-v1']
    return value


def restore_startup_source_targets(original, canonical_projection, subject):
    # The original helper validates actual canonical mappings. Restore only its source projection;
    # retain every original finite operation and target grant byte-for-byte.
    expected = startup_source_projection(original, subject)
    roles = ['agency-admin', 'tenant-admin', 'user-admin']
    primary = next(item for item in expected['bindings'] if item['subject'] == subject)
    primary['realmRoles'] = roles
    profile = next(item for item in expected['profiles'] if item['id'] == 'platform-v1')
    profile['realmRoles'] = roles
    if canonical_projection != expected: raise ValueError('Canonical source helper changed unrelated policy')
    result = copy.deepcopy(original)
    next(item for item in result['profiles'] if item['id'] == 'platform-v1')['realmRoles'] = roles
    for binding in result['bindings']:
        if binding['authority'] == 'PLATFORM' and set(binding['realmRoles']) == {'agency-admin', 'tenant-admin'}:
            binding['realmRoles'] = roles
    return result


def safe_binding_failure(raw):
    source = (ROOT / 'src/main/java/de/caritas/cob/userservice/api/config/auth/ScopedAccountCommandInstallation.java').read_text()
    allowed = set(re.findall(r'"([A-Za-z]+)"', source))
    candidates = re.findall(r'Incomplete scoped account binding: ([A-Za-z]+)', raw.decode('utf-8', errors='replace'))
    return sorted({name for name in candidates if name in allowed})


def checked_witness(value, state, browser=False):
    flags = COMMON_FLAGS | OPEN_FLAGS | {'exactFence'}
    if (state not in {'FENCED', 'OPEN'} or not isinstance(value, dict)
            or set(value) != flags | {'state', 'observedBeans', 'failureTypes', 'tenantNative', 'currentClientBrowser'}
            or value['state'] != state or any(type(value[key]) is not bool for key in flags)
            or not isinstance(value['observedBeans'], list)
            or value['observedBeans'] != sorted(set(value['observedBeans']))
            or any(key not in BEAN_TYPES for key in value['observedBeans'])
            or not isinstance(value['failureTypes'], list)
            or any(key not in FAILURE_TYPES for key in value['failureTypes'])):
        raise ValueError('Exact finite startup witness required')
    browser_value = value['currentClientBrowser']
    if (type(browser) is not bool or not isinstance(browser_value, dict) or set(browser_value) != BROWSER_FLAGS
            or any(type(flag) is not bool for flag in browser_value.values())):
        raise ValueError('Exact finite browser witness required')
    checked_tenant_native(value['tenantNative'])
    return (all(flag == browser for flag in browser_value.values())
            and (not browser or state == 'OPEN') and all(value[key] for key in COMMON_FLAGS)
            and value['exactFence'] == (state == 'FENCED')
            and all(value[key] == (state == 'OPEN') for key in OPEN_FLAGS)
            and (state != 'OPEN' or not value['failureTypes'])
            and (state != 'FENCED' or (len(value['failureTypes']) >= 2
                and value['failureTypes'][-1] == 'IllegalStateException'
                and all(kind in {'BeanCreationException', 'UnsatisfiedDependencyException', 'IllegalStateException'} for kind in value['failureTypes']))))


def sanitize(reports, output):
    clean = ET.Element('testsuite', name=SUITE)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(reports.glob('TEST-*.xml')); names = []; valid = len(files) == 1; frames = []; http_assertions = set()
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
                    # Only known HTTP status integers, never assertion text or response bodies.
                    allowed_statuses = {200, 201, 204, 400, 401, 403, 404, 405, 409, 500, 502, 503}
                    for expected, actual in re.findall(r'expected: <([0-9]{3})> but was: <([0-9]{3})>',
                            (problem.get('message') or '') + '\n' + (problem.text or '')):
                        pair = (int(expected), int(actual))
                        if all(value in allowed_statuses for value in pair): http_assertions.add(pair)
        for key, count in counts.items():
            valid &= suite.get(key) is None or int(suite.get(key)) == count
    valid &= names == [CASE]
    for key, value in counts.items(): clean.set(key, str(value))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(clean).write(output, encoding='utf-8', xml_declaration=True)
    return {**counts, 'exact_method_inventory': valid, 'failure_locations': sorted(set(frames)),
            'http_status_assertions': [{'expected': expected, 'actual': actual} for expected, actual in sorted(http_assertions)],
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
    browser_mode = os.environ.get('STARTUP_CURRENT_CLIENT_BROWSER')
    if browser_mode not in {None, 'true'}: raise ValueError('Explicit browser mode required')
    browser = browser_mode == 'true'
    browser_evidence = {'required': browser,
        'clientRefs': {kind: os.environ['STARTUP_BROWSER_' + kind.upper() + '_REF'] for kind in ('admin', 'frontend')} if browser else {},
        'metadataLimit': 'Actual same-human native roles and GET capability; Agency GET /agencies/70 uses existing testing origin http://localhost:8085/service and may degrade to empty agencies; no complete Agency content or UI availability claim'}
    state = os.environ.get('STARTUP_EXPECTED_STATE')
    if state not in {'FENCED', 'OPEN'}: raise ValueError('Finite expected startup state required')
    module = kc / 'keycloak-image/account-lifecycle-spi'; otp = kc / 'keycloak-image/otp-config-spi'
    jars = {'LIFECYCLE_C_TEST_JAR': module / 'target/account-lifecycle-spi-1.0-SNAPSHOT.jar',
            'OTP_C_TEST_JAR': otp / 'target/keycloak-otp-config-spi-1.0-SNAPSHOT-keycloak.jar',
            'SCOPED_OTP_READY_FIXTURE_JAR': module / 'target/scoped-otp-ready-fixture.jar'}
    hashes = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in jars.items()}
    classes = ['target/test-classes/' + name.replace('.', '/') + '.class' for name in (SUITE, BROWSER_HELPER)] + [
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

    role_spec = importlib.util.spec_from_file_location('startup_role_source', ROOT / 'scripts/ci/role-maintenance-native.py')
    role_source = importlib.util.module_from_spec(role_spec); role_spec.loader.exec_module(role_source)

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
            value = startup_protected_catalogue(OperationFixture.prepare_installation.__func__(cls, helper, configuration))
            command = cls.command.copy()
            group_index = [i for i, argument in enumerate(command) if argument.startswith('ORISO_ACCOUNT_LIFECYCLE_GROUPS=')]
            policy_index = [i for i, argument in enumerate(command) if argument.startswith('ORISO_ACCOUNT_MAINTENANCE_CONFIG=')]
            if len(group_index) != 1 or len(policy_index) != 1: raise ValueError('Exact startup policy environment is required')
            installed, groups = startup_fixture_catalogue(cls.ordinary_installed_configuration,
                json.loads(command[group_index[0]].split('=', 1)[1]))
            command[group_index[0]] = 'ORISO_ACCOUNT_LIFECYCLE_GROUPS=' + json.dumps(groups, separators=(',', ':'))
            command[policy_index[0]] = 'ORISO_ACCOUNT_MAINTENANCE_CONFIG=' + json.dumps(installed, separators=(',', ':'))
            cls.ordinary_installed_configuration = installed
            original_protected = copy.deepcopy(value)
            source_projection = startup_source_projection(value, cls.human_subject)
            canonical = role_source.install_canonical_platform_source(cls, helper, source_projection, installed)
            value = restore_startup_source_targets(original_protected, canonical, cls.human_subject)
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
    receipt['current_client_browser'] = browser_evidence
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
        reviewed_locale_profile = startup_locale_profile(fixture.admin('GET', '/users/profile'))
        fixture.admin('PUT', '/users/profile', reviewed_locale_profile)
        if not verified_locale_profile(fixture.admin('GET', '/users/profile'), reviewed_locale_profile):
            raise ValueError('Managed locale profile readback differs')
        def verify_installed_declarations():
            if not verified_locale_profile(fixture.admin('GET', '/users/profile'), reviewed_locale_profile):
                raise ValueError('Managed locale profile changed')
            actual_env = json.loads(subprocess.run(['docker', 'inspect', '--format', '{{json .Config.Env}}', fixture.name],
                capture_output=True, check=True, text=True, timeout=15).stdout)
            actual = dict(value.split('=', 1) for value in actual_env)
            if (json.loads(actual['ORISO_PROTECTED_INVITATIONS_CONFIG']) != json.loads(protected)
                    or json.loads(actual['ORISO_ACCOUNT_MAINTENANCE_CONFIG']) != json.loads(ordinary)
                    or json.loads(actual['ORISO_ACCOUNT_LIFECYCLE_GROUPS']) != groups):
                raise ValueError('Actual installed policy differs from exact User inputs')
            for profile in [*json.loads(protected)['profiles'], *json.loads(ordinary)['ordinaryProfiles']]:
                group_id = profile.get('groupId') or fixture.group_id(profile['groupPath'])
                roles = fixture.admin('GET', '/groups/' + group_id + '/role-mappings/realm/composite')
                if {role['name'] for role in roles} != set(profile['realmRoles']):
                    raise ValueError('Actual installed reviewed group roles differ from declaration')
            return True
        receipt['native_declarations_before'] = verify_installed_declarations()
        receipt['canonical_platform_source'] = copy.deepcopy(StartupFixture.canonical_platform_source)
        realm = fixture.admin('GET', ''); realm['adminEventsEnabled'] = True
        realm['adminEventsDetailsEnabled'] = False; fixture.admin('PUT', '', realm)

        def native_accounts():
            users = fixture.admin('GET', '/users?max=500')
            if not 0 < len(users) < 500: raise ValueError('Bounded native inventory required')
            return {user['id']: {'user': user,
                'groups': fixture.admin('GET', '/users/' + user['id'] + '/groups'),
                'roles': fixture.admin('GET', '/users/' + user['id'] + '/role-mappings'),
                'credentials': fixture.admin('GET', '/users/' + user['id'] + '/credentials')} for user in users}

        from test_protected_invitations import HUMAN_PASSWORD
        from profile_transition_operations import committed_kind
        primary_bearer = role_source.verify_canonical_platform_human(fixture, lifecycle, ISSUER, HUMAN_PASSWORD)
        journey_targets = {}
        own_creation = None
        for key in ('consultantThenAdmin', 'adminThenConsultant', 'retainedAdmin'):
            account, creation = committed_kind(fixture, fixture, 'CONSULTANT_AGENCY_ADMIN', 'consultant-agency-admin')
            journey_targets[key] = fixture.admin('GET', '/users/' + account)
            if key == 'consultantThenAdmin': own_creation = creation
        tenant_account = StartupFixture.adopted_account
        journey_targets['pureTenant'] = fixture.admin('GET', '/users/' + tenant_account)
        journey_targets['primary'] = fixture.admin('GET', '/users/' + fixture.human_subject)
        from adopted_self_operations import login
        self_account, self_password = StartupFixture.adopted_self_cases['user']
        self_native = fixture.admin('GET', '/users/' + self_account)
        self_native.setdefault('attributes', {})['locale'] = ['en']
        fixture.admin('PUT', '/users/' + self_account, self_native)
        self_bearer = login(fixture, fixture, self_native, self_password)
        self_native = fixture.admin('GET', '/users/' + self_account)
        if self_native.get('attributes', {}).get('locale') != ['en']:
            raise ValueError('Original managed locale was not installed')
        browser_payload = {}
        if browser:
            def tokens(username, password, expected_subject):
                status, result = lifecycle.call(fixture.base, 'POST', '/realms/' + lifecycle.REALM + '/protocol/openid-connect/token',
                    {'client_id': 'protected-human-web', 'grant_type': 'password', 'username': username,
                     'password': password, 'scope': 'openid'}, form=True)
                if status != 200 or not isinstance(result.get('refresh_token'), str):
                    raise ValueError('Actual full native browser session required')
                claims = json.loads(base64.urlsafe_b64decode(result['access_token'].split('.')[1] + '==='))
                if claims.get('sub') != expected_subject: raise ValueError('Actual original browser subject required')
                return result
            support_id, _ = StartupFixture.adopted_self_cases['consultant']
            browser_payload = {
                'browserConfig': {'keycloakOrigin': fixture.base, 'realm': lifecycle.REALM,
                    **{kind: {'root': os.environ['STARTUP_BROWSER_' + kind.upper() + '_ROOT'],
                              'ref': os.environ['STARTUP_BROWSER_' + kind.upper() + '_REF']}
                       for kind in ('admin', 'frontend')}},
                'selfTokens': tokens(self_native['username'], self_password, self_account),
                'primaryTokens': tokens('reviewed-human', HUMAN_PASSWORD, fixture.human_subject),
                'ownTokens': tokens(own_creation['account']['username'], own_creation['account']['password'],
                                    journey_targets['consultantThenAdmin']['id']),
                'supportConsultant': fixture.admin('GET', '/users/' + support_id)}
        pure_admin_groups = fixture.admin('GET', '/users/' + StartupFixture.adopted_self_cases['agency-admin'][0] + '/groups')
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
            'MATRIX_SERVER_NAME': 'startup-fixture.invalid',
            'SPRING_DATASOURCE_URL': 'jdbc:h2:mem:userservice-startup-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY',
            'ACCOUNT_INACTIVITY_ENABLED': 'true',
            'DELETION_READONLYWINDOW_TENANTOVERRIDES': '11:0',
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
            'disposableInactivityBaseline': 'Actual seeded human UUIDs/tenants/native creation times, existing assignAtCreation with explicit fixture18months/revision1; baseline only, not onboarding policy integration',
            'disposableConsultingTypeMetadata': 'Loopback ephemeral exact GET /consultingtypes/basic, no query/body, empty list for unassigned seeds; actual production client; no CTS-native or external delivery credit',
            'disposableInactivityScheduler': 'ACCOUNT_INACTIVITY_ENABLED=true; unchanged dry-run default; instantiation and binding only, not inactivity effects',
            'disposableMatrixName': 'MATRIX_SERVER_NAME=startup-fixture.invalid; required grant owner configuration only, not Matrix reachability or delivery',
            'disposableRetentionPolicy': 'deletion.readOnlyWindow.tenantOverrides=11:0; proves configured immediate eligibility, not elapsed48h; nonzero tests retained',
            'disposableTenantMetadata': 'Owned ephemeral exact GET /tenant/public/id/22, no query/body, id22/subdomain fixture-22 only; actual production response enrichment client, wire and returned subdomain asserted; no nativeTenant/policy/baseline credit',
            'disposableProtectedCatalogue': 'Five to four profiles: omit only adversarial tenant-other-v1 plus its two inherited target-ID references; retain both human bindings and every other field; identical installed/User contents, unchanged K negative suites',
            'pureTenantProvenance': 'Existing explicitly adopted tenant-peer-v1 target17, exact native birth; move22 with unchanged installed grant',
            'journeyDownstreamLimit': 'Real User servlet/JPA and Keycloak; no Matrix account/room; standard mode has no Consultant agency relations, browser mode seeds four real SQL agency70 relations and preserves the support Consultant; Appointment feature retains configured false; surviving AdminAgency row preserved',
            'schema': ['Hibernate testing create-drop', 'existing UserServiceDatabase.sql', 'existing AccountInactivityDatabase.sql', 'exact shipped C migration H2 fixture']})
        reports = ROOT / 'target/surefire-reports'
        for path in reports.glob('TEST-*.xml'): path.unlink()
        require_active('before-full-user-startup')
        receipt['phase'] = 'actual-full-application-startup'
        receipt['test_classpath'] = {}
        boot_phase = {'verified': False, 'failure': None}
        with tempfile.TemporaryDirectory(prefix='oriso-startup-private-') as private:
            exchange = Path(private)
            os.chmod(exchange, 0o700)
            environment['STARTUP_PRIVATE_EXCHANGE'] = str(exchange)
            finished = threading.Event()
            def verify_boot():
                try:
                    deadline = time.monotonic() + 175
                    while not (exchange / 'boot-ready').exists():
                        if finished.wait(0.05) or time.monotonic() >= deadline: return
                    if (exchange / 'boot-ready').read_text() != 'BOOT_READY': raise ValueError('Exact boot marker required')
                    if original_accounts != native_accounts(): raise ValueError('Boot changed native accounts')
                    if original_events != fixture.admin('GET', '/admin-events?max=500'): raise ValueError('Boot changed native admin events')
                    boot_phase['verified'] = True
                    release = exchange / 'boot-verified.pending'
                    payload = {'bootNoEffect': True, 'account': self_account, 'username': self_native['username'],
                        'email': self_native['email'], 'bearer': self_bearer, 'nativeCreation': self_native['createdTimestamp'],
                        'primaryBearer': primary_bearer, **journey_targets, **browser_payload}
                    fd = os.open(release, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with os.fdopen(fd, 'w') as stream: json.dump(payload, stream)
                    release.rename(exchange / 'boot-verified.json')
                except BaseException as error:
                    boot_phase['failure'] = type(error).__name__
            observer = threading.Thread(target=verify_boot, daemon=True)
            observer.start()
            try:
                with isolated_test_classes(ROOT, receipt['test_classpath']):
                    child = subprocess.run(['mvn', '-B', '-ntp', '-q', 'surefire:test', '-Dtest=' + SUITE + '#' + CASE,
                        '-DfailIfNoTests=true', '-Dsurefire.failIfNoSpecifiedTests=true',
                        '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2'],
                        cwd=ROOT, env=environment, capture_output=True, timeout=180)
            finally:
                finished.set(); observer.join(timeout=5)
            if observer.is_alive(): raise ValueError('Native boot observer did not finish')
        receipt['boot_native_accounts_and_events_unchanged'] = boot_phase['verified']
        receipt['boot_observer_failure'] = boot_phase['failure']
        inventory = sanitize(reports, report.with_suffix('.xml')); receipt.update(inventory)
        receipt['executed'] = inventory['tests']; receipt['child_exit'] = child.returncode
        receipt['sql_input'] = safe_child_input(child.stdout + b'\n' + child.stderr)
        receipt['dependency_path'] = safe_dependency_path(child.stdout + b'\n' + child.stderr)
        receipt['installed_binding_failure'] = safe_binding_failure(child.stdout + b'\n' + child.stderr)
        receipt['failure_types'] = safe_failure_types(child.stdout + b'\n' + child.stderr)
        receipt['source_failure_frames'] = safe_source_frames(child.stdout + b'\n' + child.stderr); child = None
        witness_file = report.with_name('application-startup-native-witness.json')
        witness = json.loads(witness_file.read_text()) if witness_file.exists() else {}
        try: receipt['java_witness_complete'] = checked_witness(witness, state, browser)
        except ValueError:
            if witness_file.exists(): base.save(witness_file, {'witnessSchemaRejected': True})
            raise
        require_active('after-full-user-startup')
        receipt['native_declarations_after'] = verify_installed_declarations()
        receipt['native_account_count_before'] = len(original_accounts)
        after_accounts = native_accounts()
        receipt['native_account_count_after'] = len(after_accounts)
        expected_accounts = copy.deepcopy(original_accounts)
        if state == 'OPEN':
            expected_accounts[self_account]['user']['attributes']['locale'] = ['de']
            for key in ('consultantThenAdmin', 'adminThenConsultant'):
                del expected_accounts[journey_targets[key]['id']]
            expected_accounts[journey_targets['retainedAdmin']['id']]['groups'] = pure_admin_groups
            moved = expected_accounts[tenant_account]['user']
            moved['attributes']['tenantId'] = ['22']
            moved.update(firstName='Grace', lastName='Hopper', email='grace@fixture.invalid', emailVerified=False)
        receipt['native_accounts_exact_expected_effect'] = expected_accounts == after_accounts
        after_events = fixture.admin('GET', '/admin-events?max=500')
        receipt['native_admin_events_unchanged'] = original_events == after_events
        receipt['native_admin_events_exact_expected_effect'] = (
            exact_journey_audit(original_events, after_events,
                {'self': self_account, **{name: target['id'] for name, target in journey_targets.items()}},
                fixture.realm_id, lifecycle.ACTORS['ACCOUNT_ADMIN'][0], lifecycle.SUBJECTS['ACCOUNT_ADMIN'])
            if state == 'OPEN' else receipt['native_admin_events_unchanged'])
        receipt['native_effect_diagnostics'] = native_effect_diagnostics(expected_accounts, after_accounts,
            {'self': self_account, **{name: target['id'] for name, target in journey_targets.items()}},
            original_events, after_events, fixture.realm_id,
            lifecycle.ACTORS['ACCOUNT_ADMIN'][0], lifecycle.SUBJECTS['ACCOUNT_ADMIN'])
        receipt['installer_identity_checks'] = ProtectedOperationRead.observation.get('installerIdentityChecks', [])
        receipt['native_startup_telemetry'] = {
            'nativeImageIds': sorted(set(StartupFixture.native_image_ids)),
            'endpointPhases': [{'phase': entry['phase'], 'changed': entry['changed']} for entry in StartupFixture.endpoint_receipts],
            'actualSeedHumanChecks': [{key: entry[key] for key in ['stage', 'enabledNativeUser', 'enabledInteractiveClient', 'serviceAccountLinkAbsent', 'currentNativeSession', 'nativeUserinfoValidatedToken', 'actualCanonicalSubject']} for entry in StartupFixture.preconditions]}
        receipt['resource_observations'] = evidence()
        receipt['accepted'] = (inventory['accepted'] and receipt['child_exit'] == 0 and receipt['java_witness_complete']
            and receipt['native_accounts_exact_expected_effect'] and receipt['native_admin_events_exact_expected_effect']
            and receipt['native_declarations_before'] and receipt['native_declarations_after']
            and (state != 'OPEN' or receipt['boot_native_accounts_and_events_unchanged'])
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
