import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
RUNNERS = ("run-authenticated-write-replicas.sh", "run-seeded-public-read-replicas.sh")


def executable(path: Path, body: str) -> None:
    path.write_text("#!/usr/bin/env bash\n" + body)
    path.chmod(0o755)


class ReplicaPackageFailureTest(unittest.TestCase):
    def test_both_runners_show_maven_failure_before_cleaning_up(self):
        for runner in RUNNERS:
            with self.subTest(runner=runner), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                scripts = root / "scripts" / "load"
                scripts.mkdir(parents=True)
                for script in (runner, "ensure-java-21.sh"):
                    shutil.copyfile(ROOT / "scripts" / "load" / script, scripts / script)

                bin_dir = root / "bin"
                bin_dir.mkdir()
                run_dir = root / "runs"
                run_dir.mkdir()
                executable(root / "mvnw", "echo 'simulated Maven package error' >&2\nexit 23\n")
                executable(bin_dir / "java", """echo 'openjdk version "21.0.0"' >&2\n""")
                executable(
                    bin_dir / "docker",
                    """case "$1" in
  inspect) echo healthy ;;
  port) echo 127.0.0.1:15000 ;;
esac
""",
                )
                executable(bin_dir / "curl", "exit 0\n")
                executable(
                    bin_dir / "python3",
                    """case "$1" in
  */allocate-ports.py) echo '15001 15002 15003' ;;
esac
""",
                )

                result = subprocess.run(
                    ["bash", str(scripts / runner)],
                    env={
                        **os.environ,
                        "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                        "TMPDIR": str(run_dir),
                        "USERSERVICE_KEEP_RUN_DIR": "false",
                    },
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                )

                self.assertEqual(result.returncode, 23, result.stderr)
                self.assertIn("simulated Maven package error", result.stderr)
                self.assertEqual(list(run_dir.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
