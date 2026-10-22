import importlib.util
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('policy_runner', Path(__file__).resolve().parents[1] / 'test-account-policy-context.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class PolicyXmlControls(unittest.TestCase):
    def report(self, root, cases='<testcase name="one"/>', counts='tests="1" failures="0" errors="0" skipped="0"'):
        path = root / 'report.xml'
        path.write_text('<testsuite ' + counts + '>' + cases + '</testsuite>')
        return path

    def test_exact_case_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.report(Path(directory))
            counts, cases = runner.xml_inventory(path, 0, {'one'})
            self.assertEqual(counts['tests'], 1)
            self.assertEqual(cases, [{'name': 'one', 'outcome': 'pass'}])

    def test_stale_missing_duplicated_and_skipped_xml_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError): runner.xml_inventory(root / 'absent.xml', 0, {'one'})
            path = self.report(root)
            os.utime(path, (1, 1))
            with self.assertRaises(ValueError): runner.xml_inventory(path, time.time(), {'one'})
            path = self.report(root, '<testcase name="one"/><testcase name="one"/>', 'tests="2"')
            with self.assertRaises(ValueError): runner.xml_inventory(path, 0, 1)
            path = self.report(root, '<testcase name="one"><skipped/></testcase>', 'tests="1" skipped="1"')
            with self.assertRaises(ValueError): runner.xml_inventory(path, 0, {'one'})

    def test_missing_outcome_nodes_and_extra_cases_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.report(Path(directory), counts='tests="1" failures="1"')
            with self.assertRaises(ValueError): runner.xml_inventory(path, 0, {'one'})
            path = self.report(Path(directory), '<testcase name="two"/>')
            with self.assertRaises(ValueError): runner.xml_inventory(path, 0, {'one'})

    def test_jwt_proof_or_declared_private_value_never_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            for private in ('eyJheader.payload.signature', 'synthetic-private-link'):
                path = self.report(Path(directory), '<testcase name="one"><system-out>' + private + '</system-out></testcase>')
                with self.assertRaises(ValueError): runner.xml_inventory(path, 0, {'one'}, ('synthetic-private-link',))

    def test_exception_frames_do_not_export_messages_or_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private.log'
            path.write_text('Caused by: java.lang.IllegalStateException: synthetic-private-link\n'
                            '\tat com.vi.tenantservice.Policy.require(Policy.java:42)\n'
                            'Bearer eyJheader.payload.signature\n')
            actual = runner.safe_java_frames(path)
            self.assertEqual(actual, {'exceptionTypes': ['java.lang.IllegalStateException'],
                'sourceFrames': [{'callsite': 'com.vi.tenantservice.Policy.require', 'file': 'Policy.java', 'line': 42}]})
            self.assertNotIn('synthetic-private-link', str(actual))


class OwnedNativeMemoryAttributionControls(unittest.TestCase):
    def test_owned_cgroup_snapshot_retains_only_numeric_memory_fields(self):
        identifier = "a" * 64
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc, cgroup = root / "proc", root / "cgroup"
            (proc / "123").mkdir(parents=True)
            (proc / "123" / "cgroup").write_text("0::/system.slice/docker-" + identifier + ".scope\n")
            memory = cgroup / "system.slice" / ("docker-" + identifier + ".scope")
            memory.mkdir(parents=True)
            for name, value in {"current": "7", "peak": "9", "max": "10",
                                "events": "low 0\nhigh 0\nmax 6\noom 0\noom_kill 0\n"}.items():
                (memory / ("memory." + name)).write_text(value)
            class Owned:
                label_key, run_id = "owned-run", "current"
                def inspect(self, kind, name):
                    return {"Id": identifier, "Config": {"Labels": {"owned-run": "current"}},
                            "State": {"Pid": 123}}
            snapshot = runner.owned_memory_snapshot(Owned(), "owned", proc, cgroup)
            self.assertEqual({"available": True, "currentBytes": 7, "peakBytes": 9,
                              "limitBytes": 10, "events": {"low": 0, "high": 0, "max": 6,
                                                             "oom": 0, "oom_kill": 0}}, snapshot)

    def test_foreign_ownership_is_refused_before_proc_or_cgroup_read(self):
        class Foreign:
            label_key, run_id = "owned-run", "current"
            def inspect(self, kind, name):
                return {"Config": {"Labels": {"owned-run": "other"}}}
        with patch.object(Path, "read_text", side_effect=AssertionError("No foreign reads")):
            self.assertEqual({"available": False, "failureType": "RuntimeError"},
                             runner.owned_memory_snapshot(Foreign(), "owned"))

    def test_wrong_cgroup_identity_cannot_be_reported_as_the_owned_peak(self):
        class Owned:
            label_key, run_id = "owned-run", "current"
            def inspect(self, kind, name):
                return {"Id": "a" * 64, "Config": {"Labels": {"owned-run": "current"}},
                        "State": {"Pid": 123}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "123").mkdir()
            (root / "123" / "cgroup").write_text("0::/system.slice/other.service\n")
            self.assertEqual({"available": False, "failureType": "RuntimeError"},
                             runner.owned_memory_snapshot(Owned(), "owned", root, root))


if __name__ == '__main__':
    unittest.main()
