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
    def test_reference_reads_use_tenant_scoped_bounded_shared_cache(self):
        catalog = json.loads(CATALOG.read_text())
        shared_cache = (
            MAIN_JAVA
            / "de/caritas/cob/userservice/api/service/cache/SharedReadCache.java"
        ).read_text()
        replica_test = (
            ROOT
            / "src/test/java/de/caritas/cob/userservice/api/service/cache/"
            "SharedReadCacheRedisIT.java"
        ).read_text()
        expected_sources = {
            "agency-read-cache": (
                "src/main/java/de/caritas/cob/userservice/api/service/agency/"
                "AgencyService.java",
                "CacheName.AGENCY",
            ),
            "consulting-type-read-cache": (
                "src/main/java/de/caritas/cob/userservice/api/service/"
                "ConsultingTypeService.java",
                "CacheName.CONSULTING_TYPE",
            ),
            "topic-read-cache": (
                "src/main/java/de/caritas/cob/userservice/api/service/"
                "consultingtype/TopicService.java",
                "CacheName.TOPIC",
            ),
        }

        self.assertIn(
            "private static final Duration MAXIMUM_TTL = Duration.ofSeconds(60)",
            shared_cache,
        )
        self.assertIn("tryAcquireLoadLock", shared_cache)
        self.assertIn("falling back to upstream", shared_cache)
        self.assertIn(
            "referenceDataListsRoundTripAcrossReplicasAndStayTenantIsolated",
            replica_test,
        )

        entries = {entry["id"]: entry for entry in catalog["components"]}
        for component_id, (source_path, cache_name) in expected_sources.items():
            source = (ROOT / source_path).read_text()
            entry = entries[component_id]
            self.assertNotIn("@Cacheable", source)
            self.assertIn("SharedReadCache", source)
            self.assertIn(cache_name, source)
            self.assertIn('"tenant:"', source)
            self.assertEqual("shared-read-cache", entry["kind"])
            self.assertEqual(source_path, entry["source"])
            self.assertIn("tenant-scoped shared Redis", entry["decision"])
            self.assertIn("60-second", entry["decision"])
            self.assertIn("cold-load lock", entry["decision"])
            self.assertIn("fail open", entry["decision"])
            self.assertEqual(
                ["userservice.shared_read_cache.operations"],
                entry["signals"],
            )

    def test_inactive_account_recovery_remains_provider_gated_and_migrated(self):
        application_properties = (
            ROOT / "src/main/resources/application.properties"
        ).read_text()
        mail_contract = (ROOT / "services/mailservice.yaml").read_text()
        master_changelog = (
            ROOT / "src/main/resources/db/changelog/userservice-master.xml"
        ).read_text()
        migration = (
            ROOT
            / "src/main/resources/db/changelog/changeset/"
            "0079_inactive_account_notification_recovery/migrate.sql"
        ).read_text()
        replica_test = (
            ROOT
            / "src/test/java/de/caritas/cob/userservice/api/workflow/"
            "inactiveaccountnotification/service/"
            "InactiveAccountNotificationServiceReplicaIT.java"
        ).read_text()
        catalog = json.loads(CATALOG.read_text())
        decision = next(
            entry["decision"]
            for entry in catalog["components"]
            if entry["id"] == "inactive-account-notification"
        )

        self.assertIn(
            "inactive.account.notification.idempotent-recovery.enabled=false",
            application_properties,
        )
        self.assertIn("Idempotency-Key", mail_contract)
        self.assertIn(
            "0079_inactive_account_notification_recovery/0079_changeSet.xml",
            master_changelog,
        )
        self.assertIn("email_dispatch_started_at", migration)
        self.assertIn("email_dispatch_attempt_count", migration)
        self.assertIn("email_idempotency_key", migration)
        self.assertIn("email_body", migration)
        self.assertIn(
            "acceptedMailIsRecoveredAfterCrashWithSameOpaqueIdempotencyKey",
            replica_test,
        )
        self.assertIn("Recovery is disabled by default", decision)
        self.assertIn("deployed MailService", decision)
        self.assertIn("keep one replica", decision)

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
