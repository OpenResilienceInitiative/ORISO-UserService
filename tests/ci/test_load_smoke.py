import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from threading import Thread
import time
import unittest
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
LOAD_SCRIPT = ROOT / "tests/load/user_service_load_smoke.py"
SEEDED_PUBLIC_READ_SCENARIO = ROOT / "tests/load/scenarios/seeded-public-read.json"
AGENCY_STUB_SCRIPT = ROOT / "tests/load/seeded_agency_stub.py"
SEEDED_PUBLIC_READ_RUNNER = ROOT / "scripts/load/run-seeded-public-read.sh"
SEEDED_REPLICA_RUNNER = ROOT / "scripts/load/run-seeded-public-read-replicas.sh"
SPEC = importlib.util.spec_from_file_location("user_service_load_smoke", LOAD_SCRIPT)
LOAD_SMOKE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = LOAD_SMOKE
SPEC.loader.exec_module(LOAD_SMOKE)


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        payload = b'{"status":"UP"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        pass


class SlowOperationHandler(HealthHandler):
    def do_GET(self):
        if self.path == "/slow":
            time.sleep(0.1)
        super().do_GET()


class FailingHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        payload = b'{"error":"replica failure"}'
        self.send_response(500)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        pass


class LoadSmokeContractTest(unittest.TestCase):
    def test_concurrent_smoke_reports_request_volume_payload_and_latency(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            summary, samples = LOAD_SMOKE.run_load(
                f"http://127.0.0.1:{server.server_port}/actuator/health",
                requests=32,
                concurrency=8,
                timeout_seconds=2,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(32, summary["requests"])
        self.assertEqual(0, summary["failures"])
        self.assertEqual(0, summary["error_rate"])
        self.assertGreater(summary["response_bytes"], 0)
        self.assertGreater(summary["requests_per_second"], 0)
        self.assertGreaterEqual(summary["latency_p95_ms"], 0)
        self.assertTrue(all(sample.status == 200 for sample in samples))

    def test_weighted_workload_reports_each_public_read_operation_separately(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            summary, samples = LOAD_SMOKE.run_workload(
                f"http://127.0.0.1:{server.server_port}",
                request_specs=[
                    LOAD_SMOKE.RequestSpec(
                        name="consultant-profile",
                        path="/users/consultants/473f7c4b-f011-4fc2-847c-ceb636a5b399",
                        weight=3,
                    ),
                    LOAD_SMOKE.RequestSpec(
                        name="agency-languages",
                        path="/users/consultants/languages?agencyId=1",
                        weight=1,
                    ),
                ],
                requests=40,
                concurrency=8,
                timeout_seconds=2,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(40, summary["requests"])
        self.assertEqual(30, summary["operations"]["consultant-profile"]["requests"])
        self.assertEqual(10, summary["operations"]["agency-languages"]["requests"])
        self.assertEqual(0, summary["operations"]["consultant-profile"]["failures"])
        self.assertEqual(0, summary["operations"]["agency-languages"]["failures"])
        self.assertEqual(
            {"consultant-profile", "agency-languages"},
            {sample.operation for sample in samples},
        )

    def test_weighted_workload_distributes_requests_across_two_replicas(self):
        first = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        second = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        first_thread = Thread(target=first.serve_forever, daemon=True)
        second_thread = Thread(target=second.serve_forever, daemon=True)
        first_thread.start()
        second_thread.start()
        first_url = f"http://127.0.0.1:{first.server_port}"
        second_url = f"http://127.0.0.1:{second.server_port}"
        try:
            summary, samples = LOAD_SMOKE.run_workload(
                [first_url, second_url],
                request_specs=[
                    LOAD_SMOKE.RequestSpec(
                        name="liveness",
                        path="/actuator/health/liveness",
                    )
                ],
                requests=40,
                concurrency=8,
                timeout_seconds=2,
            )
        finally:
            first.shutdown()
            second.shutdown()
            first.server_close()
            second.server_close()
            first_thread.join(timeout=2)
            second_thread.join(timeout=2)

        self.assertEqual(20, summary["targets"][first_url]["requests"])
        self.assertEqual(20, summary["targets"][second_url]["requests"])
        self.assertEqual({first_url, second_url}, {sample.target for sample in samples})

    def test_weighted_workload_requires_enough_requests_to_reach_every_target(self):
        with self.assertRaisesRegex(ValueError, "requests must reach every target"):
            LOAD_SMOKE.run_workload(
                ["http://127.0.0.1:1", "http://127.0.0.1:2"],
                request_specs=[
                    LOAD_SMOKE.RequestSpec(name="liveness", path="/health")
                ],
                requests=1,
                concurrency=1,
                timeout_seconds=1,
            )

    def test_weighted_workload_requires_one_complete_weight_cycle(self):
        with self.assertRaisesRegex(
            ValueError, "requests must cover at least one complete weight cycle"
        ):
            LOAD_SMOKE.run_workload(
                "http://127.0.0.1:1",
                request_specs=[
                    LOAD_SMOKE.RequestSpec(name="first", path="/first", weight=3),
                    LOAD_SMOKE.RequestSpec(name="second", path="/second", weight=1),
                ],
                requests=3,
                concurrency=1,
                timeout_seconds=1,
            )

    def test_cli_runs_a_json_scenario_and_emits_per_operation_results(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", encoding="utf-8"
            ) as scenario:
                json.dump(
                    {
                        "requests": [
                            {
                                "name": "consultant-profile",
                                "path": "/users/consultants/473f7c4b-f011-4fc2-847c-ceb636a5b399",
                                "weight": 3,
                            },
                            {
                                "name": "agency-languages",
                                "path": "/users/consultants/languages?agencyId=1",
                                "weight": 1,
                            },
                        ]
                    },
                    scenario,
                )
                scenario.flush()
                result = subprocess.run(
                    [
                        sys.executable,
                        str(LOAD_SCRIPT),
                        "--base-url",
                        f"http://127.0.0.1:{server.server_port}",
                        "--scenario",
                        scenario.name,
                        "--requests",
                        "20",
                        "--concurrency",
                        "4",
                    ],
                    capture_output=True,
                    check=False,
                    text=True,
                    timeout=10,
                )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual(
            15, output["summary"]["operations"]["consultant-profile"]["requests"]
        )
        self.assertEqual(
            5, output["summary"]["operations"]["agency-languages"]["requests"]
        )
        self.assertEqual(
            f"http://127.0.0.1:{server.server_port}",
            output["target"],
        )

    def test_cli_accepts_a_second_replica_and_reports_both_targets(self):
        first = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        second = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        first_thread = Thread(target=first.serve_forever, daemon=True)
        second_thread = Thread(target=second.serve_forever, daemon=True)
        first_thread.start()
        second_thread.start()
        first_url = f"http://127.0.0.1:{first.server_port}"
        second_url = f"http://127.0.0.1:{second.server_port}"
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", encoding="utf-8"
            ) as scenario:
                json.dump(
                    {"requests": [{"name": "liveness", "path": "/health"}]},
                    scenario,
                )
                scenario.flush()
                result = subprocess.run(
                    [
                        sys.executable,
                        str(LOAD_SCRIPT),
                        "--base-url",
                        first_url,
                        "--replica-url",
                        second_url,
                        "--scenario",
                        scenario.name,
                        "--requests",
                        "20",
                        "--concurrency",
                        "4",
                    ],
                    capture_output=True,
                    check=False,
                    text=True,
                    timeout=10,
                )
        finally:
            first.shutdown()
            second.shutdown()
            first.server_close()
            second.server_close()
            first_thread.join(timeout=2)
            second_thread.join(timeout=2)

        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual(10, output["summary"]["targets"][first_url]["requests"])
        self.assertEqual(10, output["summary"]["targets"][second_url]["requests"])

    def test_cli_fails_when_one_replica_exceeds_its_error_rate(self):
        healthy = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        failing = ThreadingHTTPServer(("127.0.0.1", 0), FailingHandler)
        healthy_thread = Thread(target=healthy.serve_forever, daemon=True)
        failing_thread = Thread(target=failing.serve_forever, daemon=True)
        healthy_thread.start()
        failing_thread.start()
        healthy_url = f"http://127.0.0.1:{healthy.server_port}"
        failing_url = f"http://127.0.0.1:{failing.server_port}"
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", encoding="utf-8"
            ) as scenario:
                json.dump(
                    {"requests": [{"name": "liveness", "path": "/health"}]},
                    scenario,
                )
                scenario.flush()
                result = subprocess.run(
                    [
                        sys.executable,
                        str(LOAD_SCRIPT),
                        "--base-url",
                        healthy_url,
                        "--replica-url",
                        failing_url,
                        "--scenario",
                        scenario.name,
                        "--requests",
                        "20",
                        "--concurrency",
                        "4",
                        "--max-error-rate",
                        "0.6",
                    ],
                    capture_output=True,
                    check=False,
                    text=True,
                    timeout=10,
                )
        finally:
            healthy.shutdown()
            failing.shutdown()
            healthy.server_close()
            failing.server_close()
            healthy_thread.join(timeout=2)
            failing_thread.join(timeout=2)

        output = json.loads(result.stdout)
        self.assertEqual(0.5, output["summary"]["error_rate"])
        self.assertEqual(
            1.0,
            output["summary"]["targets"][failing_url]["error_rate"],
        )
        self.assertEqual(1, result.returncode)

    def test_scenario_fails_when_a_low_weight_operation_exceeds_its_p95_limit(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowOperationHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", encoding="utf-8"
            ) as scenario:
                json.dump(
                    {
                        "requests": [
                            {"name": "fast-read", "path": "/fast", "weight": 19},
                            {"name": "slow-read", "path": "/slow", "weight": 1},
                        ]
                    },
                    scenario,
                )
                scenario.flush()
                result = subprocess.run(
                    [
                        sys.executable,
                        str(LOAD_SCRIPT),
                        "--base-url",
                        f"http://127.0.0.1:{server.server_port}",
                        "--scenario",
                        scenario.name,
                        "--requests",
                        "20",
                        "--concurrency",
                        "4",
                        "--max-p95-ms",
                        "50",
                    ],
                    capture_output=True,
                    check=False,
                    text=True,
                    timeout=10,
                )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        output = json.loads(result.stdout)
        self.assertEqual(20, output["summary"]["requests"])
        self.assertEqual(19, output["summary"]["operations"]["fast-read"]["requests"])
        self.assertEqual(1, output["summary"]["operations"]["slow-read"]["requests"])
        self.assertGreater(
            output["summary"]["operations"]["slow-read"]["latency_p95_ms"], 50
        )
        self.assertEqual(1, result.returncode)

    def test_repository_scenario_covers_seeded_profiles_relations_and_liveness(self):
        specs = LOAD_SMOKE.load_request_specs(str(SEEDED_PUBLIC_READ_SCENARIO))

        self.assertEqual(6, len(specs))
        self.assertEqual(14, sum(spec.weight for spec in specs))
        self.assertEqual(
            {
                "consultant-profile-addiction",
                "consultant-profile-peer",
                "consultant-profile-parenting-team",
                "agency-languages-primary",
                "agency-languages-multi",
                "liveness-control",
            },
            {spec.name for spec in specs},
        )
        self.assertTrue(
            all(
                spec.path.startswith("/users/") or spec.path.startswith("/actuator/")
                for spec in specs
            )
        )

    def test_seeded_agency_stub_implements_the_generated_batch_read_contract(self):
        spec = importlib.util.spec_from_file_location(
            "seeded_agency_stub", AGENCY_STUB_SCRIPT
        )
        stub = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        sys.modules[spec.name] = stub
        spec.loader.exec_module(stub)

        server = stub.create_server("127.0.0.1", 0)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with urlopen(
                f"http://127.0.0.1:{server.server_port}/agencies/1,121",
                timeout=2,
            ) as response:
                payload = json.load(response)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual([1, 121], [agency["id"] for agency in payload])
        self.assertTrue(all(agency["offline"] is False for agency in payload))

    def test_seeded_runner_wires_seed_dependency_and_mixed_scenario(self):
        result = subprocess.run(
            ["bash", "-n", str(SEEDED_PUBLIC_READ_RUNNER)],
            capture_output=True,
            check=False,
            text=True,
        )
        runner = SEEDED_PUBLIC_READ_RUNNER.read_text(encoding="utf-8")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("UserServiceDatabase.sql", runner)
        self.assertIn("seeded_agency_stub.py", runner)
        self.assertIn("seeded-public-read.json", runner)
        self.assertIn("spring-boot.run.useTestClasspath=true", runner)
        self.assertIn("--max-error-rate", runner)
        self.assertIn("--max-p95-ms", runner)
        self.assertIn("trap cleanup EXIT", runner)
        self.assertIn("trap 'exit 130' INT", runner)
        self.assertIn("trap 'exit 143' TERM", runner)

    def test_replica_runner_uses_shared_mariadb_redis_and_two_processes(self):
        result = subprocess.run(
            ["bash", "-n", str(SEEDED_REPLICA_RUNNER)],
            capture_output=True,
            check=False,
            text=True,
        )
        runner = SEEDED_REPLICA_RUNNER.read_text(encoding="utf-8")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("mariadb:11.0.6", runner)
        self.assertIn("redis:7-alpine", runner)
        self.assertIn("jdbc:mariadb://127.0.0.1:", runner)
        self.assertNotIn("jdbc:h2:mem:", runner)
        self.assertIn("userservice_replica_one_pid", runner)
        self.assertIn("userservice_replica_two_pid", runner)
        self.assertIn("target/UserService.jar", runner)
        self.assertIn("--replica-url", runner)
        self.assertIn("UserServiceDatabase.sql", runner)
        self.assertIn("SPRING_LIQUIBASE_ENABLED=true", runner)
        self.assertIn("SPRING_TASK_SCHEDULING_ENABLED=false", runner)
        self.assertIn('docker rm -f "${mariadb_container}"', runner)
        self.assertIn('docker rm -f "${redis_container}"', runner)
        self.assertIn("trap cleanup EXIT", runner)


if __name__ == "__main__":
    unittest.main()
