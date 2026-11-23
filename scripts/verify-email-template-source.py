#!/usr/bin/env python3
"""Refuse an incomplete Frontend mail artefact before replacing service resources."""

import json
import sys
from pathlib import Path


def missing_templates(frontend: Path) -> list[str]:
    catalogue = json.loads((frontend / "src/emails/dist/catalogue.json").read_text())
    plain = frontend / "src/emails/dist/plain"
    app_catalogue = frontend / "src/resources/i18n"
    app_locales = sorted(
        entry.name
        for entry in app_catalogue.iterdir()
        if entry.is_dir() and (entry / "common.json").is_file()
    )
    missing = []
    for app_locale in app_locales:
        mail_locale = {
            "de": "de-sie",
            "de@informal": "de-du",
        }.get(app_locale, app_locale)
        if mail_locale not in catalogue.get("tones", []):
            missing.append(f"catalogue tone {mail_locale} (App {app_locale})")
        if mail_locale not in catalogue.get("locales", {}):
            missing.append(f"catalogue review state {mail_locale}")
        for occasion, entry in catalogue.get("mails", {}).items():
            if mail_locale not in entry.get("tones", {}):
                missing.append(f"catalogue subject {mail_locale}/{occasion}")
            for extension in ("html", "txt"):
                file = plain / mail_locale / f"{occasion}.{extension}"
                if not file.is_file():
                    missing.append(str(file.relative_to(frontend)))
    if not catalogue.get("mails"):
        missing.append("catalogue occasions")
    return missing


if __name__ == "__main__":
    missing = missing_templates(Path(sys.argv[1]))
    if missing:
        sys.exit("Incomplete e-mail source; existing resources preserved:\n  " + "\n  ".join(missing))
