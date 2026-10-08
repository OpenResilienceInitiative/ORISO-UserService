#!/usr/bin/env bash
# Copies the tenant-colour golden fixture from ORISO-Frontend into this service and refreshes the
# checksum that EmailColorsParityTest pins. The fixture is OWNED BY ORISO-Frontend
# (src/utils/theme/__fixtures__/tenant-colour-golden.json, generated from the real theme engine);
# never edit the copy here. ORISO-UserService#1252, ADR-026 amendment 2026-10-02.
#
#   scripts/sync-tenant-colour-golden.sh [path-to-ORISO-Frontend]
#
# Run with --check to fail (without writing) when the copy differs from the Frontend file.
set -euo pipefail

check=0
if [[ "${1:-}" == "--check" ]]; then
  check=1
  shift
fi
frontend="${1:-../ORISO-Frontend}"
src="$frontend/src/utils/theme/__fixtures__/tenant-colour-golden.json"
target_dir="$(cd "$(dirname "$0")/.." && pwd)/src/test/resources/email"
target="$target_dir/tenant-colour-golden.json"

if [[ ! -f "$src" ]]; then
  echo "no golden fixture at $src" >&2
  exit 1
fi
if [[ -n "$(git -C "$frontend" status --porcelain -- src/utils/theme/__fixtures__/tenant-colour-golden.json)" ]]; then
  echo "the Frontend fixture is uncommitted; commit and review it there before syncing" >&2
  exit 1
fi

if [[ "$check" == 1 ]]; then
  cmp -s "$src" "$target" || { echo "tenant-colour-golden.json differs from $src" >&2; exit 1; }
  echo "tenant-colour-golden.json is identical to the Frontend fixture"
  exit 0
fi

cp "$src" "$target"
shasum -a 256 "$target" | cut -d' ' -f1 > "$target.sha256"
echo "copied from Frontend commit $(git -C "$frontend" rev-parse --short HEAD); refreshed $target.sha256"
