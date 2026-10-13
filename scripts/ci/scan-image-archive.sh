#!/usr/bin/env bash
set -euo pipefail

archive=${1:?OCI archive is required}
platforms=${2-}
if [[ ! "$platforms" =~ ^linux/(amd64|arm64)(,linux/(amd64|arm64))*$ ]]; then
  echo "Expected a nonempty list of linux/amd64 or linux/arm64 platforms." >&2
  exit 2
fi

scan_root=$(mktemp -d "${TMPDIR:-/tmp}/oriso-image-scan.XXXXXX")
trap 'rm -rf "$scan_root"' EXIT
IFS=',' read -ra targets <<< "$platforms"
for platform in "${targets[@]}"; do
  arch=${platform#linux/}
  # Trivy's OCI-layout reader may ignore --platform on a multi-image index.
  # Select the actual manifest first; never let an arm64 gate scan amd64 twice.
  skopeo --override-os linux --override-arch "$arch" copy --preserve-digests \
    "oci-archive:$archive" "oci:$scan_root/$arch"
  trivy image --input "$scan_root/$arch" --platform "$platform" \
    --format table --exit-code 1 --ignore-unfixed \
    --vuln-type os,library --severity CRITICAL,HIGH
done
