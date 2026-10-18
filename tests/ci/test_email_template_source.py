import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/verify-email-template-source.py"


class EmailTemplateSourceTest(unittest.TestCase):
    def test_every_app_locale_needs_both_mime_parts_before_sync(self):
        with tempfile.TemporaryDirectory() as directory:
            frontend = Path(directory)
            for locale in ("de", "fr"):
                app = frontend / "src/resources/i18n" / locale
                app.mkdir(parents=True)
                (app / "common.json").write_text("{}")
            dist = frontend / "src/emails/dist"
            dist.mkdir(parents=True)
            (dist / "catalogue.json").write_text(
                json.dumps(
                    {
                        "tones": ["de-sie", "fr"],
                        "locales": {"de-sie": {}, "fr": {"release": "pending-human-review"}},
                        "mails": {"willkommen": {"tones": {"de-sie": {}, "fr": {}}}},
                    }
                )
            )
            for locale in ("de-sie", "fr"):
                target = dist / "plain" / locale
                target.mkdir(parents=True)
                (target / "willkommen.html").write_text("html")
                (target / "willkommen.txt").write_text("text")

            ok = subprocess.run([sys.executable, str(SCRIPT), str(frontend)], capture_output=True)
            self.assertEqual(ok.returncode, 0, ok.stderr.decode())

            (dist / "plain/fr/willkommen.txt").unlink()
            incomplete = subprocess.run(
                [sys.executable, str(SCRIPT), str(frontend)], capture_output=True
            )
            self.assertNotEqual(incomplete.returncode, 0)
            self.assertIn("fr/willkommen.txt", incomplete.stderr.decode())


if __name__ == "__main__":
    unittest.main()
