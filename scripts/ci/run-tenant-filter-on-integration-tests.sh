#!/usr/bin/env bash
# Runs the listed integration tests with the tenant filter on, as production does. The testing
# profile keeps it off (application-testing.properties), so without this job a test could pass
# while the same code fails for every Träger in production.

set -euo pipefail

maven_wrapper="${ORISO_MAVEN_WRAPPER:-./mvnw}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
list="${script_dir}/tenant-filter-on-its.txt"
maven_settings="${script_dir}/github-maven-settings.xml"

pattern="$(grep -Ev '^[[:space:]]*(#|$)' "${list}" | paste -sd, -)"

status=0
# ORISO_MAVEN_ARGS adds local flags such as -o.
# shellcheck disable=SC2086
"${maven_wrapper}" -B -s "${maven_settings}" ${ORISO_MAVEN_ARGS:-} -Dskip.unit-tests=true -Dmultitenancy.enabled=true \
  "-Dtest=${pattern}" -Dsurefire.failIfNoSpecifiedTests=false clean integration-test || status=$?

python3 - "${list}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

expected = {
    line.strip()
    for line in Path(sys.argv[1]).read_text().splitlines()
    if line.strip() and not line.lstrip().startswith("#")
}
ran = {}
for report in Path("target/surefire-reports").glob("TEST-*.xml"):
    root = ET.parse(report).getroot()
    name = root.attrib.get("name", "").rsplit(".", 1)[-1]
    executed = int(root.attrib.get("tests", 0)) - int(root.attrib.get("skipped", 0))
    broken = int(root.attrib.get("failures", 0)) + int(root.attrib.get("errors", 0))
    ran[name] = (executed, broken)

missing = sorted(name for name in expected if ran.get(name, (0, 0))[0] == 0)
broken = sorted(name for name, (_, count) in ran.items() if count)
executed = sum(count for count, _ in ran.values())
print(f"Tenant filter on: classes={len(ran)} tests={executed} broken={len(broken)}")
if missing:
    print(f"Listed but not run: {', '.join(missing)}", file=sys.stderr)
if broken:
    print(f"Failing with the tenant filter on: {', '.join(broken)}", file=sys.stderr)
sys.exit(1 if missing or broken else 0)
PY
exit "${status}"
