import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
CLASSIFICATION = (
    ROOT / "documentation/user-service-historical-failure-classification.json"
)


class HistoricalFailureClassificationContractTest(unittest.TestCase):
    def test_historical_baseline_is_fully_counted_and_resolved(self):
        classification = json.loads(CLASSIFICATION.read_text())
        baseline = classification["baseline"]

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
            sum(cluster["count"] for cluster in classification["failureClusters"]),
        )
        self.assertEqual(
            baseline["totals"]["errors"],
            sum(cluster["count"] for cluster in classification["errorClusters"]),
        )
        masked_context_cluster = next(
            cluster
            for cluster in classification["errorClusters"]
            if cluster["id"] == "masked-spring-context-threshold-cascades"
        )
        self.assertEqual(
            masked_context_cluster["count"],
            sum(masked_context_cluster["suiteCounts"].values()),
        )

        clusters = (
            classification["failureClusters"] + classification["errorClusters"]
        )
        self.assertEqual(
            len(clusters),
            len({cluster["id"] for cluster in clusters}),
        )
        for cluster in clusters:
            self.assertGreater(cluster["count"], 0)
            self.assertIn(cluster["status"], {"resolved", "stale-tests-removed"})
            self.assertTrue(cluster["classification"])
            self.assertTrue(cluster["resolution"])
            self.assertTrue(cluster["evidence"])

        current = classification["currentRequiredSuite"]
        self.assertGreaterEqual(current["integrationTests"], 900)
        self.assertEqual(0, current["failures"])
        self.assertEqual(0, current["errors"])
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
