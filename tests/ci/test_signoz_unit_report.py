from datetime import datetime, timezone
from hashlib import sha256
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from xml.etree import ElementTree

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/ci/write-signoz-unit-report.py"
SPEC = importlib.util.spec_from_file_location("write_signoz_unit_report", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

CLASS = "de.caritas.cob.userservice.api.service.statistics.event.ArchiveOrDeleteSessionStatisticsEventTest"
METHOD = "getPayload_Should_ReturnValidJsonPayload"
REPORT_NAME = f"TEST-{CLASS}.xml"
START = "2026-11-09T09:00:00.000000Z"
END = "2026-11-09T09:02:00.000000Z"
PRIVATE = "private fixture payload"


def timestamp(value):
    return datetime.fromisoformat(value[:-1] + "+00:00").timestamp()


class TestSignozUnitReport(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.report = root / REPORT_NAME
        self.destination = root / "result"
        self.destination.mkdir()
        self.context = {
            "repository": "OpenResilienceInitiative/ORISO-UserService",
            "commit": "a" * 40,
            "runId": "123456",
            "runAttempt": 2,
            "workflowJob": "validate",
            "startedAt": START,
            "completedAt": END,
        }

    def write_report(self, cases, modified="2026-11-09T09:01:00Z", count=None):
        content = f'<testsuite name="{CLASS}" tests="{len(cases) if count is None else count}">'
        content += f'<properties><property name="example" value="{PRIVATE}"/></properties>'
        content += "".join(cases) + "</testsuite>"
        self.report.write_text(content)
        modified_at = timestamp(modified)
        os.utime(self.report, (modified_at, modified_at))

    def case(self, method=METHOD, children=""):
        return (f'<testcase classname="{CLASS}" name="{method}" time="0.509" custom="{PRIVATE}">'
                f'{children}<system-out>{PRIVATE}</system-out></testcase>')

    def test_output_is_only_the_selected_case_and_provenance(self):
        self.write_report([
            self.case("sibling", f"<failure>{PRIVATE}</failure>"),
            self.case(children=f'<flakyFailure message="{PRIVATE}">{PRIVATE}</flakyFailure>'),
        ])
        MODULE.export_evidence(self.report, self.destination, self.context)
        self.assertEqual({path.name for path in self.destination.iterdir()}, {REPORT_NAME, "receipt.json"})
        xml = (self.destination / REPORT_NAME).read_bytes()
        self.assertNotIn(PRIVATE.encode(), xml)
        self.assertNotIn(b"sibling", xml)
        suite = ElementTree.fromstring(xml)
        self.assertEqual(suite.attrib, {"name": CLASS, "tests": "1"})
        case, = suite.findall("testcase")
        self.assertEqual(case.attrib, {"classname": CLASS, "name": METHOD, "time": "0.509"})
        child, = case
        self.assertEqual((child.tag, child.attrib, child.text), ("flakyFailure", {}, None))
        receipt = json.loads((self.destination / "receipt.json").read_text())
        self.assertEqual({key: receipt[key] for key in self.context}, self.context)
        self.assertEqual(receipt["report"], {"name": REPORT_NAME, "sha256": sha256(xml).hexdigest()})
        self.assertEqual(receipt["cases"][0]["leaf"], METHOD)
        self.assertEqual(receipt["format"], "surefire")

    def test_failure_and_retry_markers_survive_without_detail(self):
        self.write_report([self.case(children=f'<failure message="{PRIVATE}"/><rerunError>{PRIVATE}</rerunError>')])
        MODULE.export_evidence(self.report, self.destination, self.context)
        suite = ElementTree.parse(self.destination / REPORT_NAME).getroot()
        case, = suite.findall("testcase")
        self.assertEqual([child.tag for child in case], ["failure", "rerunError"])
        self.assertTrue(all(not child.attrib and child.text is None for child in case))

    def test_absent_ambiguous_and_incomplete_case_results_produce_no_artifact(self):
        for cases, count in (([self.case("sibling")], None), ([self.case(), self.case()], None), ([self.case()], 2)):
            with self.subTest(count=count, cases=len(cases)):
                self.write_report(cases, count=count)
                with self.assertRaises(ValueError):
                    MODULE.export_evidence(self.report, self.destination, self.context)
                self.assertFalse(list(self.destination.iterdir()))

    def test_report_outside_test_window_is_rejected(self):
        self.write_report([self.case()], modified="2026-11-09T08:59:59Z")
        with self.assertRaises(ValueError):
            MODULE.export_evidence(self.report, self.destination, self.context)
        self.assertFalse(list(self.destination.iterdir()))

    def test_invalid_attempt_is_rejected(self):
        self.write_report([self.case()])
        with self.assertRaises(ValueError):
            MODULE.export_evidence(self.report, self.destination, {**self.context, "runAttempt": 0})
        self.assertFalse(list(self.destination.iterdir()))
