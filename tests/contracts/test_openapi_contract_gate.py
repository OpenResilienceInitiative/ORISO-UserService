import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class OpenApiContractGateTest(unittest.TestCase):
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
                r"11811e3f5abb75ad0710e9591bdc050eba6b21ef",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            workflow,
            re.compile(
                r"repository: OpenResilienceInitiative/ORISO-ConsultingTypeService.*"
                r"48004847491b0ad0d38296a5d57d1ba3c1ea4730",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            workflow,
            re.compile(
                r"repository: OpenResilienceInitiative/ORISO-TenantService.*"
                r"232a7bd59eebb20681f84ad4f2b9e48bfcbd25fd",
                re.DOTALL,
            ),
        )
        self.assertIn("|| 'pre-dev'", workflow)

    def test_contract_gate_tests_are_executed_by_ci(self):
        # A gate assertion that never runs protects nothing. Without a job that
        # invokes pytest, this file can drift away from the workflow and the
        # scripts it describes while every check stays green.
        workflow = (ROOT / ".github/workflows/openapi-contracts.yml").read_text()

        self.assertIn("contract-gate-tests:", workflow)
        self.assertIn("python -m pytest -q tests/contracts", workflow)

    def test_team_access_contract_uses_positive_owner_bound_boolean(self):
        provider = yaml.safe_load((ROOT / "api/userservice.yaml").read_text())

        operation = provider["paths"][
            "/users/sessions/{sessionId}/team-access"
        ]["post"]
        request_schema = operation["requestBody"]["content"]["application/json"][
            "schema"
        ]
        self.assertEqual(
            "#/components/schemas/TeamAccessDTO", request_schema["$ref"]
        )
        self.assertIn("403", operation["responses"])

        team_access = provider["components"]["schemas"]["TeamAccessDTO"]
        self.assertEqual(["allowed"], team_access["required"])
        self.assertEqual("boolean", team_access["properties"]["allowed"]["type"])


if __name__ == "__main__":
    unittest.main()
