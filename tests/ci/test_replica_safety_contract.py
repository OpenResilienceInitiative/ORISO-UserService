import json
from pathlib import Path
import re
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "src/main/resources/replica-safety-components.json"
MAIN_JAVA = ROOT / "src/main/java"
CLAIM_MECHANISMS = {"scheduled-task-claim", "row-lock", "unique-claim-row"}
SOURCE_COMMENTS = re.compile(
    r"""("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|//[^\n]*|/\*[\s\S]*?\*/|^[ \t]*--[^\n]*""",
    re.MULTILINE,
)


def without_comments(text: str) -> str:
    return SOURCE_COMMENTS.sub(lambda match: match.group(1) or " ", text)


def uses_scheduled_task_claim(text: str) -> bool:
    text = SOURCE_COMMENTS.sub(" ", text)
    receivers = re.findall(r"\bScheduledTaskClaimService\s+(\w+)\s*[,;)=]", text)
    return any(
        re.search(
            rf"\b{re.escape(receiver)}\s*\.\s*(?:tryClaim|tryClaimLease|runIfClaimed)\s*\(",
            text,
        )
        for receiver in receivers
    )


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
            r"\b(?:(?:public|protected|private)\s+)?"
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

    def test_declared_scheduler_claims_are_backed_by_their_sources(self):
        entries = [
            entry for entry in load_catalog(self)["components"] if "claim" in entry
        ]
        self.assertTrue(entries, "inventory must include its verified scheduler claims")
        for entry in entries:
            with self.subTest(scheduler=entry["id"]):
                claim = entry["claim"]
                evidence = claim["evidence"]
                self.assertEqual("scheduler", entry["kind"])
                if entry["status"] == "blocker":
                    covered = claim.get("coveredComponents", [])
                    self.assertTrue(covered, "a blocker claim must name the protected methods")
                    self.assertLess(
                        set(covered),
                        set(entry["components"]),
                        "some scheduled methods must remain uncovered on a blocker",
                    )
                    self.assertTrue(
                        all(
                            any(
                                f"{method}(" in fragment
                                for item in evidence
                                if item.get("source", entry["source"]) == entry["source"]
                                for fragment in item["contains"]
                            )
                            for method in covered
                        ),
                        "evidence must name each protected scheduled method on a blocker",
                    )
                else:
                    self.assertIn(entry["status"], {"bounded", "safe"})
                    self.assertNotIn("coveredComponents", claim)
                self.assertIn(claim["mechanism"], CLAIM_MECHANISMS)
                self.assertIn(
                    entry["source"],
                    {item.get("source", entry["source"]) for item in evidence},
                    "evidence must start at the scheduled source",
                )
                sources = []
                for item in evidence:
                    source = ROOT / item.get("source", entry["source"])
                    self.assertTrue(source.is_file(), source)
                    self.assertTrue(item["contains"], source)
                    text = without_comments(source.read_text())
                    for fragment in item["contains"]:
                        self.assertIn(fragment, text, str(source.relative_to(ROOT)))
                    sources.append(text)
                if claim["mechanism"] == "scheduled-task-claim":
                    self.assertTrue(
                        any(uses_scheduled_task_claim(text) for text in sources),
                        "a ScheduledTaskClaimService receiver must actually claim work",
                    )

    def test_scheduled_task_claims_are_catalogued(self):
        for entry in load_catalog(self)["components"]:
            if entry["kind"] != "scheduler":
                continue
            scheduled_source = ROOT / entry["source"]
            if (
                uses_scheduled_task_claim(scheduled_source.read_text())
                or "ScheduledTaskClaimService" in entry["decision"]
            ):
                with self.subTest(scheduler=entry["id"]):
                    self.assertEqual(
                        "scheduled-task-claim",
                        entry.get("claim", {}).get("mechanism"),
                        "catalogue the direct or delegated ScheduledTaskClaimService protection",
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

    def test_claim_detection_requires_a_call_on_the_claim_service(self):
        self.assertFalse(
            uses_scheduled_task_claim(
                "import x.ScheduledTaskClaimService;\n"
                "class Sample { ScheduledTaskClaimService claims;\n"
                "  /* claims.tryClaim(task, duration); */\n"
                '  String hint = "claims.tryClaim(task, duration)";\n'
                "  void run() { another.tryClaim(task, duration); } }"
            )
        )
        self.assertTrue(
            uses_scheduled_task_claim(
                "class Sample { ScheduledTaskClaimService claims;\n"
                "  void run() { claims.tryClaimLease(task, duration); } }"
            )
        )

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
