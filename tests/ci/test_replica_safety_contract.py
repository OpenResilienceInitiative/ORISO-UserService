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


CLAIM_SERVICE = "ScheduledTaskClaimService"
CLAIM_CALLS = ("tryClaim(", "tryClaimLease(", "runIfClaimed(")
CLAIM_MECHANISMS = {
    # Run-level lease in the scheduled_task_claim table.
    "scheduled-task-claim",
    # Insert of a uniquely keyed claim/fingerprint row before the side effect.
    "unique-claim-row",
    # Pessimistic database row lock around the work.
    "row-lock",
    # A single set-based statement that is safe to run twice at once.
    "idempotent-statement",
}
DECISION_KEYWORDS = ("claim", "lease", "lock", "idempotent")


def claim_source(entry: dict, root: Path = ROOT) -> Path:
    claim = entry["claim"]
    return root / claim.get("source", entry["source"])


def claim_reference(entry: dict) -> str:
    claim = entry["claim"]
    if claim["mechanism"] == "scheduled-task-claim":
        return CLAIM_SERVICE
    return claim.get("reference", "")


def claim_violation(entry: dict, root: Path = ROOT):
    """Returns why an entry's declared claim is not backed by code, or None."""
    claim = entry["claim"]
    if claim.get("mechanism") not in CLAIM_MECHANISMS:
        return f"unknown claim mechanism {claim.get('mechanism')!r}"
    reference = claim_reference(entry)
    if not reference:
        return "claim mechanism needs a 'reference' that the claim source contains"
    source = claim_source(entry, root)
    if not source.is_file():
        return f"claim source {source} does not exist"
    text = source.read_text()
    if reference not in text:
        return f"{source.name} does not reference {reference}"
    if claim["mechanism"] == "scheduled-task-claim" and not any(
        call in text for call in CLAIM_CALLS
    ):
        return f"{source.name} imports {CLAIM_SERVICE} but never claims ({', '.join(CLAIM_CALLS)})"
    return None


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

    def test_every_declared_claim_is_backed_by_its_source(self):
        catalog = load_catalog(self)
        claimed = [entry for entry in catalog["components"] if "claim" in entry]
        self.assertTrue(claimed, "the inventory must declare its claim-protected entries")
        for entry in claimed:
            self.assertEqual("scheduler", entry["kind"], entry["id"])
            self.assertIsNone(claim_violation(entry), entry["id"])
            self.assertNotEqual(
                "blocker",
                entry["status"],
                f"{entry['id']} declares a claim and must not stay a blocker",
            )
            decision = entry["decision"].lower()
            self.assertTrue(
                claim_reference(entry).lower() in decision
                or any(keyword in decision for keyword in DECISION_KEYWORDS),
                f"{entry['id']} decision must name its mechanism",
            )

    def test_scheduler_class_using_the_claim_service_declares_the_claim(self):
        catalog = load_catalog(self)
        for entry in catalog["components"]:
            if entry["kind"] != "scheduler":
                continue
            if CLAIM_SERVICE in (ROOT / entry["source"]).read_text():
                self.assertIn(
                    "claim",
                    entry,
                    f"{entry['id']} references {CLAIM_SERVICE} but does not declare a claim",
                )
                self.assertEqual(
                    "scheduled-task-claim", entry["claim"]["mechanism"], entry["id"]
                )

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

    def test_claim_detection_fails_when_the_source_never_references_the_claim_service(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Plain.java").write_text("class Plain { void run() {} }\n")
            (root / "Imports.java").write_text(
                "import x.ScheduledTaskClaimService;\nclass Imports { }\n"
            )
            (root / "Claims.java").write_text(
                "class Claims { ScheduledTaskClaimService c; void r() { c.runIfClaimed(); } }\n"
            )
            entry = lambda source, mechanism="scheduled-task-claim", **extra: {
                "source": source,
                "claim": {"mechanism": mechanism, **extra},
            }

            self.assertIn("does not reference", claim_violation(entry("Plain.java"), root))
            self.assertIn("never claims", claim_violation(entry("Imports.java"), root))
            self.assertIsNone(claim_violation(entry("Claims.java"), root))
            self.assertIn("unknown", claim_violation(entry("Claims.java", "magic"), root))
            self.assertIn("reference", claim_violation(entry("Claims.java", "row-lock"), root))
            self.assertIsNone(
                claim_violation(
                    entry("Claims.java", "row-lock", reference="ScheduledTaskClaimService"), root
                )
            )
            self.assertIn(
                "does not exist",
                claim_violation(entry("Missing.java"), root),
            )


if __name__ == "__main__":
    unittest.main()
