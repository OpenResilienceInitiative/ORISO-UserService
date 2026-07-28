#!/usr/bin/env bash

set -euo pipefail

maven_wrapper="${ORISO_MAVEN_WRAPPER:-./mvnw}"

# The complete *IT suite is the required contract. Unit tests are owned by the
# validate job, so they are skipped here to keep the two conclusions explicit.
"${maven_wrapper}" -B -Dskip.unit-tests=true clean integration-test

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

missing_e2e = sorted(required_e2e - classes)
print(
    "Required integration contract: "
    f"reports={len(reports)} tests={tests} failures={failures} "
    f"errors={errors} skipped={skipped}"
)
if len(reports) < minimum_reports:
    print(
        f"Expected at least {minimum_reports} integration reports, found {len(reports)}.",
        file=sys.stderr,
    )
    sys.exit(1)
if tests < minimum_tests:
    print(f"Expected at least {minimum_tests} integration tests, found {tests}.", file=sys.stderr)
    sys.exit(1)
if failures or errors:
    print("Integration reports contain failures or errors.", file=sys.stderr)
    sys.exit(1)
if missing_e2e:
    print(f"Missing critical E2E reports: {', '.join(missing_e2e)}", file=sys.stderr)
    sys.exit(1)
PY
