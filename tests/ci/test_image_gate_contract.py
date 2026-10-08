import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / ".github/actions/docker-build-push/action.yml"


def document(path):
    return yaml.safe_load(path.read_text())


class ImageGateContractTest(unittest.TestCase):
    def run_script(self, name, arguments, failing_command="", failing_arch="", tags=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log = root / "commands.jsonl"
            executable = root / "fake-command"
            executable.write_text(
                "#!/usr/bin/env python3\n"
                "import json,os,pathlib,sys\n"
                "name=pathlib.Path(sys.argv[0]).name\n"
                "args=sys.argv[1:]\n"
                "with open(os.environ['COMMAND_LOG'],'a') as out:\n"
                " out.write(json.dumps([name]+args)+'\\n')\n"
                "if name==os.environ.get('FAILING_COMMAND'):\n"
                " arch=os.environ.get('FAILING_ARCH','')\n"
                " if not arch or any(arch in a for a in args): sys.exit(23)\n"
                "if '--digestfile' in args:\n"
                " pathlib.Path(args[args.index('--digestfile')+1]).write_text(os.environ['DIGEST'])\n"
            )
            executable.chmod(0o755)
            for command in ("skopeo", "trivy"):
                (root / command).symlink_to(executable)
            env = os.environ.copy()
            env.update(
                PATH=f"{root}:{env['PATH']}",
                COMMAND_LOG=str(log),
                FAILING_COMMAND=failing_command,
                FAILING_ARCH=failing_arch,
                DIGEST="sha256:expected",
                GITHUB_OUTPUT=str(root / "outputs"),
                IMAGE_TAGS="ghcr.io/example/image:sha-123\nghcr.io/example/image:dev" if tags is None else tags,
            )
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/ci" / name), *arguments],
                env=env, capture_output=True, text=True, check=False,
            )
            commands = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            outputs = (root / "outputs").read_text() if (root / "outputs").exists() else ""
            return result, commands, outputs

    def test_each_selected_platform_is_scanned_with_the_release_policy(self):
        result, commands, _ = self.run_script(
            "scan-image-archive.sh", ["/tmp/built-image.tar", "linux/amd64,linux/arm64"]
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["skopeo", "trivy", "skopeo", "trivy"], [c[0] for c in commands])
        for offset, arch in ((0, "amd64"), (2, "arm64")):
            select, scan = commands[offset:offset + 2]
            self.assertEqual(arch, select[select.index("--override-arch") + 1])
            self.assertIn("--preserve-digests", select)
            self.assertIn("oci-archive:/tmp/built-image.tar", select)
            self.assertEqual(select[-1].removeprefix("oci:"), scan[scan.index("--input") + 1])
            self.assertEqual("1", scan[scan.index("--exit-code") + 1])
            self.assertEqual("CRITICAL,HIGH", scan[scan.index("--severity") + 1])
            self.assertEqual("os,library", scan[scan.index("--vuln-type") + 1])
            self.assertIn("--ignore-unfixed", scan)

    def test_scan_failure_stops_instead_of_reaching_the_next_platform(self):
        for failing_command, arch, expected_commands in (
            ("skopeo", "amd64", 1), ("trivy", "amd64", 2), ("trivy", "arm64", 4),
        ):
            with self.subTest(command=failing_command, arch=arch):
                result, commands, _ = self.run_script(
                    "scan-image-archive.sh", ["/tmp/built-image.tar", "linux/amd64,linux/arm64"],
                    failing_command, arch,
                )
                self.assertEqual(23, result.returncode, result.stderr)
                self.assertEqual(expected_commands, len(commands))

    def test_unsupported_or_empty_platforms_fail_before_any_scan(self):
        for platforms in ("", "linux/amd64,", "linux/ppc64le", "linux/amd64,linux/ppc64le"):
            with self.subTest(platforms=platforms):
                result, commands, _ = self.run_script("scan-image-archive.sh", ["image.tar", platforms])
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], commands)

    def test_publish_copies_the_original_archive_without_rebuilding(self):
        result, commands, outputs = self.run_script(
            "publish-image-archive.sh", ["/tmp/built-image.tar", "sha256:expected"]
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(commands))
        for command in commands:
            self.assertEqual("skopeo", command[0])
            self.assertIn("--all", command)
            self.assertIn("--preserve-digests", command)
            self.assertIn("oci-archive:/tmp/built-image.tar", command)
        self.assertEqual("digest=sha256:expected\n", outputs)

    def test_copy_failure_or_digest_mismatch_does_not_report_a_release_digest(self):
        for digest, failure in (("sha256:expected", "skopeo"), ("sha256:other", "")):
            result, _, outputs = self.run_script(
                "publish-image-archive.sh", ["built.tar", digest], failure,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", outputs)

    def test_empty_tag_list_cannot_report_a_release_digest(self):
        result, commands, outputs = self.run_script(
            "publish-image-archive.sh", ["built.tar", "sha256:expected"], tags="\n\n",
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], commands)
        self.assertEqual("", outputs)

    def test_shared_action_builds_once_scans_then_logs_in_and_publishes(self):
        action = document(ACTION)
        steps = action["runs"]["steps"]
        build = next(s for s in steps if s.get("id") == "build")
        scan = next(s for s in steps if "scan-image-archive.sh" in s.get("run", ""))
        login = next(s for s in steps if s.get("uses", "").startswith("docker/login-action@"))
        publish = next(s for s in steps if "publish-image-archive.sh" in s.get("run", ""))
        self.assertEqual(1, sum(s.get("uses", "").startswith("docker/build-push-action@") for s in steps))
        self.assertFalse(build["with"]["push"])
        self.assertIn("type=oci,dest=", build["with"]["outputs"])
        self.assertNotIn("tags", build["with"], "Multiple OCI ref names make archive selection ambiguous")
        self.assertEqual("mode=max", build["with"]["provenance"])
        self.assertTrue(build["with"]["sbom"])
        self.assertLess(steps.index(build), steps.index(scan))
        self.assertLess(steps.index(scan), steps.index(login))
        self.assertLess(steps.index(login), steps.index(publish))
        self.assertNotIn("if", scan)
        self.assertNotIn("continue-on-error", scan)
        for step in (login, publish):
            self.assertEqual("${{ inputs.push_to_ghcr == 'true' }}", step["if"])
        self.assertEqual(scan["env"]["IMAGE_ARCHIVE"], publish["env"]["IMAGE_ARCHIVE"])
        self.assertEqual("${{ steps.build.outputs.digest }}", publish["env"]["EXPECTED_DIGEST"])
        self.assertEqual("${{ steps.publish.outputs.digest }}", action["outputs"]["digest"]["value"])

    def test_every_image_workflow_uses_the_gate_and_release_side_effects_follow_it(self):
        for name in ("ci-main.yml", "ci-feature-branch.yml", "ci-pull-request.yml", "release-image.yml"):
            with self.subTest(workflow=name):
                workflow = document(ROOT / ".github/workflows" / name)
                jobs = workflow["jobs"]
                image_jobs = [j for j in jobs.values() if any(
                    s.get("uses") == "./.github/actions/docker-build-push" for s in j.get("steps", [])
                )]
                self.assertEqual(1, len(image_jobs))
                steps = image_jobs[0]["steps"]
                gate = next(s for s in steps if s.get("uses") == "./.github/actions/docker-build-push")
                publishing = name in ("ci-main.yml", "release-image.yml")
                self.assertEqual(publishing, gate["with"]["push_to_ghcr"])
                self.assertNotIn("continue-on-error", gate)
                if not publishing:
                    self.assertNotIn("github_token", gate["with"])
                    permissions = image_jobs[0].get("permissions", workflow.get("permissions", {}))
                    self.assertEqual({"contents": "read"}, permissions)
                    checkout = next(s for s in steps if s.get("uses", "").startswith("actions/checkout@"))
                    self.assertIs(False, checkout.get("with", {}).get("persist-credentials"))
                for step in steps:
                    if "attest@" in step.get("uses", "") or "git push" in step.get("run", ""):
                        self.assertGreater(steps.index(step), steps.index(gate))
                        self.assertNotIn("always()", step.get("if", ""))
                self.assertFalse(any(s.get("uses", "").startswith("docker/build-push-action@") for s in steps))

    def test_ci_contracts_install_and_run_in_one_disposable_virtual_environment(self):
        action = document(ROOT / ".github/actions/maven-build/action.yml")
        step = next(s for s in action["runs"]["steps"] if s["name"] == "Verify CI and load-test contracts")
        for test_exit in (0, 23):
            with self.subTest(test_exit=test_exit), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                log = root / "commands.jsonl"
                executable = root / "python3"
                executable.write_text(
                    f"#!{sys.executable}\n"
                    "import json,os,pathlib,sys\n"
                    "args=sys.argv[1:]\n"
                    "with open(os.environ['COMMAND_LOG'],'a') as out:\n"
                    " out.write(json.dumps([sys.argv[0]]+args)+'\\n')\n"
                    "if args[:2]==['-m','venv']:\n"
                    " destination=pathlib.Path(args[2]); (destination/'bin').mkdir()\n"
                    " (destination/'bin'/'python').symlink_to(sys.argv[0])\n"
                    "elif pathlib.Path(sys.argv[0]).name=='python3': sys.exit(42)\n"
                    "elif args[:2]==['-m','unittest']: sys.exit(int(os.environ['TEST_EXIT']))\n"
                )
                executable.chmod(0o755)
                env = {**os.environ, "PATH": f"{root}:{os.environ['PATH']}",
                       "RUNNER_TEMP": temporary, "COMMAND_LOG": str(log), "TEST_EXIT": str(test_exit)}
                result = subprocess.run(["bash", "-eo", "pipefail", "-c", step["run"]],
                                        env=env, capture_output=True, text=True)
                self.assertEqual(test_exit, result.returncode, result.stderr)
                commands = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertEqual(["-m", "venv"], commands[0][1:3])
                venv = Path(commands[0][3])
                self.assertEqual(str(venv / "bin/python"), commands[1][0])
                self.assertEqual(commands[1][0], commands[2][0])
                self.assertEqual(["-m", "pip", "install", "--disable-pip-version-check", "PyYAML==6.0.3"], commands[1][1:])
                self.assertEqual(["-m", "unittest", "discover", "-s", "tests/ci", "-p", "test_*.py"], commands[2][1:])
                self.assertFalse(venv.exists(), "Clean up the environment even when a contract fails")


if __name__ == "__main__":
    unittest.main()
