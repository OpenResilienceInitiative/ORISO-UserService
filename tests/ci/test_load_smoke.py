import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
from threading import Thread
import unittest


ROOT = Path(__file__).resolve().parents[2]
LOAD_SCRIPT = ROOT / "tests/load/user_service_load_smoke.py"
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


if __name__ == "__main__":
    unittest.main()
