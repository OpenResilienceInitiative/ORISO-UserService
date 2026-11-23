"""The authenticated replica proof uses an enrolled human identity, not a guard bypass."""
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AuthenticatedLifecycleFixtureTest(unittest.TestCase):
    def test_signed_subject_has_a_creation_policy_before_authenticated_requests(self):
        spec = importlib.util.spec_from_file_location("jwk_stub", ROOT / "tests/load/jwt_jwk_stub.py")
        stub = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(stub)
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "key.pem"
            stub.generate_key(key)
            payload = stub.sign_jwt(key).split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        sql = (ROOT / "tests/load/authenticated_identity.sql").read_text()
        self.assertIn("'" + claims["sub"] + "'", sql)
        self.assertIn("INTERVAL 24 MONTH", sql)
        self.assertIn("'ACTIVE'", sql)
        self.assertIn("24,0", sql)
        runner = (ROOT / "scripts/load/run-authenticated-write-replicas.sh").read_text()
        ready = runner.index('wait_for_replica "${replica_one_port}"')
        seed = runner.index('tests/load/authenticated_identity.sql')
        first_request = runner.index('tests/load/authenticated_tutorial_replica_load.py')
        self.assertLess(ready, seed)
        self.assertLess(seed, first_request)


if __name__ == "__main__":
    unittest.main()
