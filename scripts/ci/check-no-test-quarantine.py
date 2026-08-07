#!/usr/bin/env python3

import argparse
from pathlib import Path
import re
import sys


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
JAVA_UNICODE_ESCAPE = re.compile(r"\\u+([0-9a-fA-F]{4})")
QUARANTINE_ANNOTATION = re.compile(
    r"@\s*(?P<annotation>"
    r"(?:org\s*\.\s*junit\s*\.\s*"
    r"(?:jupiter\s*\.\s*api\s*\.\s*(?:condition\s*\.\s*)?)?)?"
    r"(?:Disabled[A-Za-z0-9_]*|Ignore))\b"
)


def translate_java_unicode_escapes(source: str) -> str:
    return JAVA_UNICODE_ESCAPE.sub(
        lambda match: chr(int(match.group(1), 16)),
        source,
    )


def java_code_only(source: str) -> str:
    output = []
    index = 0
    state = "code"

    def mask(characters: str) -> None:
        output.extend("\n" if character == "\n" else " " for character in characters)

    while index < len(source):
        if state == "code":
            if source.startswith("//", index):
                mask("//")
                index += 2
                state = "line-comment"
            elif source.startswith("/*", index):
                mask("/*")
                index += 2
                state = "block-comment"
            elif source.startswith('"""', index):
                mask('"""')
                index += 3
                state = "text-block"
            elif source[index] == '"':
                mask('"')
                index += 1
                state = "string"
            elif source[index] == "'":
                mask("'")
                index += 1
                state = "character"
            else:
                output.append(source[index])
                index += 1
        elif state == "line-comment":
            if source[index] == "\n":
                output.append("\n")
                index += 1
                state = "code"
            else:
                mask(source[index])
                index += 1
        elif state == "block-comment":
            if source.startswith("*/", index):
                mask("*/")
                index += 2
                state = "code"
            else:
                mask(source[index])
                index += 1
        elif state == "text-block":
            if source.startswith('"""', index):
                mask('"""')
                index += 3
                state = "code"
            elif source[index] == "\\" and index + 1 < len(source):
                mask(source[index : index + 2])
                index += 2
            else:
                mask(source[index])
                index += 1
        else:
            delimiter = '"' if state == "string" else "'"
            if source[index] == "\\" and index + 1 < len(source):
                mask(source[index : index + 2])
                index += 2
            elif source[index] == delimiter:
                mask(source[index])
                index += 1
                state = "code"
            else:
                mask(source[index])
                index += 1

    return "".join(output)


def find_quarantine_annotations(test_root: Path) -> list[str]:
    offenders = []
    for source in sorted(test_root.rglob("*.java")):
        code = java_code_only(translate_java_unicode_escapes(source.read_text()))
        for match in QUARANTINE_ANNOTATION.finditer(code):
            line_number = code.count("\n", 0, match.start()) + 1
            annotation = re.sub(r"\s+", "", match.group("annotation"))
            offenders.append(
                f"{source.relative_to(test_root)}:{line_number}: @{annotation}"
            )
    return offenders


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Reject disabled or ignored Java tests."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=REPOSITORY_ROOT / "src/test",
        help="Test source root to inspect.",
    )
    args = parser.parse_args()

    if not args.root.is_dir():
        print(f"Test source root does not exist: {args.root}", file=sys.stderr)
        return 2

    offenders = find_quarantine_annotations(args.root)
    if offenders:
        print("Test quarantine annotations are forbidden:")
        print("\n".join(offenders))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
