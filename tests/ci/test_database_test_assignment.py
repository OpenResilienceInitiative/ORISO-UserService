import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DatabaseTestAssignmentTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        scripts = self.root / "scripts/ci"
        shutil.copytree(ROOT / "scripts/ci", scripts)
        self.assignment = scripts / "database-test-assignment.py"
        self.h2 = scripts / "run-required-integration-tests.sh"
        self.report_directory = self.root / "target/surefire-reports"
        self.report_directory.mkdir(parents=True)
        self.manifest = scripts / "mariadb-contract-tests.txt"
        self.manifest.write_text(
            "standard SyntheticStandardIT\nguest SyntheticGuestIT\n"
        )
        self.arguments = self.root / "arguments.txt"

    def run_ci(self, *command):
        env = os.environ.copy()
        env["PATH"] = f"{self.root}{os.pathsep}{env.get('PATH', '')}"
        return subprocess.run(
            command, cwd=self.root, env=env, capture_output=True, text=True, check=False
        )

    def write_maven(self, reports):
        maven = self.root / "mvnw"
        maven.write_text(
            "#!/usr/bin/env python3\n"
            "import sys\n"
            "from pathlib import Path\n"
            "from xml.etree import ElementTree as ET\n"
            "Path('arguments.txt').write_text('\\n'.join(sys.argv[1:]))\n"
            f"for name, total, skipped in {reports!r}:\n"
            "    report = Path(f'target/surefire-reports/TEST-demo.{name}.xml')\n"
            "    suite = ET.Element('testsuite', name=f'demo.{name}', tests=str(total),\n"
            "                       skipped=str(skipped), failures='0', errors='0')\n"
            "    ET.ElementTree(suite).write(report)\n"
        )
        maven.chmod(0o755)

    def test_single_assignment_controls_both_maven_entry_points(self):
        self.write_maven([])
        self.run_ci(self.h2)
        self.assertIn(
            "-Dtest=**/*IT,!SyntheticStandardIT,!SyntheticGuestIT",
            self.arguments.read_text().splitlines(),
        )

        self.write_maven([("SyntheticStandardIT", 1, 0)])
        result = self.run_ci("python3", self.assignment, "standard")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(
            "-Dtest=SyntheticStandardIT", self.arguments.read_text().splitlines()
        )

        self.write_maven([("SyntheticGuestIT", 2, 0)])
        result = self.run_ci("python3", self.assignment, "guest")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(
            "-Dtest=SyntheticGuestIT", self.arguments.read_text().splitlines()
        )

    def test_native_contract_rejects_stale_and_skipped_reports(self):
        self.write_maven([("SyntheticStandardIT", 2, 0)])
        self.assertEqual(
            0, self.run_ci("python3", self.assignment, "standard").returncode
        )

        self.write_maven([])
        result = self.run_ci("python3", self.assignment, "standard")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SyntheticStandardIT", result.stderr)

        self.write_maven([("SyntheticStandardIT", 2, 1)])
        result = self.run_ci("python3", self.assignment, "standard")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SyntheticStandardIT", result.stderr)

    def test_guest_migration_requires_both_cases(self):
        self.write_maven([("SyntheticGuestIT", 1, 0)])
        result = self.run_ci("python3", self.assignment, "guest")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SyntheticGuestIT", result.stderr)

    def test_guest_migration_keeps_the_maven_central_retry(self):
        self.write_maven([("SyntheticGuestIT", 2, 0)])
        wrapper = self.root / "mvnw"
        success = wrapper.read_text()
        wrapper.write_text(
            success.replace(
                "from pathlib import Path\n",
                "from pathlib import Path\n"
                "if '-U' not in sys.argv:\n"
                "    print('[ERROR] Could not transfer artifact demo from/to central "
                "(https://repo.maven.apache.org/maven2): status code: 429')\n"
                "    sys.exit(1)\n",
            )
        )
        sleep = self.root / "sleep"
        sleep.write_text('#!/usr/bin/env bash\n[[ "$*" == 15 ]]\n')
        sleep.chmod(0o755)
        result = self.run_ci("python3", self.assignment, "guest")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-U", self.arguments.read_text().splitlines())

    def test_assignment_rejects_duplicate_classes_before_running_maven(self):
        self.manifest.write_text(
            "standard SyntheticStandardIT\nguest SyntheticStandardIT\n"
        )
        self.write_maven([])
        result = self.run_ci(self.h2)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SyntheticStandardIT", result.stderr)
        self.assertFalse(self.arguments.exists())
