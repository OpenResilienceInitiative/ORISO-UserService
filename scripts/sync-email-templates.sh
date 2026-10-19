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

# Validate the App's complete locale/occasion set before touching the installed copy.
python3 "$(dirname "$0")/verify-email-template-source.py" "$frontend"
if [[ -n "$(git -C "$frontend" status --porcelain -- src/emails)" ]]; then
  echo "Frontend e-mail artifacts or source are uncommitted; commit and review them before sync" >&2
  exit 1
fi

target="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/emails"
if [[ ! -x "$frontend/node_modules/.bin/vite-node" ]]; then
  echo "Frontend dependencies are required to export e-mail fragments: run npm ci in $frontend" >&2
  exit 1
fi
stage="$(mktemp -d "$(dirname "$target")/.emails-sync.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
cp -R "$src/plain/." "$stage/"
cp "$src/catalogue.json" "$stage/catalogue.json"
"$frontend/node_modules/.bin/vite-node" --root / \
  "$(cd "$(dirname "$0")" && pwd)/export-email-fragments.mts" \
  "$(cd "$frontend" && pwd)" "$stage/fragments"
frontend_commit="$(git -C "$frontend" rev-parse HEAD)"
python3 "$(cd "$(dirname "$0")" && pwd)/email-template-integrity.py" \
  build "$stage" "$frontend_commit"

# The source preflight above checks every App locale, occasion and MIME part.
# Keep an installed-copy check for the operator-authored invitation frame.
missing=()
for tone in de-sie de-du en fr ru ti tr; do
  for id in einladung-freitext; do
    for ext in html txt; do
      [[ -f "$stage/$tone/$id.$ext" ]] || missing+=("$tone/$id.$ext")
    done
  done
done
if (( ${#missing[@]} )); then
  echo >&2
  echo "ERROR: the sync removed templates this service renders: ${missing[*]}" >&2
  echo "       Add them to ORISO-Frontend src/emails/ and regenerate, or restore them here." >&2
  exit 1
fi

rm -rf "$target"
mv "$stage" "$target"
trap - EXIT
echo "synced $(find "$target" -type f | wc -l | tr -d ' ') files into src/main/resources/emails"
echo "review the diff before committing — it is the only review this content gets here."
