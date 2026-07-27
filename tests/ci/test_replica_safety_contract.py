import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "src/main/resources/replica-safety-components.json"
MAIN_JAVA = ROOT / "src/main/java"


def source_has_annotation(source: Path, annotation: str) -> bool:
    return any(
        line.lstrip().startswith(f"@{annotation}(")
        for line in source.read_text().splitlines()
    )


def source_has_process_local_state(source: Path) -> bool:
    text = source.read_text()
    return (
        "new ConcurrentHashMap<" in text
        or "new java.util.concurrent.ConcurrentHashMap<" in text
        or "ConcurrentHashMap.newKeySet()" in text
        or source_has_annotation(source, "Cacheable")
        or "private final AtomicReference<" in text
        or "private ExecutorService " in text
        or "Caffeine.newBuilder" in text
    )


def load_catalog(test_case: unittest.TestCase) -> dict:
    catalog = json.loads(CATALOG.read_text())
    test_case.assertIsInstance(
        catalog,
        dict,
        "replica-safety catalog must expose schema and metric metadata",
    )
    return catalog


class ReplicaSafetyContractTest(unittest.TestCase):
    def test_every_scheduled_source_has_an_owner_decision_and_runtime_signal(self):
        catalog = load_catalog(self)
        self.assertEqual(
            "Matrix chat + ORISO frontend + ORISO-controlled Element Call/MatrixRTC fork + LiveKit",
            catalog["targetArchitecture"],
        )
        self.assertEqual(
            "userservice.scheduler.registered",
            catalog["schedulerRegistrationMetric"],
        )
        entries = [
            entry for entry in catalog["components"] if entry["kind"] == "scheduler"
        ]
        inventoried_sources = {entry["source"] for entry in entries}
        scheduled_sources = {
            str(source.relative_to(ROOT))
            for source in MAIN_JAVA.rglob("*.java")
            if source_has_annotation(source, "Scheduled")
        }

        self.assertEqual(scheduled_sources, inventoried_sources)
        for entry in entries:
            self.assertTrue(entry["owner"])
            self.assertIn(
                entry["risk"],
                {"correctness", "duplicate-side-effect", "performance"},
            )
            self.assertTrue(entry["decision"])
            self.assertEqual(
                [
                    "userservice.scheduler.executions",
                    "userservice.scheduler.duration",
                ],
                entry["signals"],
            )

    def test_every_process_local_state_source_has_an_owner_decision_and_signal(self):
        catalog = load_catalog(self)
        entries = [
            entry
            for entry in catalog["components"]
            if entry["kind"] == "local-state"
        ]
        inventoried_sources = {entry["source"] for entry in entries}
        local_state_sources = {
            str(source.relative_to(ROOT))
            for source in MAIN_JAVA.rglob("*.java")
            if source_has_process_local_state(source)
        }

        self.assertEqual(local_state_sources, inventoried_sources)
        for entry in entries:
            self.assertTrue(entry["owner"])
            self.assertIn(
                entry["risk"],
                {"correctness", "duplicate-side-effect", "performance"},
            )
            self.assertTrue(entry["decision"])
            self.assertTrue(entry["components"])
            self.assertIn("userservice.replica.local_state", entry["signals"])

    def test_catalog_contains_no_removed_chat_transport(self):
        catalog_text = CATALOG.read_text().lower()

        self.assertNotIn("rocket.chat", catalog_text)
        self.assertNotIn("rocketchat", catalog_text)
        self.assertNotIn("jitsi", catalog_text)


if __name__ == "__main__":
    unittest.main()
