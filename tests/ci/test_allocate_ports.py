from pathlib import Path
import socket
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
ALLOCATOR = ROOT / "scripts/load/allocate-ports.py"
REPLICA_RUNNER = ROOT / "scripts/load/run-authenticated-write-replicas.sh"
# Docker publishes random host ports (127.0.0.1::3306) from the kernel's ephemeral range.
EPHEMERAL_RANGE_START = 32768


class AllocatePortsTest(unittest.TestCase):
    def test_allocates_distinct_bindable_ports_below_the_ephemeral_range(self):
        result = subprocess.run(
            [sys.executable, str(ALLOCATOR), "3"],
            check=True,
            capture_output=True,
            text=True,
        )

        ports = [int(port) for port in result.stdout.split()]
        self.assertEqual(3, len(ports))
        self.assertEqual(3, len(set(ports)))
        for port in ports:
            self.assertGreaterEqual(port, 1024)
            self.assertLess(port, EPHEMERAL_RANGE_START)
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", port))

    def test_replica_runner_takes_its_ports_from_the_allocator(self):
        runner = REPLICA_RUNNER.read_text()

        self.assertIn("allocate-ports.py", runner)
        self.assertNotIn('bind(("127.0.0.1", 0))', runner)


if __name__ == "__main__":
    unittest.main()
