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
        or "net.sf.ehcache.CacheManager.newInstance" in text
    )


class ReplicaSafetyContractTest(unittest.TestCase):
    def test_every_scheduled_component_has_a_decision_and_runtime_signal(self):
        catalog = json.loads(CATALOG.read_text())
        self.assertEqual(
            "userservice.scheduler.registered",
            catalog["schedulerRegistrationMetric"],
        )
        scheduler_entries = {
            entry["source"]: entry
            for entry in catalog["components"]
            if entry["kind"] == "scheduler"
        }
        scheduled_sources = {
            str(source.relative_to(ROOT))
            for source in MAIN_JAVA.rglob("*.java")
            if source_has_annotation(source, "Scheduled")
        }

        self.assertEqual(scheduled_sources, set(scheduler_entries))
        for entry in scheduler_entries.values():
            self.assertTrue(entry["owner"])
            self.assertIn(
                entry["risk"],
                {"correctness", "duplicate-side-effect", "performance-only"},
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
        catalog = json.loads(CATALOG.read_text())
        local_state_entries = {
            entry["source"]: entry
            for entry in catalog["components"]
            if entry["kind"] == "local-state"
        }
        local_state_sources = {
            str(source.relative_to(ROOT))
            for source in MAIN_JAVA.rglob("*.java")
            if source_has_process_local_state(source)
        }

        self.assertEqual(local_state_sources, set(local_state_entries))
        for entry in local_state_entries.values():
            self.assertTrue(entry["owner"])
            self.assertIn(
                entry["risk"],
                {"correctness", "duplicate-side-effect", "performance-only"},
            )
            self.assertTrue(entry["decision"])
            self.assertTrue(entry["components"])
            self.assertIn("userservice.replica.local_state", entry["signals"])


if __name__ == "__main__":
    unittest.main()
