"""Offline evidence controls; never application acceptance."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

MODULE = Path(__file__).resolve().parents[2] / 'scripts/ci/pre-deletion-boundary-proof.py'
spec = importlib.util.spec_from_file_location('pre_deletion_boundary', MODULE)
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class EvidenceControls(unittest.TestCase):
    def fixture(self, root, failing=False):
        reports = root / 'target/surefire-reports'
        reports.mkdir(parents=True)
        for index, (name, count) in enumerate(proof.SUITES.items()):
            failed = failing and index == 0
            suite = ET.Element('testsuite', name=name, tests=str(count), failures=str(int(failed)), errors='0', skipped='0')
            for item in range(count):
                case = ET.SubElement(suite, 'testcase', classname=name, name='method' + str(item))
                if failed and item == 0:
                    ET.SubElement(case, 'failure', message='private-password-token').text = 'private body'
            ET.ElementTree(suite).write(reports / ('TEST-' + name + '.xml'))
        for name in proof.COMPILED:
            path = root / 'target/classes/de/caritas/cob/userservice/api' / (name + '.class')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'compiled-production')
        path = root / 'target/test-classes/de/caritas/cob/userservice/api/workflow/delete/service/PublicPreDeletionJpaTest.class'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'compiled-test')
        destination = root / 'evidence'
        destination.mkdir()
        proof.save(destination / 'before.json', {'head': 'a' * 40, 'sourceInputs': {'src/fixture': 'abc'}})
        return destination

    def test_exact_complete_xml_and_compiled_source_can_be_green_only_without_native_claim(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = self.fixture(root)
            with patch.object(proof, 'ROOT', root), patch.object(proof, 'sources', return_value={'src/fixture': 'abc'}):
                receipt = proof.collect(destination)
            self.assertTrue(receipt['green'])
            self.assertFalse(receipt['nativeAcceptance'])

    def test_real_assertion_stays_red_and_private_failure_payload_is_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = self.fixture(root, True)
            with patch.object(proof, 'ROOT', root), patch.object(proof, 'sources', return_value={'src/fixture': 'abc'}):
                receipt = proof.collect(destination)
            self.assertFalse(receipt['green'])
            self.assertNotIn('private-password-token', ''.join(path.read_text() for path in destination.glob('*')))
            self.assertEqual(1, receipt['suites'][next(iter(proof.SUITES))]['counts']['failures'])

    def test_missing_required_compiled_owner_cannot_be_green(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = self.fixture(root)
            (root / 'target/classes/de/caritas/cob/userservice/api' / (proof.COMPILED[0] + '.class')).unlink()
            with patch.object(proof, 'ROOT', root), patch.object(proof, 'sources', return_value={'src/fixture': 'abc'}):
                self.assertFalse(proof.collect(destination)['green'])

    def test_changed_source_after_build_cannot_be_green(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = self.fixture(root)
            with patch.object(proof, 'ROOT', root), patch.object(proof, 'sources', return_value={'src/fixture': 'changed'}):
                receipt = proof.collect(destination)
            self.assertFalse(receipt['sourceStable'])
            self.assertFalse(receipt['green'])


if __name__ == '__main__':
    unittest.main()
