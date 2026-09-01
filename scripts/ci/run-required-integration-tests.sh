#!/usr/bin/env bash

set -euo pipefail

maven_wrapper="${ORISO_MAVEN_WRAPPER:-./mvnw}"

# Real-MariaDB contracts are owned by the separately required mariadb-contract job. Excluding them
# from discovery here prevents JUnit's environment conditions from manufacturing skipped reports.
# SupportRoomMigrationConvergenceIT is excluded here for the same reason: it is @EnabledIf...
# LIQUIBASE_IT_DB_URL and is run by the mariadb-contract job. An earlier revision justified the
# exclusion with 3bfa3d06 ("Keep support migration outside refactor CI"), but that commit is on
# feature/user-service-refactor only and never reached pre-dev, so on this branch the exclusion
# would have removed the test from required CI entirely instead of moving it.
mariadb_owned_tests=(
  DatabaseChangelogDriftIT
  AdminStatisticsRepositoryMariaDbIT
  ProvisioningCompensationMariaDbIT
  ScheduledTaskClaimMariaDbIT
  TutorialProgressServiceMariaDbReplicaIT
  OrganizerMariaDbReplicaIT
  DeactivateGroupChatSchedulerMariaDbReplicaIT
  DeleteUserAccountSchedulerMariaDbReplicaIT
  DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT
  SupportRoomMigrationConvergenceIT
)
required_test_pattern="**/*IT"
for mariadb_owned_test in "${mariadb_owned_tests[@]}"; do
  required_test_pattern+=",!${mariadb_owned_test}"
done

# The application/H2 *IT suite is the required contract here. Unit tests and real-MariaDB tests
# have their own required jobs, so they are not discovered in this report inventory.
"${maven_wrapper}" -B -Dskip.unit-tests=true "-Dtest=${required_test_pattern}" clean integration-test

python3 - <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = sorted(Path("target/surefire-reports").glob("TEST-*IT.xml"))
# The complete Matrix-only suite produces at least 75 reports / 830 tests. Keep these
# bounds explicit so Maven cannot silently skip a material part of the suite. The
# previous 900-test floor included deleted Rocket.Chat-only tests.
minimum_reports = 75
minimum_tests = 830
required_e2e = {
    "AppointmentControllerE2EIT",
    "ConversationControllerAuthorizationIT",
    "ConversationControllerIT",
    "UserAdminControllerE2EIT",
    "UserControllerE2EIT",
}

tests = failures = errors = skipped = 0
classes = set()
for report in reports:
    root = ET.parse(report).getroot()
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))
    classes.add(root.attrib.get("name", "").rsplit(".", 1)[-1])

executed = tests - skipped
missing_e2e = sorted(required_e2e - classes)
print(
    "Required integration contract: "
    f"reports={len(reports)} tests={tests} executed={executed} failures={failures} "
    f"errors={errors} skipped={skipped}"
)
contract_failed = False
if len(reports) < minimum_reports:
    print(
        f"Expected at least {minimum_reports} integration reports, found {len(reports)}.",
        file=sys.stderr,
    )
    contract_failed = True
if executed < minimum_tests:
    print(
        f"Expected at least {minimum_tests} executed integration tests, found {executed}.",
        file=sys.stderr,
    )
    contract_failed = True
if skipped:
    print(f"Integration reports contain {skipped} skipped tests.", file=sys.stderr)
    contract_failed = True
if failures or errors:
    print("Integration reports contain failures or errors.", file=sys.stderr)
    contract_failed = True
if missing_e2e:
    print(f"Missing critical E2E reports: {', '.join(missing_e2e)}", file=sys.stderr)
    contract_failed = True
if contract_failed:
    sys.exit(1)
PY
