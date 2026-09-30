#!/usr/bin/env python3
"""Create or verify the copied e-mail artifact manifest without a moving remote."""

import hashlib
import json
import sys
from pathlib import Path


def files_and_hashes(root: Path) -> dict[str, str]:
    return {
        file.relative_to(root).as_posix(): hashlib.sha256(file.read_bytes()).hexdigest()
        for file in sorted(root.rglob("*"))
        if file.is_file() and file != root / "manifest.json"
    }


def main() -> None:
    if len(sys.argv) not in (3, 4) or sys.argv[1] not in ("build", "verify"):
        sys.exit("Usage: email-template-integrity.py build|verify EMAILS_DIR [FRONTEND_COMMIT]")
    mode, root = sys.argv[1], Path(sys.argv[2])
    manifest_path = root / "manifest.json"
    actual = files_and_hashes(root)
    if mode == "build":
        if len(sys.argv) != 4 or not sys.argv[3]:
            sys.exit("build requires the exact Frontend source commit")
        manifest = {"schemaVersion": 1, "frontendCommit": sys.argv[3], "files": actual}
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        return
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("schemaVersion") != 1 or not manifest.get("frontendCommit"):
        sys.exit("invalid e-mail artifact manifest")
    expected = manifest.get("files", {})
    if actual != expected:
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        changed = sorted(k for k in set(actual) & set(expected) if actual[k] != expected[k])
        sys.exit(f"e-mail artifacts differ from pinned Frontend copy: missing={missing}, extra={extra}, changed={changed}")


if __name__ == "__main__":
    main()
