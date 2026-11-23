import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class OpenApiContractGateTest(unittest.TestCase):
    def test_user_list_preference_references_resolve_to_schemas(self):
        provider = yaml.safe_load((ROOT / "api/useradminservice.yaml").read_text())
        read = provider["paths"]["/useradmin/list-preferences"]["get"]
        responses = {str(code): value for code, value in read["responses"].items()}
        write = provider["paths"]["/useradmin/list-preferences/sorts/{tab}"]["put"]
        contracts = [responses["200"], write["requestBody"]]

        for contract in contracts:
            reference = contract["content"]["application/json"]["schema"]["$ref"]
            self.assertTrue(reference.startswith("#/components/schemas/"))
            model = reference.rsplit("/", 1)[-1]
            self.assertTrue(
                model in provider["components"]["schemas"],
                f"{model} must be a schema, so the API client and server can generate it",
            )

    def test_consumer_gate_propagates_oasdiff_failure(self):
        gate = ROOT / "scripts/contracts/verify-consumer-contract.sh"
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            provider_root = temp / "provider"
            provider_root.mkdir()
            (provider_root / "provider.yaml").write_text("openapi: 3.0.3\n")
            consumer = temp / "consumer.yaml"
            consumer.write_text("openapi: 3.0.3\n")

            fake_redocly = temp / "redocly"
            fake_redocly.write_text("#!/usr/bin/env bash\n" 'cp "$2" "$4"\n')
            fake_redocly.chmod(0o755)

            fake_oasdiff = temp / "oasdiff"
            fake_oasdiff.write_text("#!/usr/bin/env bash\nexit 23\n")
            fake_oasdiff.chmod(0o755)

            env = os.environ.copy()
            env["REDOCLY_BIN"] = str(fake_redocly)
            env["REDOCLY_VERSION"] = "test"
            env["OASDIFF_BIN"] = str(fake_oasdiff)
            result = subprocess.run(
                [gate, consumer, provider_root, "provider.yaml"],
                cwd=ROOT,
                env=env,
                check=False,
            )

        self.assertEqual(23, result.returncode)

    def test_workflow_pins_tools_and_publishes_provider_artifact(self):
        workflow = (ROOT / ".github/workflows/openapi-contracts.yml").read_text()
        self.assertIn("REDOCLY_VERSION: 2.40.0", workflow)
        self.assertIn("OASDIFF_VERSION: v1.17.0", workflow)
        self.assertIn("name: openapi provider and consumer contracts", workflow)
        self.assertIn("actions/upload-artifact@v4", workflow)
        self.assertIn("provider-contracts-${{ github.sha }}", workflow)
        self.assertNotIn("continue-on-error:", workflow)
        self.assertIn("permissions:\n  contents: read", workflow)
        self.assertEqual(
            workflow.count("uses: actions/checkout@v6"),
            workflow.count("persist-credentials: false"),
        )

    def test_provider_specs_are_linted_before_they_are_bundled(self):
        publisher = (
            ROOT / "scripts/contracts/publish-provider-contracts.sh"
        ).read_text()

        lint = publisher.index(
            'run_redocly lint --extends minimal "${relative_spec}"'
        )
        bundle = publisher.index('run_redocly bundle "${relative_spec}"')
        self.assertLess(lint, bundle)

    def test_provider_compatibility_flattens_equivalent_allof_schemas(self):
        gate = (
            ROOT / "scripts/contracts/verify-provider-source-compatibility.sh"
        ).read_text()

        self.assertIn('"${oasdiff_bin}" breaking "${base_spec}" "${head_spec}"', gate)
        self.assertIn("--flatten-allof", gate)

    def test_compatibility_views_are_self_contained_and_valid(self):
        provider = yaml.safe_load((ROOT / "api/useradminservice.yaml").read_text())
        agency_admin = yaml.safe_load(
            (ROOT / "services/agencyadminservice.yaml").read_text()
        )
        topic = yaml.safe_load((ROOT / "services/topicservice.yaml").read_text())

        full_response = provider["components"]["schemas"][
            "AgencyAdminFullResponseDTO"
        ]
        self.assertEqual(
            "#/components/schemas/AgencyLinks",
            full_response["properties"]["_links"]["$ref"],
        )
        # AgencyLinks is deliberately flattened (no allOf): oasdiff does not merge
        # allOf, so `self` and friends must be direct properties for the consumer
        # contracts in AgencyService (#232) and TenantService (#162) to hold.
        agency_links = provider["components"]["schemas"]["AgencyLinks"]
        self.assertNotIn("allOf", agency_links)
        self.assertEqual("object", agency_links["type"])
        self.assertIn("self", agency_links["required"])
        for link in ("self", "update", "delete", "postcodeRanges"):
            self.assertEqual(
                "#/components/schemas/HalLink",
                agency_links["properties"][link]["$ref"],
            )

        sort_field = agency_admin["components"]["schemas"]["Sort"]["properties"][
            "field"
        ]
        self.assertIn("postCode", sort_field["example"].split("|"))
        self.assertNotIn("postcode", sort_field["example"].split("|"))

        topic_schemas = topic["components"]["schemas"]
        self.assertNotIn("format", topic_schemas["WelcomeMessage"])
        self.assertNotIn("format", topic_schemas["FallBackUrl"])

    def test_all_backend_consumer_contracts_are_checked(self):
        workflow = (ROOT / ".github/workflows/openapi-contracts.yml").read_text()
        expected = {
            "services/agencyadminservice.yaml": ".providers/agency",
            "services/agencyservice.yaml": ".providers/agency",
            "services/applicationsettingsservice.yaml": ".providers/consulting",
            "services/consultingtypeservice.yaml": ".providers/consulting",
            "services/topicservice.yaml": ".providers/consulting",
            "services/tenantadminservice.yaml": ".providers/tenant",
            "services/tenantservice.yaml": ".providers/tenant",
        }
        for contract, checkout in expected.items():
            self.assertIn(contract, workflow)
            self.assertIn(checkout, workflow)

    def test_pull_request_uses_coordinated_provider_commits(self):
        workflow = (ROOT / ".github/workflows/openapi-contracts.yml").read_text()
        self.assertRegex(
            workflow,
            re.compile(
                r"repository: OpenResilienceInitiative/ORISO-AgencyService.*"
                r"c50ca29c8c63f600e6af350db5ff693a7fed3503",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            workflow,
            re.compile(
                r"repository: OpenResilienceInitiative/ORISO-ConsultingTypeService.*"
                r"25e7068e6c587b12fab3aa02633749023c3ed071",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            workflow,
            re.compile(
                r"repository: OpenResilienceInitiative/ORISO-TenantService.*"
                # TenantService #405 includes dev (proof-bound release, assignment confirmation)
                # and featureTeamDiscussionAfterAcceptEnabled.
                r"bd15fe30ced95362a3eeaeae1303831588d07c98",
                re.DOTALL,
            ),
        )
        self.assertIn("|| 'dev'", workflow)
        self.assertIn("    - dev", workflow)
        self.assertNotIn("pre-dev", workflow)

    def test_contract_gate_tests_are_executed_by_ci(self):
        # A gate assertion that never runs protects nothing. Without a job that
        # invokes pytest, this file can drift away from the workflow and the
        # scripts it describes while every check stays green.
        workflow = (ROOT / ".github/workflows/openapi-contracts.yml").read_text()

        self.assertIn("contract-gate-tests:", workflow)
        self.assertIn("python -m pytest -q tests/contracts", workflow)

    def test_case_handover_contract_does_not_publish_a_standalone_team_access_feature(self):
        provider = yaml.safe_load((ROOT / "api/userservice.yaml").read_text())

        self.assertNotIn(
            "/users/sessions/{sessionId}/team-access", provider["paths"]
        )
        self.assertNotIn("TeamAccessDTO", provider["components"]["schemas"])
        self.assertNotIn(
            "teamAccessAllowed",
            provider["components"]["schemas"]["SessionDTO"]["properties"],
        )

    def _run_provider_gate(self, temp, retired_text, accepted_text):
        base_api = temp / "base" / "api"
        base_api.mkdir(parents=True)
        (base_api / "kept.yaml").write_text("openapi: 3.0.3\n")
        (base_api / "gone.yaml").write_text("openapi: 3.0.3\n")
        head = temp / "head"
        head.mkdir()
        (head / "kept.yaml").write_text("openapi: 3.0.3\n")

        calls = temp / "oasdiff-calls"
        fake_oasdiff = temp / "oasdiff"
        fake_oasdiff.write_text(
            "#!/usr/bin/env bash\n" f'echo "$*" >> "{calls}"\n'
        )
        fake_oasdiff.chmod(0o755)
        retired = temp / "retired.txt"
        retired.write_text(retired_text)
        accepted = temp / "accepted.txt"
        accepted.write_text(accepted_text)

        env = os.environ.copy()
        env["OASDIFF_BIN"] = str(fake_oasdiff)
        env["RETIRED_PROVIDER_CONTRACTS"] = str(retired)
        env["ACCEPTED_BREAKING_CHANGES"] = str(accepted)
        result = subprocess.run(
            [
                ROOT / "scripts/contracts/verify-provider-source-compatibility.sh",
                temp / "base",
                head,
            ],
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        return result, calls.read_text() if calls.exists() else ""

    def test_provider_gate_fails_on_an_unlisted_removed_contract(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            result, _ = self._run_provider_gate(Path(temp_dir), "# none\n", "")

        self.assertEqual(1, result.returncode)
        self.assertIn("Provider contract was removed: gone.yaml", result.stderr)

    def test_provider_gate_needs_a_reason_to_skip_a_removed_contract(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            result, _ = self._run_provider_gate(Path(temp_dir), "gone.yaml\n", "")

        self.assertEqual(1, result.returncode)

    def test_provider_gate_skips_a_retired_contract_and_passes_accepted_changes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            result, calls = self._run_provider_gate(
                temp, "gone.yaml ADR-000 removed on purpose\n", "# none\n"
            )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("retired on purpose: gone.yaml (ADR-000", result.stdout)
        self.assertEqual(1, len(calls.splitlines()))
        self.assertIn("kept.yaml", calls)
        self.assertIn(f"--err-ignore {temp / 'accepted.txt'}", calls)
        self.assertIn(f"--warn-ignore {temp / 'accepted.txt'}", calls)

    def test_reviewed_exception_lists_give_a_reason_for_every_entry(self):
        retired = (
            ROOT / "scripts/contracts/retired-provider-contracts.txt"
        ).read_text()
        for line in retired.splitlines():
            if line.strip() and not line.startswith("#"):
                name, _, reason = line.partition(" ")
                self.assertFalse((ROOT / "api" / name).exists(), name)
                self.assertIn("ADR-", reason, line)

        accepted = (
            ROOT / "scripts/contracts/accepted-breaking-changes.txt"
        ).read_text().splitlines()
        for index, line in enumerate(accepted):
            if line.strip() and not line.startswith("#"):
                self.assertTrue(
                    index > 0 and "ADR-" in accepted[index - 1],
                    f"{line!r} needs a comment line with its ADR above it",
                )


if __name__ == "__main__":
    unittest.main()
