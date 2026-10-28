"""Finite startup evidence/privacy controls; no JVM or native container."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('startup_pair', ROOT / 'scripts/ci/application-startup-native-pair.py')
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class ApplicationStartupPairControls(unittest.TestCase):
    def test_native_difference_diagnostics_are_fixed_labels_and_never_copy_native_values(self):
        import copy
        labels = {name: 'private-' + name for name in ['self', 'consultantThenAdmin',
            'adminThenConsultant', 'retainedAdmin', 'pureTenant', 'primary']}
        original = {'user': {'id': 'private-user-id', 'username': 'private-username',
            'email': 'private-email', 'attributes': {'tenantId': ['17'], 'locale': ['en'],
            'private-attribute': ['private-secret']}}, 'groups': [{'id': 'private-group',
            'name': 'private-name', 'path': '/private-path'}],
            'roles': {'realmMappings': [{'name': 'private-role'}]},
            'credentials': [{'secretData': 'private-password'}]}
        expected = {key: copy.deepcopy(original) for key in [*labels.values(), 'private-other']}
        actual = copy.deepcopy(expected)
        actual[labels['pureTenant']]['user']['attributes']['tenantId'] = ['22']
        actual[labels['retainedAdmin']]['groups'][0]['id'] = 'private-other-group'
        actual['private-other']['credentials'][0]['secretData'] = 'private-changed-password'
        events = [{'id': 'private-event', 'realmId': 'private-realm', 'operationType': 'UPDATE',
            'resourceType': 'USER', 'resourcePath': 'accounts/' + labels['pureTenant'],
            'authDetails': {'realmId': 'private-realm', 'clientId': 'private-client', 'userId': 'private-subject'}}]
        result = proof.native_effect_diagnostics(expected, actual, labels, [], events,
            'private-realm', 'private-client', 'private-subject')
        self.assertFalse(result['targets']['pureTenant']['attributeFieldsEqual']['tenantId'])
        self.assertFalse(result['targets']['retainedAdmin']['groupIdsEqual'])
        self.assertTrue(result['targets']['retainedAdmin']['groupPathsEqual'])
        self.assertFalse(result['otherAccountsExactlyEqual'])
        self.assertEqual(1, result['newAuditCounts']['pureTenant'])
        self.assertTrue(result['newAuditCanonicalActorAndResource'])
        self.assertNotIn('private-', json.dumps(result))
        self.assertNotIn('secretData', json.dumps(result))
        events[0]['representation'] = 'private-jwt'
        events[0]['resourcePath'] = 'private-unknown-resource'
        events[0]['authDetails']['clientId'] = 'private-foreign-client'
        result = proof.native_effect_diagnostics(expected, actual, labels, [], events,
            'private-realm', 'private-client', 'private-subject')
        self.assertFalse(result['newAuditCanonicalActorAndResource'])
        self.assertFalse(result['newAuditWithoutRepresentation'])
        self.assertEqual(1, result['newAuditCounts']['other'])
        self.assertNotIn('private-', json.dumps(result))

    def test_native_difference_diagnostics_keep_exact_missing_targets_and_prior_events(self):
        labels = {name: 'id-' + name for name in ['self', 'consultantThenAdmin',
            'adminThenConsultant', 'retainedAdmin', 'pureTenant', 'primary']}
        before_event = {'id': 'original', 'resourcePath': 'private-original'}
        result = proof.native_effect_diagnostics({}, {labels['consultantThenAdmin']: {}}, labels,
            [before_event], [], 'realm', 'client', 'subject')
        self.assertFalse(result['targets']['consultantThenAdmin']['expectedPresent'])
        self.assertTrue(result['targets']['consultantThenAdmin']['actualPresent'])
        self.assertFalse(result['originalAuditEventsRetainedExactly'])
        self.assertNotIn('private-original', json.dumps(result))
        with self.assertRaises(ValueError):
            proof.native_effect_diagnostics({}, {}, {'private-unexpected': 'id'}, [], [], 'r', 'c', 's')

    def test_managed_locale_is_an_exact_disposable_projection_without_unmanaged_widening(self):
        import copy
        original = {'attributes': [{'name': 'tenantId', 'permissions': {'view': ['admin']}}],
            'unmanagedAttributePolicy': 'DISABLED', 'groups': [{'name': 'unchanged'}]}
        saved = copy.deepcopy(original)
        desired = proof.startup_locale_profile(original)
        self.assertEqual(saved, original)
        self.assertEqual(original['attributes'], desired['attributes'][:-1])
        self.assertEqual('DISABLED', desired['unmanagedAttributePolicy'])
        self.assertEqual('en|de', desired['attributes'][-1]['validations']['pattern']['pattern'])
        self.assertTrue(proof.verified_locale_profile(desired, desired))
        for bad in [original, {**desired, 'unmanagedAttributePolicy': 'ENABLED'},
                    {**desired, 'attributes': desired['attributes'] + [desired['attributes'][-1]]}]:
            self.assertFalse(proof.verified_locale_profile(bad, desired))
        with self.assertRaises(ValueError):
            proof.startup_locale_profile({**original, 'attributes': [{'name': 'locale'}]})

    def test_audit_oracle_requires_only_eight_original_target_and_type_effects(self):
        import copy
        labels = {name: 'id-' + name for name in ['self', 'consultantThenAdmin',
            'adminThenConsultant', 'retainedAdmin', 'pureTenant', 'primary']}
        original = [{'id': 'before', 'unchanged': True}]
        expected = [('self', 'UPDATE'), ('consultantThenAdmin', 'UPDATE'),
            ('adminThenConsultant', 'UPDATE'), ('adminThenConsultant', 'UPDATE'),
            ('adminThenConsultant', 'DELETE'), ('retainedAdmin', 'UPDATE'),
            ('pureTenant', 'UPDATE'), ('pureTenant', 'UPDATE')]
        added = [{'id': 'new-' + str(index), 'realmId': 'realm', 'operationType': kind,
            'resourceType': 'USER', 'resourcePath': 'accounts/' + labels[name],
            'authDetails': {'realmId': 'realm', 'clientId': 'client', 'userId': 'subject'}}
            for index, (name, kind) in enumerate(expected)]
        def accepted(after):
            return proof.exact_journey_audit(original, after, labels, 'realm', 'client', 'subject')
        self.assertTrue(accepted([*reversed(added), *original]))
        for change in ['extra', 'missing', 'wrong-type', 'wrong-target', 'foreign-client',
                       'foreign-subject', 'foreign-realm', 'representation', 'original-drift', 'duplicate-id']:
            bad = copy.deepcopy([*original, *added])
            if change == 'extra': bad.append({**added[0], 'id': 'extra'})
            elif change == 'missing': bad.pop()
            elif change == 'wrong-type': bad[1]['operationType'] = 'DELETE'
            elif change == 'wrong-target': bad[1]['resourcePath'] = 'accounts/foreign'
            elif change == 'foreign-client': bad[1]['authDetails']['clientId'] = 'foreign'
            elif change == 'foreign-subject': bad[1]['authDetails']['userId'] = 'foreign'
            elif change == 'foreign-realm': bad[1]['realmId'] = 'foreign'
            elif change == 'representation': bad[1]['representation'] = 'private'
            elif change == 'original-drift': bad[0]['unchanged'] = False
            else: bad[1]['id'] = bad[0]['id']
            with self.subTest(change=change): self.assertFalse(accepted(bad))

    def test_startup_declares_only_disposable_lowercase_h2_database(self):
        import ast
        tree = ast.parse((ROOT / 'scripts/ci/application-startup-native-pair.py').read_text())
        values = [value.value for node in ast.walk(tree) if isinstance(node, ast.Dict)
                  for key, value in zip(node.keys, node.values)
                  if isinstance(key, ast.Constant) and key.value == 'SPRING_DATASOURCE_URL'
                  and isinstance(value, ast.Constant)]
        self.assertEqual(['jdbc:h2:mem:userservice-startup-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY'], values)

    def witness(self, state):
        return {'currentClientBrowser': {key: False for key in proof.BROWSER_FLAGS}, 'tenantNative': {key: {'dispatches': 0, 'statuses': []} for key in ['tenant', 'profile']}, 'state': state, 'observedBeans': [], 'failureTypes': ['BeanCreationException', 'IllegalStateException'] if state == 'FENCED' else [],
                **{key: True for key in proof.COMMON_FLAGS},
                **{key: state == 'OPEN' for key in proof.OPEN_FLAGS},
                'exactFence': state == 'FENCED'}

    def test_primary_adoption_preserves_existing_authority_and_requires_exact_native_identity(self):
        import copy
        subject = '11111111-1111-4111-8111-111111111111'
        roles = ['agency-admin', 'tenant-admin', 'user-admin']
        config = {'profiles': [{'id': 'platform-v1', 'kind': 'PLATFORM_ADMIN', 'version': 1,
            'groupId': '22222222-2222-4222-8222-222222222222',
            'groupPath': '/protected-admins/platform-admins', 'realmRoles': roles}],
            'bindings': [{'subject': subject, 'authority': 'PLATFORM', 'authorizerTenantIds': [0],
                'humanClientIds': ['protected-human-web'], 'audienceSets': [['userservice']],
                'protectedProfileIds': ['platform-v1', 'tenant-peer-v1'], 'realmRoles': roles,
                'accountOperations': [{'operations': ['account.read'], 'targetProfiles': ['platform-v1'], 'selfOnly': True}]}],
            'adoptedAccounts': [{'accountId': 'original-tenant', 'protectedProfileId': 'tenant-peer-v1', 'profileVersion': 1}],
            'other': {'preserved': ['exact']}}
        native = {'id': subject, 'enabled': True, 'createdTimestamp': 123,
            'attributes': {'tenantId': ['0']}, 'username': 'reviewed-human'}
        original = copy.deepcopy(config)
        desired = proof.startup_primary_adoption(config, subject, native)
        self.assertEqual(original, config)
        self.assertEqual({**original, 'adoptedAccounts': original['adoptedAccounts'] + [{
            'accountId': subject, 'protectedProfileId': 'platform-v1', 'profileVersion': 1}]}, desired)
        for key, value in [('id', 'foreign'), ('createdTimestamp', 0), ('enabled', False),
                           ('attributes', {'tenantId': ['11']}), ('serviceAccountClientLink', 'machine'),
                           ('federationLink', 'foreign')]:
            bad = copy.deepcopy(native); bad[key] = value
            with self.assertRaises(ValueError): proof.startup_primary_adoption(config, subject, bad)
        for section, key, value in [('profiles', 'realmRoles', roles + ['extra']),
                ('profiles', 'version', 0), ('profiles', 'groupPath', '/foreign'),
                ('bindings', 'subject', 'foreign'), ('bindings', 'realmRoles', roles + ['extra']),
                ('bindings', 'humanClientIds', ['foreign']), ('bindings', 'authorizerTenantIds', [11])]:
            bad = copy.deepcopy(config); bad[section][0][key] = value
            with self.assertRaises(ValueError): proof.startup_primary_adoption(bad, subject, native)
        with self.assertRaises(ValueError): proof.startup_primary_adoption(desired, subject, native)

    def test_primary_adoption_native_readbacks_reject_identity_credential_and_grant_drift(self):
        import copy
        from types import SimpleNamespace
        subject = '11111111-1111-4111-8111-111111111111'
        group = '22222222-2222-4222-8222-222222222222'
        roles = ['agency-admin', 'tenant-admin', 'user-admin']
        config = {'profiles': [{'id': 'platform-v1', 'kind': 'PLATFORM_ADMIN', 'version': 1,
            'groupId': group, 'groupPath': '/protected-admins/platform-admins', 'realmRoles': roles}],
            'bindings': [{'subject': subject, 'authority': 'PLATFORM', 'authorizerTenantIds': [0],
                'humanClientIds': ['protected-human-web'], 'audienceSets': [['userservice']],
                'protectedProfileIds': ['platform-v1'], 'realmRoles': roles,
                'accountOperations': [{'operations': ['account.read'], 'targetProfiles': ['platform-v1'], 'selfOnly': True}]}],
            'adoptedAccounts': []}
        class Native:
            def __init__(self, drift):
                self.drift = drift; self.joined = False; self.direct = [{'name': r} for r in roles]; self.writes = []
            def admin(self, method, path, body=None):
                base = '/users/' + subject
                if method == 'PUT' and path == base + '/groups/' + group:
                    self.joined = True; self.writes.append('join'); return None
                if method == 'DELETE' and path == base + '/role-mappings/realm':
                    if body != self.direct: raise AssertionError('Unexpected role removal')
                    self.direct = []; self.writes.append('remove-direct'); return None
                if method != 'GET': raise AssertionError('Unexpected native mutation')
                if path == base:
                    return {'id': subject, 'enabled': True,
                        'createdTimestamp': 124 if self.joined and self.drift == 'birth' else 123,
                        'attributes': {'tenantId': ['11' if self.joined and self.drift == 'tenant' else '0']}}
                if path == base + '/credentials':
                    return [{'id': 'changed' if self.joined and self.drift == 'credential' else 'original'}]
                if path == base + '/role-mappings/realm': return copy.deepcopy(self.direct)
                if path == base + '/role-mappings/realm/composite':
                    return [{'name': r} for r in roles + (['extra'] if self.joined and self.drift == 'roles' else [])]
                if path == '/groups/' + group + '/role-mappings/realm/composite': return [{'name': r} for r in roles]
                if path == base + '/groups':
                    return [{'id': 'foreign' if self.drift == 'group' else group}] if self.joined else []
                raise AssertionError('Unexpected native read')
        for drift in [None, 'birth', 'tenant', 'credential', 'roles', 'group']:
            setup = SimpleNamespace(human_subject=subject); native = Native(drift)
            if drift:
                with self.assertRaises(ValueError): proof.install_startup_primary_adoption(setup, native, config)
                self.assertFalse(hasattr(setup, 'primary_adoption_observation'))
            else:
                value = proof.install_startup_primary_adoption(setup, native, config)
                self.assertEqual(config['bindings'], value['bindings'])
                self.assertTrue(all(setup.primary_adoption_observation.values()))
            self.assertEqual(['join', 'remove-direct'], native.writes)

    def test_browser_credit_requires_exact_explicit_mode_and_every_bounded_witness(self):
        import copy
        value = self.witness('OPEN')
        self.assertTrue(proof.checked_witness(value, 'OPEN'))
        self.assertFalse(proof.checked_witness(value, 'OPEN', True))
        value['currentClientBrowser'] = {key: True for key in proof.BROWSER_FLAGS}
        self.assertTrue(proof.checked_witness(value, 'OPEN', True))
        self.assertFalse(proof.checked_witness(value, 'OPEN'))
        for key in proof.BROWSER_FLAGS:
            incomplete = copy.deepcopy(value); incomplete['currentClientBrowser'][key] = False
            self.assertFalse(proof.checked_witness(incomplete, 'OPEN', True))
        for extra in ['token', 'requests', 'operationId', 'bodySHA256', 'path']:
            private = copy.deepcopy(value); private['currentClientBrowser'][extra] = 'private'
            with self.assertRaises(ValueError): proof.checked_witness(private, 'OPEN', True)

    def test_startup_installs_the_same_exact_positive_catalogue_in_both_peers(self):
        roles = {'consultant': ['consultant', 'group-chat-consultant'],
                 'consultant-basic': ['consultant'], 'user': ['user'],
                 'agency-admin': ['restricted-agency-admin', 'user-admin'],
                 'consultant-agency-admin': ['consultant', 'group-chat-consultant', 'restricted-agency-admin', 'user-admin'],
                 'consultant-agency-admin-basic': ['consultant', 'restricted-agency-admin', 'user-admin']}
        groups = {'/human-roles/' + name: value for name, value in roles.items()}
        profiles = [{'id': name + '-v1', 'groupPath': '/human-roles/' + name, 'realmRoles': value} for name, value in roles.items()]
        groups['/human-roles/consultant-agency-admin-unacknowledged'] = roles['consultant-agency-admin']
        profiles.append({'id': 'combined-unacknowledged-v1', 'groupPath': '/human-roles/consultant-agency-admin-unacknowledged', 'realmRoles': roles['consultant-agency-admin']})
        config = {'ordinaryProfiles': profiles, 'ordinaryAdminOperations': [{'sourceProfiles': ['consultant-agency-admin-basic-v1']}]}
        installed, selected = proof.startup_fixture_catalogue(config, groups)
        self.assertEqual(6, len(selected))
        self.assertEqual(selected, {p['groupPath']: p['realmRoles'] for p in installed['ordinaryProfiles']})
        self.assertEqual(config['ordinaryAdminOperations'], installed['ordinaryAdminOperations'])
        self.assertEqual(7, len(groups))
        self.assertEqual(7, len(config['ordinaryProfiles']))
        for bad in [profiles[1:], profiles + [profiles[0]], [{**p, 'groupPath': '/unreviewed'} if p['id'] == 'consultant-v1' else p for p in profiles]]:
            with self.assertRaises(ValueError): proof.startup_fixture_catalogue({'ordinaryProfiles': bad}, groups)
        changed = dict(groups); changed['/human-roles/consultant'] = ['technical']
        with self.assertRaises(ValueError): proof.startup_fixture_catalogue(config, changed)
        config['ordinaryAdminOperations'].append({'targetProfiles': ['combined-unacknowledged-v1']})
        with self.assertRaises(ValueError): proof.startup_fixture_catalogue(config, groups)

    def test_protected_catalogue_omits_only_exact_adversarial_alias_and_two_references(self):
        import copy
        peer = {'id': 'tenant-peer-v1', 'kind': 'TENANT_ADMIN', 'version': 1,
                'realmRoles': ['tenant-admin'], 'groupId': 'peer', 'groupPath': '/protected-admins/tenant-peers'}
        config = {'profiles': [peer, {**peer, 'id': 'tenant-other-v1'},
            {'id': 'platform-v1'}, {'id': 'tenant-consultant-v2'}, {'id': 'platform-consultant-v2'}],
            'bindings': [{'subject': who, 'protectedProfileIds': ['platform-v1', 'tenant-other-v1', 'tenant-peer-v1'],
                'accountOperations': [{'targetProfiles': ['tenant-peer-v1'], 'operations': ['account.reassign-tenant']}]}
                for who in ['primary', 'variant']], 'adoptedAccounts': [{'protectedProfileId': 'tenant-peer-v1'}]}
        original = copy.deepcopy(config)
        result = proof.startup_protected_catalogue(config)
        expected = copy.deepcopy(config)
        expected['profiles'].pop(1)
        for binding in expected['bindings']: binding['protectedProfileIds'].remove('tenant-other-v1')
        self.assertEqual(expected, result)
        self.assertEqual(original, config)
        self.assertEqual(['tenant-peer-v1'], [p['id'] for p in result['profiles'] if p.get('realmRoles') == ['tenant-admin']])
        self.assertEqual(2, len([p for p in config['profiles'] if p.get('realmRoles') == ['tenant-admin']]))
        for change in ['missing', 'extra', 'remapped', 'other-id', 'extra-reference', 'missing-reference']:
            bad = copy.deepcopy(config)
            if change == 'missing': bad['profiles'].pop(1)
            elif change == 'extra': bad['profiles'].append(copy.deepcopy(peer))
            elif change == 'remapped': bad['profiles'][1]['groupId'] = 'foreign'
            elif change == 'other-id': bad['profiles'][2]['id'] = 'foreign'
            elif change == 'extra-reference': bad['adoptedAccounts'].append({'protectedProfileId': 'tenant-other-v1'})
            else: bad['bindings'][1]['protectedProfileIds'].remove('tenant-other-v1')
            with self.subTest(change=change), self.assertRaises(ValueError): proof.startup_protected_catalogue(bad)

    def test_two_exact_expected_states_have_different_acceptance(self):
        for state in ['FENCED', 'OPEN']:
            self.assertTrue(proof.checked_witness(self.witness(state), state))
            for flag in proof.COMMON_FLAGS:
                value = self.witness(state); value[flag] = False
                self.assertFalse(proof.checked_witness(value, state))
        value = self.witness('FENCED'); value['contextStarted'] = True
        self.assertFalse(proof.checked_witness(value, 'FENCED'))
        value = self.witness('OPEN'); value['securityReady'] = False
        self.assertFalse(proof.checked_witness(value, 'OPEN'))

    def test_fence_does_not_imply_uncreated_graph_and_open_cannot_accept_fence(self):
        value = self.witness('OPEN'); value['exactFence'] = True
        self.assertFalse(proof.checked_witness(value, 'OPEN'))
        value = self.witness('FENCED'); value['scopedOwnersReady'] = True
        self.assertFalse(proof.checked_witness(value, 'FENCED'))

    def test_unknown_fields_states_values_and_secret_bean_names_are_refused(self):
        for value in [{}, self.witness('OTHER'), {**self.witness('FENCED'), 'bearer': 'sensitive'},
                      {**self.witness('FENCED'), 'observedBeans': ['password=secret']},
                      {**self.witness('FENCED'), 'failureTypes': ['Bearer secret']},
                      {**self.witness('FENCED'), 'actualApplication': 1}]:
            with self.assertRaises(ValueError): proof.checked_witness(value, 'FENCED')

    def test_exact_executed_java_method_and_no_skip_required(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); source = reports / ('TEST-' + proof.SUITE + '.xml')
            body = '<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '">'
            source.write_text(body + '</testcase></testsuite>')
            self.assertTrue(proof.sanitize(reports, reports / 'safe.xml')['accepted'])
            source.write_text(body + '<skipped/></testcase></testsuite>')
            self.assertFalse(proof.sanitize(reports, reports / 'safe.xml')['accepted'])

    def test_raw_diagnostics_never_survive_xml(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); output = reports / 'safe.xml'
            (reports / ('TEST-' + proof.SUITE + '.xml')).write_text(
                '<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '"><error>Bearer secret password mail OTP123</error></testcase></testsuite>')
            self.assertFalse(proof.sanitize(reports, output)['accepted'])
            for word in ['Bearer', 'secret', 'password', 'OTP123']:
                self.assertNotIn(word, output.read_text())

    def test_http_assertion_capsule_exports_only_closed_status_integers(self):
        with tempfile.TemporaryDirectory() as folder:
            reports = Path(folder); output = reports / 'safe.xml'
            (reports / ('TEST-' + proof.SUITE + '.xml')).write_text(
                '<testsuite name="' + proof.SUITE + '"><testcase classname="' + proof.SUITE + '" name="' + proof.CASE + '"><failure>'
                'Bearer secret mail password: expected: &lt;200&gt; but was: &lt;503&gt; '
                'unknown expected: &lt;123&gt; but was: &lt;456&gt;'
                '</failure></testcase></testsuite>')
            value = proof.sanitize(reports, output)
            self.assertFalse(value['accepted'])
            self.assertEqual([{'expected': 200, 'actual': 503}], value['http_status_assertions'])
            for secret in ['Bearer', 'secret', 'mail', 'password', '123', '456']:
                self.assertNotIn(secret, json.dumps(value) + output.read_text())

    def test_sql_input_capsule_exports_only_fixed_resource_number_and_vendor_code(self):
        raw = b'Failed to execute SQL script statement #17 of URL [file:/private/runner/application-startup-native-journals.sql]: secret password Bearer otp123 [42101-240]'
        value = proof.safe_child_input(raw)
        self.assertEqual({'resource': 'C_JOURNAL_FIXTURE', 'statement': 17, 'vendorErrorCodes': [42101]}, value)
        exported = json.dumps(value)
        for secret in ['private', 'password', 'Bearer', 'otp123', 'runner']:
            self.assertNotIn(secret, exported)
        self.assertEqual({}, proof.safe_child_input(b'password=secret unknown resource and payload'))
        self.assertEqual({}, proof.safe_child_input(b'Failed to execute SQL script statement #2 of URL [file:/unknown/secret.sql]'))

    def test_compiled_input_is_only_proof_and_unchanged_resources_and_always_restores(self):
        for raises in [False, True]:
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder); output = root / 'target/test-classes'; output.mkdir(parents=True)
                own = proof.SUITE.replace('.', '/')
                values = {proof.BROWSER_HELPER.replace('.', '/') + '.class': b'private IPC utility', own + '.class': b'proof', own + '$1.class': b'own anonymous',
                    'de/caritas/cob/userservice/api/ForeignTest$Config.class': b'test replacement',
                    'application-testing-credentials.properties': b'unchanged testing resource'}
                for name, value in values.items():
                    path = output / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(value)
                original = proof.test_output_manifest(output); observation = {}
                try:
                    with proof.isolated_test_classes(root, observation):
                        self.assertFalse((output / 'de/caritas/cob/userservice/api/ForeignTest$Config.class').exists())
                        self.assertEqual(b'private IPC utility', (output / (proof.BROWSER_HELPER.replace('.', '/') + '.class')).read_bytes())
                        self.assertEqual(b'own anonymous', (output / (own + '$1.class')).read_bytes())
                        self.assertEqual(b'unchanged testing resource', (output / 'application-testing-credentials.properties').read_bytes())
                        if raises: raise RuntimeError('child startup failed')
                except RuntimeError:
                    self.assertTrue(raises)
                self.assertTrue(observation['restoredByteExact'])
                self.assertEqual(original, proof.test_output_manifest(output))
                self.assertEqual(original, observation['restoredTestManifest'])
                self.assertFalse((root / 'target/application-startup-test-class-quarantine').exists())

    def test_cause_capsule_exports_only_fixed_library_and_source_exception_types(self):
        raw = b'org.springframework.beans.factory.NoUniqueBeanDefinitionException: secret password Bearer abc com.secret.UnknownException java.lang.IllegalStateException'
        self.assertEqual(['org.springframework.beans.factory.NoUniqueBeanDefinitionException', 'java.lang.IllegalStateException'], proof.safe_failure_types(raw))
        self.assertEqual([], proof.safe_failure_types(b'org.secret.PasswordSecretException de.secret.PasswordSecretException password=secret'))
        source_type = 'de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException'
        self.assertEqual([source_type], proof.safe_failure_types((source_type + ': Bearer secret').encode()))

    def test_source_frames_expose_only_existing_class_method_file_and_bounded_line(self):
        owner = 'de.caritas.cob.userservice.api.service.identity.PureTenantAdminEdit'
        raw = ('password=secret Bearer abc\n\tat ' + owner + '.checked(PureTenantAdminEdit.java:362)\n'
            '\tat de.secret.Hidden.password(Hidden.java:12)\n'
            '\tat ' + owner + '.passwordSecret(PureTenantAdminEdit.java:362)\n'
            '\tat ' + owner + '.checked(Other.java:362)\n'
            '\tat ' + owner + '.checked(PureTenantAdminEdit.java:999999)\n').encode()
        self.assertEqual([{'class': owner, 'method': 'checked', 'file': 'PureTenantAdminEdit.java', 'line': 362}], proof.safe_source_frames(raw))
        self.assertEqual([], proof.safe_source_frames(b'Bearer raw secret uuid path /private/token'))

    def test_tenant_diagnostics_have_only_fixed_counts_and_http_statuses(self):
        import copy
        valid = {'tenant': {'dispatches': 2, 'statuses': [200, 200]}, 'profile': {'dispatches': 0, 'statuses': []}}
        proof.checked_tenant_native(valid)
        for change in ['field', 'kind', 'bool', 'large', 'status', 'raw', 'more-responses']:
            bad = copy.deepcopy(valid)
            if change == 'field': bad['tenant']['body'] = 'secret'
            elif change == 'kind': bad['other'] = bad.pop('profile')
            elif change == 'bool': bad['tenant']['dispatches'] = True
            elif change == 'large': bad['tenant']['dispatches'] = 10000
            elif change == 'status': bad['tenant']['statuses'] = [999]
            elif change == 'raw': bad['tenant']['statuses'] = ['Bearer secret']
            else: bad['tenant']['statuses'] = [200, 200, 200]
            with self.subTest(change=change), self.assertRaises(ValueError): proof.checked_tenant_native(bad)

    def test_dependency_capsule_accepts_only_existing_production_identifiers(self):
        raw = b"bean with name 'keycloakService' password=secret Bearer abc bean with name 'selfPasswordMaintenance' bean with name 'SensitiveUnknownLabel'"
        self.assertEqual(['keycloakService', 'selfPasswordMaintenance'], proof.safe_dependency_path(raw))
        self.assertEqual([], proof.safe_dependency_path(b"bean with name 'unknown-secret-label'"))

    def test_fenced_witness_requires_actual_closed_cause_chain(self):
        value = self.witness('FENCED'); value['failureTypes'] = []
        self.assertFalse(proof.checked_witness(value, 'FENCED'))
        value['failureTypes'] = ['BeanCreationException', 'OTHER', 'IllegalStateException']
        self.assertFalse(proof.checked_witness(value, 'FENCED'))

    def test_h2_fixture_reads_only_actual_master_sql_and_preserves_new_custody_column(self):
        with tempfile.TemporaryDirectory() as folder:
            path = proof.journal_fixture(Path(folder) / 'result.json')
            source = json.loads(path.with_name('application-startup-native-schema-source.json').read_text())
            self.assertEqual(27, len(source['sources']))
            self.assertIn('suspension_source_json LONGTEXT NULL', path.read_text())
            self.assertEqual(13, path.read_text().count('ALTER TABLE account_inactivity ADD COLUMN IF NOT EXISTS'))
            self.assertNotRegex(path.read_text(), r',\s*ADD COLUMN')
            self.assertEqual([13], list(source['exactSourceMultiAddSplit'].values()))
            self.assertNotIn('DROP TABLE', path.read_text())
            self.assertNotIn('DELETE FROM', path.read_text())
            for name in source['sources']:
                self.assertTrue((ROOT / name).is_file())

    def test_canonical_source_projection_preserves_full_existing_operation_grants(self):
        import copy
        original = {'bindings': [{'subject': 'primary', 'authority': 'PLATFORM',
            'realmRoles': ['agency-admin', 'tenant-admin'], 'protectedProfileIds': ['platform-v1', 'tenant-peer-v1'],
            'accountOperations': [{'operations': ['account.read', 'account.reassign-tenant'], 'targetProfiles': ['tenant-peer-v1']}]},
            {'subject': 'variant', 'authority': 'PLATFORM', 'realmRoles': ['agency-admin', 'tenant-admin'],
             'protectedProfileIds': ['platform-v1'], 'sourceProfileVariants': [{'profileId': 'platform-consultant-v2'}]}],
            'profiles': [{'id': 'platform-v1', 'realmRoles': ['agency-admin', 'tenant-admin']}]}
        projected = proof.startup_source_projection(original, 'primary')
        projected['bindings'][0]['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        projected['profiles'][0]['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        restored = proof.restore_startup_source_targets(original, projected, 'primary')
        expected = copy.deepcopy(original)
        for binding in expected['bindings']: binding['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        expected['profiles'][0]['realmRoles'] = ['agency-admin', 'tenant-admin', 'user-admin']
        self.assertEqual(expected, restored)
        changed = copy.deepcopy(projected); changed['bindings'][0]['accountOperations'][0]['operations'].append('account.delete')
        with self.assertRaises(ValueError): proof.restore_startup_source_targets(original, changed, 'primary')

    def test_binding_capsule_exports_only_fixed_installed_consumer_names(self):
        value = proof.safe_binding_failure(b'Incomplete scoped account binding: DeleteUserAccountService password=secret Bearer abc')
        self.assertEqual(['DeleteUserAccountService'], value)
        self.assertEqual([], proof.safe_binding_failure(b'Incomplete scoped account binding: PasswordSecret'))

    def test_open_requires_every_actual_http_and_cleanup_journey(self):
        for flag in ['bootNoEffect', 'authenticatedMetadata', 'selfLocaleApplied', 'selfLocaleReplay',
                     'consultantInverseRetention', 'consultantThenAdmin', 'adminThenConsultant', 'pureTenantTwoChildren']:
            value = self.witness('OPEN'); value[flag] = False
            self.assertFalse(proof.checked_witness(value, 'OPEN'), flag)

    def test_workflow_immutable_source_and_finite_default_state(self):
        import yaml
        workflow = yaml.safe_load((ROOT / '.github/workflows/scenario-c-application-startup-native-pair.yml').read_text())
        self.assertEqual(['dev'], workflow['on']['pull_request']['branches'])
        self.assertEqual(['dev', 'codex/367-c-startup-finish-20261023', 'codex/367-c-caller-finish-20261021'], workflow['on']['push']['branches'])
        job = next(iter(workflow['jobs'].values()))
        self.assertEqual('OPEN', job['env']['STARTUP_EXPECTED_STATE'])
        native = [s for s in job['steps'] if s.get('with', {}).get('repository') == 'OpenResilienceInitiative/ORISO-Keycloak']
        self.assertEqual(1, len(native)); self.assertEqual(proof.KC_COMMIT, native[0]['with']['ref'])
        self.assertEqual('05fefa772dda8b248be9531e0552aa9910e1b71e', proof.KC_COMMIT)
        self.assertNotIn('@MockitoBean', (ROOT / proof.JAVA_SOURCE).read_text())
        self.assertNotIn('setLazyInitialization', (ROOT / proof.JAVA_SOURCE).read_text())


if __name__ == '__main__': unittest.main()
