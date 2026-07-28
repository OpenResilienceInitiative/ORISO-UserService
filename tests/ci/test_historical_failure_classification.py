import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
CLASSIFICATION = (
    ROOT / "documentation/user-service-historical-failure-classification.json"
)


class HistoricalFailureClassificationContractTest(unittest.TestCase):
    def setUp(self):
        self.classification = json.loads(CLASSIFICATION.read_text())

    def test_historical_baseline_is_fully_counted_and_resolved(self):
        baseline = self.classification["baseline"]

        self.assertEqual(
            {
                "tests": 4707,
                "failures": 28,
                "errors": 704,
                "skipped": 10,
            },
            baseline["totals"],
        )
        self.assertEqual(
            baseline["totals"]["failures"],
            sum(
                cluster["count"]
                for cluster in self.classification["failureClusters"]
            ),
        )
        self.assertEqual(
            baseline["totals"]["errors"],
            sum(
                cluster["count"] for cluster in self.classification["errorClusters"]
            ),
        )
        masked_context_cluster = next(
            cluster
            for cluster in self.classification["errorClusters"]
            if cluster["id"] == "masked-spring-context-threshold-cascades"
        )
        self.assertEqual(
            masked_context_cluster["count"],
            sum(masked_context_cluster["suiteCounts"].values()),
        )

        clusters = (
            self.classification["failureClusters"]
            + self.classification["errorClusters"]
        )
        self.assertEqual(len(clusters), len({cluster["id"] for cluster in clusters}))
        for cluster in clusters:
            self.assertGreater(cluster["count"], 0)
            self.assertIn(cluster["status"], {"resolved", "stale-tests-removed"})
            self.assertTrue(cluster["classification"])
            self.assertTrue(cluster["resolution"])
            self.assertTrue(cluster["evidence"])

    def test_current_matrix_only_inventory_is_reconciled(self):
        reconciliation = self.classification["inventoryReconciliation"]
        repaired = reconciliation["repairedPreCutover"]
        current = reconciliation["matrixOnlyCurrent"]
        delta = reconciliation["matrixOnlyDelta"]

        self.assertEqual(
            repaired["unit"] + repaired["integration"],
            repaired["primaryTotal"],
        )
        self.assertEqual(
            current["unit"] + current["integration"],
            current["primaryTotal"],
        )
        self.assertEqual(
            current["unit"] - repaired["unit"],
            delta["unit"],
        )
        self.assertEqual(
            current["integration"] - repaired["integration"],
            delta["integration"],
        )
        self.assertEqual(
            current["primaryTotal"] - repaired["primaryTotal"],
            delta["primaryTotal"],
        )
        self.assertEqual(40, reconciliation["sourceDiff"]["deletedJUnitTestClasses"])
        self.assertEqual(29, reconciliation["sourceDiff"]["addedJUnitTestClasses"])
        self.assertEqual(
            33,
            reconciliation["sourceDiff"]["deletedLegacyNamedClasses"],
        )

    def test_current_required_suite_is_full_and_not_quarantined(self):
        current = self.classification["currentRequiredSuite"]

        self.assertEqual(
            "scripts/ci/run-required-integration-tests.sh",
            current["command"],
        )
        self.assertGreaterEqual(current["integrationReports"], 75)
        self.assertGreaterEqual(current["integrationTests"], 830)
        self.assertEqual(0, current["failures"])
        self.assertEqual(0, current["errors"])
        self.assertEqual(2, current["skipped"])
        self.assertFalse(current["quarantine"])

    def test_stability_document_links_the_counted_classification(self):
        stability_document = (
            ROOT / "documentation/USER_SERVICE_STABILITY.md"
        ).read_text()

        self.assertIn(
            "user-service-historical-failure-classification.json",
            stability_document,
        )
        self.assertIn("637", stability_document)
        self.assertIn("45", stability_document)


if __name__ == "__main__":
    unittest.main()
