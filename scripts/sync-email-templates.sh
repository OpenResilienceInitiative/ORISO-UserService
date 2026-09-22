#!/usr/bin/env bash
# Copies the generated e-mail templates from ORISO-Frontend into this service.
#
# The templates are generated from the design system in ORISO-Frontend
# (`src/emails/`, `npm run emails:build`) and reviewed there as a diff. This
# service consumes the `plain` dialect — `{{placeholder}}` string replacement,
# no template engine. See ADR-020 in ORISO-Frontend.
#
#   scripts/sync-email-templates.sh [path-to-ORISO-Frontend]
set -euo pipefail

frontend="${1:-../ORISO-Frontend}"
src="$frontend/src/emails/dist"

if [[ ! -d "$src/plain" ]]; then
  echo "no generated templates at $src/plain — run 'npm run emails:build' in $frontend" >&2
  exit 1
fi

target="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/emails"
rm -rf "$target"
mkdir -p "$target"
cp -R "$src/plain/." "$target/"
cp "$src/catalogue.json" "$target/catalogue.json"

echo "synced $(find "$target" -type f | wc -l | tr -d ' ') files into src/main/resources/emails"
echo "review the diff before committing — it is the only review this content gets here."

# This sync wipes the target directory, so a template the generator does not know about
# disappears without a word. Name the ones the service cannot start without, loudly.
missing=()
for tone in de-sie de-du en; do
  for id in einladung-freitext; do
    for ext in html txt; do
      [[ -f "$target/$tone/$id.$ext" ]] || missing+=("$tone/$id.$ext")
    done
  done
done
if (( ${#missing[@]} )); then
  echo >&2
  echo "ERROR: the sync removed templates this service renders: ${missing[*]}" >&2
  echo "       Add them to ORISO-Frontend src/emails/ and regenerate, or restore them here." >&2
  exit 1
fi
