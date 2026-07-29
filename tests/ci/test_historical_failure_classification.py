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
        self.assertIn("matrixOnlyCutover", reconciliation)
        self.assertIn("matrixOnlyCutoverDelta", reconciliation)
        self.assertIn("verifiedCurrent", reconciliation)
        self.assertIn("sinceCutoverDelta", reconciliation)
        cutover = reconciliation["matrixOnlyCutover"]
        cutover_delta = reconciliation["matrixOnlyCutoverDelta"]
        current = reconciliation["verifiedCurrent"]
        current_delta = reconciliation["sinceCutoverDelta"]

        self.assertEqual(
            repaired["unit"] + repaired["integration"],
            repaired["primaryTotal"],
        )
        self.assertEqual(
            cutover["unit"] + cutover["integration"],
            cutover["primaryTotal"],
        )
        self.assertEqual(
            cutover["unit"] - repaired["unit"],
            cutover_delta["unit"],
        )
        self.assertEqual(
            cutover["integration"] - repaired["integration"],
            cutover_delta["integration"],
        )
        self.assertEqual(
            cutover["primaryTotal"] - repaired["primaryTotal"],
            cutover_delta["primaryTotal"],
        )
        self.assertEqual(
            current["unit"] + current["integration"],
            current["primaryTotal"],
        )
        self.assertEqual(
            current["unit"] - cutover["unit"],
            current_delta["unit"],
        )
        self.assertEqual(
            current["integration"] - cutover["integration"],
            current_delta["integration"],
        )
        self.assertEqual(
            current["primaryTotal"] - cutover["primaryTotal"],
            current_delta["primaryTotal"],
        )
        required_suite = self.classification["currentRequiredSuite"]
        self.assertEqual(required_suite["unitTests"], current["unit"])
        self.assertEqual(required_suite["integrationTests"], current["integration"])
        self.assertEqual(
            "9d833aa6288c3828b8fed96ebc45d6556e390e33",
            current["verifiedApplicationHead"],
        )
        self.assertEqual(2, len(current["localEvidence"]))
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
        self.assertEqual(3423, current.get("unitTests"))
        self.assertEqual(393, current["unitReports"])
        self.assertEqual(82, current["integrationReports"])
        self.assertEqual(854, current["integrationTests"])
        self.assertEqual(0, current["failures"])
        self.assertEqual(0, current["errors"])
        self.assertEqual(9, current["skipped"])
        self.assertFalse(current["quarantine"])

    def test_stability_document_links_the_counted_classification(self):
        stability_document = (
            ROOT / "documentation/USER_SERVICE_STABILITY.md"
        ).read_text()

        self.assertIn(
            "user-service-historical-failure-classification.json",
            stability_document,
        )
        self.assertIn(
            "637 replacement-H2 datasource failures",
            stability_document,
        )
        self.assertIn(
            "45 initial Spring context-threshold cascades",
            stability_document,
        )


if __name__ == "__main__":
    unittest.main()
