"""Closed evidence, isolation and source-preservation controls; no JVM/native resources."""
import ast
import copy
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import tempfile
import textwrap
import types
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('tracked_capture_proof', ROOT / 'scripts/ci/tracked-creation-native-pair.py')
proof = importlib.util.module_from_spec(spec); spec.loader.exec_module(proof)


def canonical_ast_digest(node):
    """Hash all semantic fields, including empty lists and None, without dump formatting."""
    def value(item):
        if isinstance(item, ast.AST):
            return {'node': type(item).__name__,
                    'fields': [[name, value(child)] for name, child in ast.iter_fields(item)]}
        if isinstance(item, list):
            return [value(child) for child in item]
        if isinstance(item, bytes):
            return {'bytes': item.hex()}
        if item is None or type(item) in (str, int, float, bool):
            return item
        raise TypeError('Unexpected semantic AST field')
    return hashlib.sha256(json.dumps(value(node), sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=True, allow_nan=False).encode()).hexdigest()


class TrackedCreationNativeControls(unittest.TestCase):
    def event(self, **changes):
        return {'id': 'private-event', 'realmId': 'private-realm', 'operationType': 'CREATE',
            'resourceType': 'USER', 'resourcePath': 'accounts/private-account',
            'authDetails': {'realmId': 'private-realm', 'clientId': 'private-create', 'userId': 'private-subject'}, **changes}

    def accepted_audit(self, before, after):
        return proof.exact_create_audit(before, after, 'private-account', 'private-realm', 'private-create', 'private-subject')

    def capture(self):
        original = {'existing': {'user': {'id': 'existing', 'enabled': True}, 'groups': [], 'roles': {}, 'credentials': []}}
        target = {'user': {'id': 'private-account', 'username': 'private-username', 'email': 'private-email',
            'firstName': 'Tracked', 'lastName': 'Fixture', 'enabled': False, 'emailVerified': True,
            'createdTimestamp': 1, 'attributes': {'tenantId': ['11']}},
            'groups': [{'id': 'private-pending'}], 'roles': {}, 'credentials': [{'type': 'password'}]}
        captured = {'account': 'private-account', 'operation': 'private-operation', 'username': 'private-username', 'email': 'private-email'}
        return original, {**copy.deepcopy(original), 'private-account': target}, captured

    def test_exact_native_create_audit_rejects_extra_missing_wrong_actor_type_or_representation(self):
        old = {'id': 'old', 'unchanged': 'private-old'}
        self.assertTrue(self.accepted_audit([old], [old, self.event()]))
        for events in [[old], [old, self.event(), self.event(id='second')],
                       [old, self.event(operationType='UPDATE')], [old, self.event(resourcePath='accounts/foreign')],
                       [old, self.event(representation='private-token')], [old, self.event(authDetails={})],
                       [{'id': 'old', 'unchanged': 'changed'}, self.event()], [old, old, self.event()]]:
            self.assertFalse(self.accepted_audit([old], events))
        for field in ['realmId', 'clientId', 'userId']:
            bad = self.event(); bad['authDetails'][field] = 'foreign'
            self.assertFalse(self.accepted_audit([], [bad]))

    def test_final_audit_allows_only_owned_compensation_after_one_create_never_commit_or_foreign_effects(self):
        old = {'id': 'old', 'private-original': 'unchanged'}
        created = self.event()
        deleted = self.event(id='private-cleanup', operationType='DELETE', authDetails={
            'realmId': 'private-realm', 'clientId': 'private-admin', 'userId': 'private-admin-subject'})
        def accepted(events):
            return proof.final_creation_audit([old], events, 'private-account', 'private-realm',
                'private-create', 'private-subject', 'private-admin', 'private-admin-subject')
        self.assertTrue(accepted([old, created]))
        self.assertTrue(accepted([old, created, deleted]))
        # Held checkpoint remains exactly one total event: cleanup here is still refused.
        self.assertFalse(self.accepted_audit([old], [old, created, deleted]))
        for events in [[old, deleted], [old, created, self.event(id='second-create')],
                       [old, created, deleted, {**deleted, 'id': 'second-delete'}],
                       [old, created, {**deleted, 'operationType': 'UPDATE'}],
                       [old, created, {**deleted, 'resourcePath': 'accounts/foreign'}],
                       [old, created, {**deleted, 'resourceType': 'GROUP'}],
                       [old, created, {**deleted, 'realmId': 'foreign'}],
                       [old, created, {**deleted, 'representation': 'private-token'}],
                       [old, created, {**deleted, 'authDetails': created['authDetails']}],
                       [old, created, {**deleted, 'id': created['id']}],
                       [{**old, 'private-original': 'changed'}, created], [created]]:
            self.assertFalse(accepted(events))
        for field in ['realmId', 'clientId', 'userId']:
            bad = copy.deepcopy(deleted); bad['authDetails'][field] = 'foreign'
            self.assertFalse(accepted([old, created, bad]))

    def test_capture_requires_exact_new_id_pending_group_disabled_roleless_profile(self):
        before, after, captured = self.capture()
        self.assertTrue(proof.exact_capture(before, after, captured, 'private-pending'))
        for mutate in [lambda a: a['private-account']['user'].update(enabled=True),
                       lambda a: a['private-account']['user'].update(username='other'),
                       lambda a: a['private-account']['user'].update(serviceAccountClientId='technical'),
                       lambda a: a['private-account']['user']['attributes'].update(tenantId=['22']),
                       lambda a: a['private-account'].update(groups=[]),
                       lambda a: a['private-account'].update(roles={'realmMappings': [{'name': 'consultant'}]}),
                       lambda a: a['existing']['user'].update(enabled=False),
                       lambda a: a.update(unexpected={})]:
            bad = copy.deepcopy(after); mutate(bad)
            self.assertFalse(proof.exact_capture(before, bad, captured, 'private-pending'))
        self.assertFalse(proof.exact_capture(before, after, {**captured, 'private-field': 'secret'}, 'private-pending'))
        self.assertFalse(proof.exact_capture(after, after, captured, 'private-pending'))

    def test_private_java_frames_require_closed_metadata_exact_source_method_and_line(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = 'src/main/java/de/caritas/FixtureKnown.java'
            path = root / source; path.parent.mkdir(parents=True)
            path.write_text('package de.caritas;\nclass FixtureKnown {\n void run() {\n  throw new IllegalStateException();\n }\n}\n')
            hashes = {source: hashlib.sha256(path.read_bytes()).hexdigest()}
            frame = {'class': 'de.caritas.FixtureKnown', 'method': 'run', 'file': 'FixtureKnown.java', 'line': 4}
            good = {'exceptionClasses': ['java.lang.IllegalStateException'], 'frames': [frame]}
            bytecode = ('Compiled from "FixtureKnown.java"\nclass de.caritas.FixtureKnown {\n'
                        '  void run();\n    LineNumberTable:\n      line 4: 0\n}\n')
            with patch.object(proof.subprocess, 'run', return_value=types.SimpleNamespace(stdout=bytecode)) as call:
                self.assertEqual({'exceptionCategories': ['java.lang.IllegalStateException'], 'sourceFrames': [frame]},
                    proof.checked_java_failure_frames(good, root, hashes))
                self.assertEqual(10, call.call_args.kwargs['timeout'])
                self.assertEqual(['de.caritas.FixtureKnown'], call.call_args.args[0][-1:])
                for change in [{'method': 'wrong'}, {'file': 'Other.java'}, {'line': 3}, {'line': 999},
                               {'line': True}, {'class': 'foreign.FixtureKnown'}, {'class': 'PRIVATE-SENTINEL'}]:
                    observed = proof.checked_java_failure_frames({**good, 'frames': [{**frame, **change}]}, root, hashes)
                    self.assertEqual([], observed['sourceFrames'])
                    self.assertNotIn('PRIVATE-SENTINEL', json.dumps(observed))
                self.assertEqual([], proof.checked_java_failure_frames(good, root, {source: '0' * 64})['sourceFrames'])
                self.assertEqual([], proof.checked_java_failure_frames(good, root, {})['sourceFrames'])
            for private in [{**good, 'message': 'PRIVATE-SENTINEL'},
                            {**good, 'exceptionClasses': ['x'] * 5}, {**good, 'frames': [frame] * 129},
                            {**good, 'frames': [{**frame, 'arguments': 'PRIVATE-SENTINEL'}]}]:
                with self.assertRaises(ValueError) as raised: proof.checked_java_failure_frames(private, root, hashes)
                self.assertNotIn('PRIVATE-SENTINEL', str(raised.exception))
            with patch.object(proof.subprocess, 'run', side_effect=OSError('PRIVATE-SENTINEL')):
                self.assertEqual([], proof.checked_java_failure_frames(good, root, hashes)['sourceFrames'])
            self.assertEqual({'exceptionCategories': [], 'sourceFrames': []},
                proof.checked_java_failure_frames({'exceptionClasses': ['PRIVATE-SENTINEL'], 'frames': []}, root, hashes))

    def test_nonce_diagnostic_is_closed_bounded_and_never_exports_private_fields(self):
        good = {'responseDone': True, 'responseStatus': 503, 'receiptHeaderPresent': True,
                'rowReadSucceeded': True, 'rowCount': '1', 'dispatch': 'NATIVE_ID_CAPTURED',
                'workflow': 'FAILED', 'row': 'ROLLED_BACK', 'reason': 'WORKFLOW_FAILED',
                'singleTenantReads': 1, 'policyReads': 2, 'nonceReads': 0}
        self.assertEqual(good, proof.checked_nonce_diagnostic(good))
        absent = {**good, 'responseStatus': 0, 'receiptHeaderPresent': False,
                  'rowReadSucceeded': False, 'rowCount': '0',
                  **dict.fromkeys(['dispatch', 'workflow', 'row', 'reason'], 'ABSENT')}
        self.assertEqual(absent, proof.checked_nonce_diagnostic(absent))
        for field in good:
            missing = good.copy(); missing.pop(field)
            with self.assertRaises(ValueError): proof.checked_nonce_diagnostic(missing)
        for field in ['body', 'header', 'uuid', 'email', 'token', 'error', 'operationKey']:
            with self.assertRaises(ValueError) as raised:
                proof.checked_nonce_diagnostic({**good, field: 'PRIVATE-SENTINEL'})
            self.assertNotIn('PRIVATE-SENTINEL', str(raised.exception))
        for field, values in {'responseStatus': [True, -1, 99, 600, '503'],
                              'singleTenantReads': [True, -1, 101, '1'],
                              'rowCount': [0, 'PRIVATE-SENTINEL'],
                              'dispatch': ['PRIVATE-SENTINEL', None, []],
                              'responseDone': [1, None]}.items():
            for value in values:
                with self.assertRaises(ValueError): proof.checked_nonce_diagnostic({**good, field: value})
        for bad in [{**good, 'responseDone': False}, {**good, 'responseStatus': 0},
                    {**good, 'rowReadSucceeded': False}, {**good, 'rowCount': 'MANY'}]:
            with self.assertRaises(ValueError): proof.checked_nonce_diagnostic(bad)

    def test_witness_rejects_absent_false_extra_or_nonboolean_claims(self):
        good = dict.fromkeys(proof.FLAGS, True)
        self.assertTrue(proof.checked_witness(good))
        for flag in proof.FLAGS:
            self.assertFalse(proof.checked_witness({**good, flag: False}))
            with self.assertRaises(ValueError): proof.checked_witness({**good, flag: 1})
            bad = good.copy(); bad.pop(flag)
            with self.assertRaises(ValueError): proof.checked_witness(bad)
        with self.assertRaises(ValueError): proof.checked_witness({**good, 'token': 'private-token'})

    def test_sanitized_xml_retains_one_real_method_and_failure_without_private_text(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); reports = root / 'reports'; reports.mkdir()
            suite = ET.Element('testsuite', name=proof.SUITE)
            case = ET.SubElement(suite, 'testcase', classname=proof.SUITE, name=proof.CASE)
            problem = ET.SubElement(case, 'failure', message='private-email private-jwt')
            problem.text = 'private-password\nTrackedConsultantCreationNativePairProof.java:123'
            ET.SubElement(suite, 'system-out').text = 'private-body'
            path = reports / 'TEST-capture.xml'; ET.ElementTree(suite).write(path)
            result = proof.sanitize(reports, root / 'safe.xml')
            self.assertEqual((1, 1, 0, 0), tuple(result[k] for k in ['tests', 'failures', 'errors', 'skipped']))
            self.assertFalse(result['accepted']); self.assertTrue(result['exactInventory'])
            self.assertEqual(['TrackedConsultantCreationNativePairProof.java:123'], result['sourceFrames'])
            self.assertNotIn('private-', (root / 'safe.xml').read_text() + json.dumps(result))
            case.remove(problem); ET.ElementTree(suite).write(path)
            self.assertTrue(proof.sanitize(reports, root / 'safe.xml')['accepted'])
            case.set('name', 'other'); ET.ElementTree(suite).write(path)
            self.assertFalse(proof.sanitize(reports, root / 'safe.xml')['accepted'])

    def test_no_xml_or_skipped_case_is_never_native_acceptance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); reports = root / 'reports'; reports.mkdir()
            self.assertFalse(proof.sanitize(reports, root / 'safe.xml')['accepted'])
            suite = ET.Element('testsuite', name=proof.SUITE)
            ET.SubElement(ET.SubElement(suite, 'testcase', classname=proof.SUITE, name=proof.CASE), 'skipped')
            ET.ElementTree(suite).write(reports / 'TEST-capture.xml')
            self.assertFalse(proof.sanitize(reports, root / 'safe.xml')['accepted'])

    def test_isolation_retains_only_declared_proof_and_restores_foreign_configuration_exactly(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); classes = root / 'target/test-classes'; binary = proof.SUITE.replace('.', '/')
            contents = {binary + '.class': b'proof', binary + '$1.class': b'anonymous',
                        'foreign/SpringConfiguration.class': b'foreign', 'other/Test.class': b'other'}
            for name, data in contents.items():
                path = classes / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
            observation = {}
            with self.assertRaisesRegex(RuntimeError, 'test failure'):
                with proof.isolated_classes(root, observation):
                    self.assertFalse((classes / 'foreign/SpringConfiguration.class').exists())
                    self.assertEqual({binary + '.class', binary + '$1.class'}, {str(p.relative_to(classes)) for p in classes.rglob('*.class')})
                    raise RuntimeError('test failure')
            self.assertTrue(observation['restoredByteExact'])
            self.assertEqual(contents, {str(p.relative_to(classes)): p.read_bytes() for p in classes.rglob('*.class')})

    def test_exact_xml_migration_ids_master_order_and_hashes_without_broad_date_filter(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); resources = root / 'src/main/resources'
            master = resources / 'db/changelog/userservice-master.xml'; master.parent.mkdir(parents=True)
            for path, change_id in zip(proof.MIGRATIONS, proof.MIGRATION_IDS):
                target = resources / path; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('<databaseChangeLog><changeSet id="' + change_id + '"/></databaseChangeLog>')
            def write(names): master.write_text('<databaseChangeLog>' + ''.join('<include file="' + name + '"/>' for name in names) + '</databaseChangeLog>')
            write(proof.MIGRATIONS)
            expected = {'src/main/resources/' + name: hashlib.sha256((resources / name).read_bytes()).hexdigest() for name in proof.MIGRATIONS}
            self.assertEqual(expected, proof.schema_sources(root))
            for names in [proof.MIGRATIONS[::-1], proof.MIGRATIONS[:1], (*proof.MIGRATIONS, proof.MIGRATIONS[1])]:
                write(names)
                with self.assertRaises(ValueError): proof.schema_sources(root)
            write(proof.MIGRATIONS)
            (resources / proof.MIGRATIONS[1]).write_text('<databaseChangeLog><changeSet id="wrong"/></databaseChangeLog>')
            with self.assertRaises(ValueError): proof.schema_sources(root)

    def test_private_handoff_is_atomic_owner_only_and_never_replaces_existing_pending(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'payload.json'
            proof.private_json(path, {'private-token': 'secret'})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual({'private-token': 'secret'}, json.loads(path.read_text()))
            path.with_suffix('.pending').write_text('owned-existing')
            with self.assertRaises(FileExistsError): proof.private_json(path, {})
            self.assertEqual('owned-existing', path.with_suffix('.pending').read_text())

    def test_canonical_ast_keeps_empty_none_nonempty_fields_and_source_semantics(self):
        original = ast.parse('def example():\n    pass\n').body[0]
        expected = canonical_ast_digest(original)
        self.assertEqual(expected, canonical_ast_digest(copy.deepcopy(original)))
        nonempty = copy.deepcopy(original)
        nonempty.type_params = [ast.Name(id='T', ctx=ast.Load())]
        self.assertNotEqual(expected, canonical_ast_digest(nonempty))
        absent = copy.deepcopy(original)
        del absent.type_params
        self.assertNotEqual(expected, canonical_ast_digest(absent))
        annotated = copy.deepcopy(original)
        annotated.returns = ast.Constant(value=None)
        self.assertNotEqual(expected, canonical_ast_digest(annotated))
        changed = copy.deepcopy(original)
        changed.body = [ast.Expr(value=ast.Constant(value='changed'))]
        self.assertNotEqual(expected, canonical_ast_digest(changed))
        self.assertNotEqual(canonical_ast_digest(ast.Constant(value=b'fixed')),
                            canonical_ast_digest(ast.Constant(value='fixed')))
        self.assertNotEqual(canonical_ast_digest(ast.Constant(value=b'fixed')),
                            canonical_ast_digest(ast.Constant(value=b'changed')))
        with self.assertRaises(TypeError): canonical_ast_digest(object())

    def test_shared_fixture_class_ast_is_identical_to_reviewed_preextraction_source(self):
        tree = ast.parse((ROOT / 'scripts/ci/application-startup-native-pair.py').read_text())
        factory = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'startup_fixture_class')
        cls = next(n for n in factory.body if isinstance(n, ast.ClassDef))
        digest = canonical_ast_digest(cls)
        self.assertEqual('a31c697cd49c0577222f218f5cf30001ddace48142d415cfe472c57f13ccfb66', digest)
        self.assertEqual(['kc', 'jars', 'browser', 'role_source'], [a.arg for a in factory.args.args])
        native = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'native')
        calls = [n for n in ast.walk(native) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == 'startup_fixture_class']
        self.assertEqual(1, len(calls)); self.assertEqual(['kc', 'jars', 'browser', 'role_source'], [a.id for a in calls[0].args])

    def test_shared_factory_preserves_browser_false_and_true_branch_and_native_mounts(self):
        startup = proof.startup
        class Peer:
            command = []
        class Operation:
            installer_claims = staticmethod(lambda token: {})
            check_installer_identity = lambda *args: None
            @classmethod
            def prepare_installation(cls, helper, configuration): return copy.deepcopy(configuration)
        modules = {'protected_peer_readiness': types.SimpleNamespace(PeerFixture=Peer),
            'protected_account_operations': types.SimpleNamespace(OperationFixture=Operation),
            'test_protected_invitations': types.SimpleNamespace(ISSUER='fixture-issuer')}
        loader = types.SimpleNamespace(exec_module=lambda module: setattr(module, 'install_canonical_platform_source', lambda cls, helper, projection, installed: projection))
        fake_spec = types.SimpleNamespace(loader=loader)
        with patch.dict('sys.modules', modules), patch.object(startup.importlib.util, 'spec_from_file_location', return_value=fake_spec), \
                patch.object(startup.importlib.util, 'module_from_spec', return_value=types.SimpleNamespace()), \
                patch.object(startup, 'startup_protected_catalogue', side_effect=copy.deepcopy), \
                patch.object(startup, 'startup_fixture_catalogue', side_effect=lambda installed, groups: (installed, groups)), \
                patch.object(startup, 'startup_source_projection', side_effect=lambda value, subject: value), \
                patch.object(startup, 'restore_startup_source_targets', side_effect=lambda original, canonical, subject: canonical), \
                patch.object(startup, 'install_startup_primary_adoption', side_effect=lambda cls, helper, value: {**value, 'adoption': True}) as adopted:
            for browser in [False, True]:
                role = types.SimpleNamespace(install_canonical_platform_source=lambda cls, helper, projection, installed: projection)
                cls = startup.startup_fixture_class(Path('/fixture'), {'OTP_C_TEST_JAR': Path('/fixture/otp.jar')}, browser, role)
                cls.human_subject = 'original-subject'; cls.ordinary_installed_configuration = {'original': True}
                cls.command = ['docker', 'run', '-e', 'ORISO_ACCOUNT_LIFECYCLE_GROUPS={"group":["role"]}',
                    '-e', 'ORISO_ACCOUNT_MAINTENANCE_CONFIG={"original":true}', startup.IMAGE]
                value = cls.prepare_installation(None, {'originalPolicy': True})
                self.assertEqual({'originalPolicy': True, **({'adoption': True} if browser else {})}, value)
                self.assertEqual(value, cls.startup_protected_configuration)
                self.assertEqual({'group': ['role']}, cls.startup_reviewed_groups)
                self.assertEqual(1, cls.command.count('/fixture/otp.jar:/opt/keycloak/providers/otp-config-spi.jar:ro'))
                self.assertEqual(1, cls.command.count(startup.IMAGE))
            self.assertEqual(1, adopted.call_count)

    def test_original_native_payload_commands_caps_and_oracles_are_ast_identical_after_lifting_class(self):
        tree = ast.parse((ROOT / 'scripts/ci/application-startup-native-pair.py').read_text())
        factory = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'startup_fixture_class')
        cls = next(n for n in factory.body if isinstance(n, ast.ClassDef))
        native = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'native')
        native.body = [copy.deepcopy(cls) if isinstance(n, ast.Assign)
            and len(n.targets) == 1 and isinstance(n.targets[0], ast.Name) and n.targets[0].id == 'StartupFixture'
            else n for n in native.body]
        self.assertEqual('2b56e5d330c38823c103e59079412dac4bdc467f2e26b6d9b1db95725b080b08', canonical_ast_digest(native))

    def test_java_keeps_real_servlet_jpa_native_checkpoint_and_guarded_fixture_schema(self):
        source = (ROOT / proof.JAVA_SOURCE).read_text()
        for required in ['new SpringApplication(UserServiceApplication.class)', 'WebApplicationType.SERVLET',
                         'new SpringLiquibase()', 'migration.setDataSource(source)',
                         '"H2".equals(connection.getMetaData().getDatabaseProductName())',
                         'jdbc:h2:mem:tracked-native-', 'rows.getLong(1) == 0',
                         'nonceReached.await(20, TimeUnit.SECONDS)', 'releaseNonce.await(8, TimeUnit.SECONDS)',
                         '"NATIVE_ID_CAPTURED"', '"WORKFLOW_FAILED"', '"ROLLED_BACK"']:
            self.assertIn(required, source)
        self.assertEqual(1, source.count('statement.execute("drop table account_creation_attempt")'))
        self.assertLess(source.index('rows.getLong(1) == 0'), source.index('statement.execute("drop table'))
        compact = re.sub(r'\s+', '', source)
        self.assertLess(compact.index('applyCreationMigrations(source,'), compact.index('send(client,port,null'))
        self.assertIn('assertEquals(Boolean.TRUE,context.getBean(de.caritas.cob.userservice.api.config.auth.ServiceIdentitySeparationCheck.class).scopedAccountCommands())', compact)
        self.assertLess(source.index('witness.put("nativeCheckpoint", true)'), source.index('releaseNonce.countDown()'))
        self.assertNotIn('Mockito', source); self.assertNotIn('@Mock', source); self.assertNotIn('setReadTimeout', source)
        self.assertNotIn('retry-initial-delay', source)
        self.assertIn('first.isDone()', source)

    def test_database_uuid_is_materialized_once_before_spring_property_lookups(self):
        source = ast.parse((ROOT / 'scripts/ci/tracked-creation-native-pair.py').read_text())
        urls = [value for node in ast.walk(source) if isinstance(node, ast.Dict)
            for key, value in zip(node.keys, node.values)
            if isinstance(key, ast.Constant) and key.value == 'SPRING_DATASOURCE_URL']
        self.assertEqual(1, len(urls))
        value = urls[0]
        self.assertIsInstance(value, ast.JoinedStr)
        self.assertEqual(3, len(value.values))
        prefix, dynamic, suffix = value.values
        self.assertEqual('jdbc:h2:mem:tracked-native-', prefix.value)
        self.assertEqual(';DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY', suffix.value)
        self.assertIsInstance(dynamic, ast.FormattedValue)
        self.assertIsNone(dynamic.format_spec); self.assertEqual(-1, dynamic.conversion)
        self.assertIsInstance(dynamic.value, ast.Call)
        call = dynamic.value
        self.assertEqual([], call.args); self.assertEqual([], call.keywords)
        self.assertIsInstance(call.func, ast.Attribute)
        self.assertEqual('uuid', call.func.value.id); self.assertEqual('uuid4', call.func.attr)
        from unittest.mock import Mock
        generated = '12345678-1234-1234-1234-123456789abc'
        generator = Mock(return_value=generated)
        # Execute only the closed expression whose complete AST is checked above.
        result = eval(compile(ast.Expression(value), '<fixed-database-url>', 'eval'),
            {'__builtins__': {}, 'uuid': types.SimpleNamespace(uuid4=generator)})
        generator.assert_called_once_with()
        self.assertEqual(prefix.value + generated + suffix.value, result)
        self.assertNotIn('${', result)

    def test_new_workflow_keeps_native_pins_caps_exact_suite_and_closed_artifacts(self):
        import yaml
        path = ROOT / '.github/workflows/scenario-c-tracked-creation-native-pair.yml'
        workflow = yaml.safe_load(path.read_text()); jobs = workflow['jobs']; self.assertEqual(['tracked-creation-native-pair'], list(jobs))
        job = jobs['tracked-creation-native-pair']; self.assertEqual(30, job['timeout-minutes'])
        checkouts = [s for s in job['steps'] if s.get('with', {}).get('repository') == 'OpenResilienceInitiative/ORISO-Keycloak']
        self.assertEqual([proof.KC_COMMIT], [s['with']['ref'] for s in checkouts])
        runs = '\n'.join(s.get('run', '') for s in job['steps'])
        self.assertIn('test_tracked_creation_native_pair.py', runs)
        self.assertIn('test_application_startup_native_pair.py', runs)
        self.assertIn('run_maintenance.py units', runs); self.assertIn('run_scoped_otp.py units', runs)
        native = [s for s in job['steps'] if 'tracked-creation-native-pair.py native' in s.get('run', '')]
        self.assertEqual(1, len(native)); self.assertEqual(10, native[0]['timeout-minutes'])
        upload = next(s for s in job['steps'] if s.get('uses', '').startswith('actions/upload-artifact@'))
        self.assertNotIn('surefire-reports', upload['with']['path']); self.assertNotIn('**', upload['with']['path'])
        source = (ROOT / 'scripts/ci/tracked-creation-native-pair.py').read_text()
        self.assertIn('timeout=180', source); self.assertIn('-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2', source)
        self.assertEqual(1, source.count("'-Dtest=' + SUITE + '#' + CASE"))


if __name__ == '__main__': unittest.main()
