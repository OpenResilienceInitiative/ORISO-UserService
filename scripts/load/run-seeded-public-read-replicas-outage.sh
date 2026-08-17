#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

export AGENCY_STUB_STATUS=503
export USERSERVICE_LOAD_EXPECT_AGENCY_FALLBACK=true
export USERSERVICE_LOAD_MAX_FALLBACK_WARNINGS_PER_REPLICA=1

exec "${repo_root}/scripts/load/run-seeded-public-read-replicas.sh"
