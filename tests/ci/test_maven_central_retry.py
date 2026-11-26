import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/ci/run-maven-with-central-retry.sh"
PARENT_LOOKUP = (
    "[FATAL] Non-resolvable parent POM: Could not find artifact "
    "org.springframework.boot:spring-boot-starter-parent:pom:4.0.8 "
    "in 1_maven.apache.org (https://repo.maven.apache.org/maven2)"
)
PLUGIN_TRANSFER = (
    "[ERROR] Could not transfer artifact "
    "org.springframework.boot:spring-boot-maven-plugin:pom:4.0.8 "
    "from/to central (https://repo.maven.apache.org/maven2): status code: 429"
)


class MavenCentralRetryTest(unittest.TestCase):
    def test_only_the_affected_maven_calls_use_the_retry(self):
        calls = {
            ".github/workflows/ci-pull-request.yml": (
                " -Dtest=ConsultantActivityRegistryRedisIT,RedisOneTimeTokenStoreIT test"
            ),
        }
        for filename, arguments in calls.items():
            with self.subTest(filename=filename):
                contents = " ".join((ROOT / filename).read_text().split())
                self.assertIn(f"bash scripts/ci/{RUNNER.name} -B{arguments}", contents)
        action = (ROOT / ".github/actions/maven-build/action.yml").read_text()
        verifier = (ROOT / "scripts/ci/verify-maven-unit.py").read_text()
        self.assertIn("python3 scripts/ci/verify-maven-unit.py", action)
        self.assertIn(f'["bash", str(CI / "{RUNNER.name}"), "-B"]', verifier)

    def test_maven_caches_are_keyed_by_repository_origin(self):
        default_paths = [
            "**/pom.xml",
            "**/.mvn/wrapper/maven-wrapper.properties",
            "**/.mvn/extensions.xml",
        ]
        direct = [*default_paths, "scripts/ci/run-maven-with-central-retry.sh"]
        mirror = [*default_paths, "scripts/ci/github-maven-settings.xml"]
        expected = {
            (".github/actions/maven-build/action.yml", "composite"): direct,
            (".github/actions/maven-verify-required/action.yml", "composite"): mirror,
            (".github/workflows/ci-pull-request.yml", "redis-contract"): direct,
            (".github/workflows/ci-pull-request.yml", "tenant-filter-on-integration-tests"): mirror,
            (".github/workflows/mariadb-contract.yml", "verify"): direct,
            (".github/workflows/notification-smtp-contract.yml", "verify"): mirror,
        }
        cache_paths = {}
        github = ROOT / ".github"
        documents = [*github.glob("actions/*/action.y*ml"), *github.glob("workflows/*.y*ml")]
        for path in documents:
            document = yaml.safe_load(path.read_text())
            jobs = document.get("jobs", {"composite": document.get("runs", {})})
            for job_name, job in jobs.items():
                for step in job.get("steps", []):
                    inputs = step.get("with", {})
                    if not step.get("uses", "").startswith("actions/setup-java@") or inputs.get("cache") != "maven":
                        continue
                    location = (path.relative_to(ROOT).as_posix(), job_name)
                    self.assertNotIn(location, cache_paths)
                    cache_paths[location] = inputs.get("cache-dependency-path", "").splitlines()
        self.assertEqual(expected, cache_paths)

    def run_maven(self, *results):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            arguments = root / "arguments"
            wrapper = root / "mvnw"
            wrapper.write_text(
                """#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
path = Path(os.environ["MAVEN_ARGUMENTS"])
previous = path.read_text().splitlines() if path.exists() else []
with path.open("a") as recorded:
    print(json.dumps(sys.argv[1:]), file=recorded)
message, status = json.loads(os.environ["MAVEN_RESULTS"])[len(previous)]
print(message, file=sys.stderr)
sys.exit(status)
"""
            )
            wrapper.chmod(0o755)
            sleep = root / "sleep"
            sleep.write_text('#!/usr/bin/env bash\n[[ "$*" == 15 ]]\n')
            sleep.chmod(0o755)
            result = subprocess.run(
                ["bash", str(RUNNER), "-B", "-Dtest=SampleTest", "test"],
                cwd=root,
                env={
                    **os.environ,
                    "PATH": f"{root}{os.pathsep}{os.environ['PATH']}",
                    "MAVEN_ARGUMENTS": str(arguments),
                    "MAVEN_RESULTS": json.dumps(results),
                },
                check=False,
                capture_output=True,
                text=True,
            )
            return result, [json.loads(line) for line in arguments.read_text().splitlines()]

    def test_recognised_central_errors_retry_once_and_refresh_resolution(self):
        for error in (PARENT_LOOKUP, PLUGIN_TRANSFER):
            with self.subTest(error=error):
                result, attempts = self.run_maven((error, 1), ("fresh Maven run", 0))
                self.assertEqual(0, result.returncode)
                self.assertEqual(
                    [
                        ["-B", "-Dtest=SampleTest", "test"],
                        ["-U", "-B", "-Dtest=SampleTest", "test"],
                    ],
                    attempts,
                )
                self.assertIn(error, result.stdout)
                self.assertIn("fresh Maven run", result.stdout)

    def test_a_second_failure_keeps_its_exit_status(self):
        result, attempts = self.run_maven((PARENT_LOOKUP, 1), (PLUGIN_TRANSFER, 23))
        self.assertEqual(23, result.returncode)
        self.assertEqual(2, len(attempts))

    def test_success_and_unrelated_failures_run_once(self):
        for message, status in (
            ("[INFO] BUILD SUCCESS", 0),
            (PARENT_LOOKUP, 130),
            ("[ERROR] COMPILATION ERROR", 17),
            ("[ERROR] Tests run: 2, Failures: 1", 18),
            (PLUGIN_TRANSFER.replace("429", "401"), 1),
            (PLUGIN_TRANSFER.replace("repo.maven.apache.org", "example.invalid"), 1),
            (PARENT_LOOKUP.replace("spring-boot-starter-parent", "another-parent"), 1),
            ("[INFO] --- surefire:3.5.4:test (default-test) ---\n" + PLUGIN_TRANSFER, 1),
            ("[INFO] Running sample.SampleTest\n" + PLUGIN_TRANSFER, 1),
            ("[ERROR] Tests run: 2, Failures: 1\n" + PLUGIN_TRANSFER, 1),
            ("[ERROR] COMPILATION ERROR\n" + PLUGIN_TRANSFER, 1),
        ):
            with self.subTest(message=message):
                result, attempts = self.run_maven((message, status))
                self.assertEqual(status, result.returncode)
                self.assertEqual([["-B", "-Dtest=SampleTest", "test"]], attempts)
