from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/ci/check-no-test-quarantine.py"


class NoTestQuarantineContractTest(unittest.TestCase):
    def run_checker(self, sources: dict[str, str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_root = Path(temp_dir)
            for relative_path, content in sources.items():
                source = source_root / relative_path
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text(content)

            return subprocess.run(
                [sys.executable, CHECKER, "--root", source_root],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_rejects_junit_quarantine_annotations(self):
        result = self.run_checker(
            {
                "DisabledTest.java": """
                    class DisabledTest {
                      @Disabled("flaky")
                      void quarantined() {}
                    }
                """,
                "nested/IgnoredTest.java": """
                    class IgnoredTest {
                      @Ignore
                      void quarantined() {}
                    }
                """,
                "FullyQualifiedDisabledTest.java": """
                    class FullyQualifiedDisabledTest {
                      @org.junit.jupiter.api.Disabled("flaky")
                      void quarantined() {}
                    }
                """,
                "SameLineDisabledTest.java": """
                    class SameLineDisabledTest {
                      @Test @Disabled void quarantined() {}
                    }
                """,
                "EscapedDisabledTest.java": r"""
                    class EscapedDisabledTest {
                      @\u0044isabled void quarantined() {}
                    }
                """,
                "SeparatedDisabledTest.java": """
                    class SeparatedDisabledTest {
                      @org /* separator */ . junit . jupiter . api . Disabled
                      void quarantined() {}
                    }
                """,
            }
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("DisabledTest.java:3: @Disabled", result.stdout)
        self.assertIn(
            "FullyQualifiedDisabledTest.java:3: @org.junit.jupiter.api.Disabled",
            result.stdout,
        )
        self.assertIn("SameLineDisabledTest.java:3: @Disabled", result.stdout)
        self.assertIn("EscapedDisabledTest.java:3: @Disabled", result.stdout)
        self.assertIn(
            "SeparatedDisabledTest.java:3: @org.junit.jupiter.api.Disabled",
            result.stdout,
        )
        self.assertIn("nested/IgnoredTest.java:3: @Ignore", result.stdout)

    def test_accepts_active_tests_and_annotation_text_in_comments(self):
        result = self.run_checker(
            {
                "ActiveTest.java": """
                    class ActiveTest {
                      // Explains why @Disabled is forbidden.
                      /*
                      @Ignore and @Disabled are documentation here.
                       */
                      String policy = "@Disabled is forbidden";
                      @Test
                      void active() {}
                    }
                """,
                "EscapedDocumentation.java": r"""
                    class EscapedDocumentation {
                      // @\u0044isabled is documentation.
                      String policy = "@\u0044isabled is forbidden";
                      @Test void active() {}
                    }
                """,
            }
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_repository_contains_no_quarantined_tests(self):
        result = subprocess.run(
            [sys.executable, CHECKER],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_rejects_a_missing_test_source_root(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            missing_root = Path(temp_dir) / "missing"
            result = subprocess.run(
                [sys.executable, CHECKER, "--root", missing_root],
                check=False,
                capture_output=True,
                text=True,
            )

        self.assertEqual(2, result.returncode)
        self.assertIn(f"Test source root does not exist: {missing_root}", result.stderr)


if __name__ == "__main__":
    unittest.main()
