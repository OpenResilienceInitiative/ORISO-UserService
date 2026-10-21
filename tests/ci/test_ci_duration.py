import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import yaml


ROOT = Path(__file__).resolve().parents[2]


def document(path):
    return yaml.safe_load((ROOT / path).read_text())


class CiDurationTest(unittest.TestCase):
    def test_branch_routing_skips_only_when_an_open_pr_is_confirmed(self):
        for response, exit_code, mergeable, detail_exit_code, expected in (
            ("null", 0, "true", 0, "true"),
            ("123", 0, "true", 0, "false"),
            ("123", 0, "false", 0, "true"),
            ("123", 0, "null", 0, "true"),
            ("123", 0, "", 1, "true"),
            ("", 1, "true", 0, "true"),
            ("invalid", 0, "true", 0, "true"),
        ):
            with self.subTest(response=response, exit_code=exit_code):
                with tempfile.TemporaryDirectory() as directory:
                    temporary = Path(directory)
                    executable = temporary / "gh"
                    executable.write_text(
                        f"#!{sys.executable}\n"
                        "import json,os,pathlib,sys\n"
                        "with open(os.environ['COMMAND_LOG'], 'a') as log:\n"
                        " log.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                        "detail=sys.argv[2].endswith('/123')\n"
                        "print(os.environ['MERGEABLE' if detail else 'RESPONSE'])\n"
                        "sys.exit(int(os.environ['DETAIL_EXIT_CODE' if detail else 'EXIT_CODE']))\n"
                    )
                    executable.chmod(0o755)
                    outputs = temporary / "outputs"
                    command_log = temporary / "command.json"
                    branch = "fix/ci-duration"
                    result = subprocess.run(
                        ["bash", str(ROOT / "scripts/ci/should-run-branch-validation.sh")],
                        env={
                            **os.environ,
                            "PATH": f"{temporary}:{os.environ['PATH']}",
                            "GITHUB_REPOSITORY": "example/service",
                            "GITHUB_REPOSITORY_OWNER": "example",
                            "GITHUB_REF_NAME": branch,
                            "GITHUB_OUTPUT": str(outputs),
                            "COMMAND_LOG": str(command_log),
                            "RESPONSE": response,
                            "EXIT_CODE": str(exit_code),
                            "MERGEABLE": mergeable,
                            "DETAIL_EXIT_CODE": str(detail_exit_code),
                        },
                        capture_output=True,
                        text=True,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(f"run_validation={expected}\n", outputs.read_text())
                    commands = [json.loads(line) for line in command_log.read_text().splitlines()]
                    command = commands[0]
                    self.assertIn("repos/example/service/pulls", command)
                    self.assertIn("state=open", command)
                    self.assertIn(f"head=example:{branch}", command)
                    if response == "123" and exit_code == 0:
                        self.assertIn("repos/example/service/pulls/123", commands[1])

    def test_all_branch_checks_defer_to_the_same_pr_route(self):
        jobs = document(".github/workflows/ci-feature-branch.yml")["jobs"]
        self.assertEqual(
            {"contents": "read", "pull-requests": "read"},
            jobs["route-validation"]["permissions"],
        )
        # Every branch check, including reusable workflows, must defer to an open PR's run.
        branch_checks = [name for name in jobs if name != "route-validation"]
        self.assertIn("notification-smtp-contract", branch_checks)
        for name in branch_checks:
            self.assertEqual("route-validation", jobs[name]["needs"], name)
            self.assertEqual(
                "needs.route-validation.outputs.run_validation == 'true'", jobs[name]["if"]
            )

    def test_package_skips_the_test_executions_configured_by_the_pom(self):
        steps = document(".github/actions/maven-build/action.yml")["runs"]["steps"]
        package = next(step for step in steps if step["name"] == "Package Java application")
        pom = ET.parse(ROOT / "pom.xml")
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        executions = pom.findall(
            ".//m:plugin[m:artifactId='maven-surefire-plugin']/m:executions/m:execution",
            namespace,
        )
        for execution in executions:
            name = execution.findtext("m:id", namespaces=namespace)
            if name in ("unit-tests", "integration-tests"):
                property_name = execution.findtext(
                    "m:configuration/m:skipTests", namespaces=namespace
                ).removeprefix("${").removesuffix("}")
                self.assertIn(f"-D{property_name}=true", package["run"])
        tests = next(step for step in steps if step["name"] == "Run Maven tests")
        self.assertIn("./mvnw -B test", tests["run"])
        self.assertNotIn("-Dskip", tests["run"])

    def test_parallel_build_hands_its_jar_to_a_publish_job_blocked_on_all_checks(self):
        jobs = document(".github/workflows/ci-main.yml")["jobs"]
        build, publish = jobs["build"], jobs["publish"]
        self.assertNotIn("needs", build)
        self.assertEqual({"contents": "read"}, build["permissions"])
        self.assertEqual(
            {"build", "required-integration-tests", "mariadb-contract", "notification-smtp-contract"},
            set(publish["needs"]),
        )
        self.assertNotIn("if", publish)
        upload = next(s for s in build["steps"] if s.get("uses", "").startswith("actions/upload-artifact@"))
        download = next(s for s in publish["steps"] if s.get("uses", "").startswith("actions/download-artifact@"))
        self.assertEqual(upload["with"]["name"], download["with"]["name"])
        self.assertEqual("target/UserService.jar", upload["with"]["path"])
        self.assertEqual("target", download["with"]["path"])
        self.assertEqual("error", upload["with"]["if-no-files-found"])
        self.assertNotIn("run-id", download["with"])
        self.assertFalse(any(s.get("uses") == "./.github/actions/maven-build" for s in publish["steps"]))
        image = next(s for s in publish["steps"] if s.get("uses") == "./.github/actions/docker-build-push")
        self.assertLess(publish["steps"].index(download), publish["steps"].index(image))
        self.assertIs(True, image["with"]["push_to_ghcr"])


if __name__ == "__main__":
    unittest.main()
