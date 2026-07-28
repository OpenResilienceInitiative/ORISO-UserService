from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
ALLOCATOR = ROOT / "scripts/load/allocate-distinct-ports.py"


class LoadPortAllocationTest(unittest.TestCase):
    def test_allocator_returns_three_distinct_ports(self):
        result = subprocess.run(
            [sys.executable, str(ALLOCATOR), "3"],
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        ports = [int(value) for value in result.stdout.split()]
        self.assertEqual(3, len(ports))
        self.assertEqual(3, len(set(ports)))
        self.assertTrue(all(0 < port < 65536 for port in ports))


if __name__ == "__main__":
    unittest.main()
