import json
from pathlib import Path
import re
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "src/main/resources/replica-safety-components.json"
MAIN_JAVA = ROOT / "src/main/java"


def source_has_annotation(source: Path, annotation: str) -> bool:
    return any(
        source_has_annotation_text(line, annotation)
        for line in source.read_text().splitlines()
    )


def source_has_process_local_state(source: Path) -> bool:
    text = source.read_text()
    return (
        "new ConcurrentHashMap<" in text
        or "new java.util.concurrent.ConcurrentHashMap<" in text
        or "ConcurrentHashMap.newKeySet()" in text
        or source_has_annotation(source, "Cacheable")
        or re.search(
            r"(?m)^\s*(?:public|protected|private)\s+"
            r"(?:(?:static|final|volatile|transient)\s+)*"
            r"(?:java\.util\.concurrent\.atomic\.)?AtomicReference\s*<",
            text,
        )
        is not None
        or re.search(
            r"(?m)^\s*(?:public|protected|private)\s+"
            r"(?:(?:static|final|volatile|transient)\s+)*"
            r"(?:java\.util\.concurrent\.)?ExecutorService\s+",
            text,
        )
        is not None
        or "Caffeine.newBuilder" in text
    )


def scheduled_methods(source: Path) -> set[str]:
    methods = set()
    scheduled = False
    for line in source.read_text().splitlines():
        if source_has_annotation_text(line, "Scheduled"):
            scheduled = True
            continue
        if not scheduled or not line.strip() or line.lstrip().startswith("@"):
            continue
        declaration = re.search(
            r"\b(?:public|protected|private)\s+"
            r"(?:(?:static|final|synchronized)\s+)*"
            r"[\w<>, ?\[\].]+\s+(\w+)\s*\(",
            line,
        )
        if declaration:
            methods.add(declaration.group(1))
            scheduled = False
    return methods


def source_has_annotation_text(text: str, annotation: str) -> bool:
    return re.search(rf"@{re.escape(annotation)}\b", text) is not None


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
            self.assertEqual(
                scheduled_methods(ROOT / entry["source"]),
                set(entry["components"]),
                f"{entry['id']} must name every actual @Scheduled method exactly",
            )
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


class ReplicaSafetyDetectorTest(unittest.TestCase):
    def test_annotation_detection_accepts_no_arguments_and_same_line_annotations(self):
        with tempfile.TemporaryDirectory() as directory:
            no_arguments = Path(directory) / "NoArguments.java"
            no_arguments.write_text("@Scheduled\npublic void run() {}\n")
            same_line = Path(directory) / "SameLine.java"
            same_line.write_text(
                '@Profile("!testing") @Scheduled(cron = "0 * * * * *")\n'
                "public void run() {}\n"
            )

            self.assertTrue(source_has_annotation(no_arguments, "Scheduled"))
            self.assertTrue(source_has_annotation(same_line, "Scheduled"))

    def test_local_state_detection_accepts_final_executor_and_non_final_atomic_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            final_executor = Path(directory) / "FinalExecutor.java"
            final_executor.write_text(
                "private final ExecutorService executorService;\n"
            )
            mutable_reference = Path(directory) / "MutableReference.java"
            mutable_reference.write_text(
                "private AtomicReference<String> currentState;\n"
            )

            self.assertTrue(source_has_process_local_state(final_executor))
            self.assertTrue(source_has_process_local_state(mutable_reference))


if __name__ == "__main__":
    unittest.main()
