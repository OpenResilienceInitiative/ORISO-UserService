import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "src/main/java"
DEPENDENCY_CATALOG = ROOT / "documentation/user-service-outbound-dependencies.json"
API_CLIENT_ROOT = (
    JAVA_ROOT / "de/caritas/cob/userservice/api/config/apiclient"
)
REST_TEMPLATE_FACTORIES = {
    JAVA_ROOT / "de/caritas/cob/userservice/api/config/AppConfig.java",
    JAVA_ROOT
    / "de/caritas/cob/userservice/api/adapters/keycloak/config/KeycloakConfig.java",
}


class OutboundTelemetryBoundaryContractTest(unittest.TestCase):
    def test_rest_template_construction_stays_in_observed_factories(self):
        offenders = []

        for source in JAVA_ROOT.rglob("*.java"):
            content = source.read_text()
            if "new RestTemplate(" in content:
                offenders.append(f"{source.relative_to(ROOT)} constructs RestTemplate directly")
            if "RestTemplateBuilder" in content and source not in REST_TEMPLATE_FACTORIES:
                offenders.append(
                    f"{source.relative_to(ROOT)} constructs a RestTemplate outside its factories"
                )

        self.assertEqual(
            [],
            offenders,
            "Every production RestTemplate must receive the bounded outbound telemetry policy:\n"
            + "\n".join(offenders),
        )

        for factory in REST_TEMPLATE_FACTORIES:
            content = factory.read_text()
            self.assertIn("OutboundHttpMetrics", content)
            self.assertIn("outboundHttpMetrics.customize(restTemplate)", content)

    def test_generated_http_factories_match_the_dependency_catalog(self):
        catalog = json.loads(DEPENDENCY_CATALOG.read_text())
        declared_factories = catalog["generatedHttpApiFactories"]
        declared_files = {entry["file"] for entry in declared_factories}
        discovered_files = {
            str(source.relative_to(ROOT))
            for source in API_CLIENT_ROOT.glob("*ApiControllerFactory.java")
        }

        self.assertEqual(discovered_files, declared_files)
        self.assertEqual(len(declared_files), len(declared_factories))

        application_properties = (
            ROOT / "src/main/resources/application.properties"
        ).read_text()
        for entry in declared_factories:
            with self.subTest(factory=entry["file"]):
                source = (ROOT / entry["file"]).read_text()
                property_name = entry["configurationProperty"]
                self.assertIn("${" + property_name, source)
                self.assertIn(property_name + "=", application_properties)

    def test_custom_http_transports_match_the_dependency_catalog(self):
        catalog = json.loads(DEPENDENCY_CATALOG.read_text())
        declared_files = {
            ROOT / entry["constructionSite"]
            for entry in catalog["customHttpTransports"]
        }
        expected_files = REST_TEMPLATE_FACTORIES | {
            JAVA_ROOT
            / "de/caritas/cob/userservice/api/adapters/keycloak/config/"
            "KeycloakAdminClientTransport.java"
        }

        self.assertEqual(expected_files, declared_files)

    def test_dependency_catalog_declares_application_fallback_measurement(self):
        catalog = json.loads(DEPENDENCY_CATALOG.read_text())
        fallback_metric = catalog["measurementPolicy"]["applicationFallbacks"]
        fallback_telemetry = (
            JAVA_ROOT
            / "de/caritas/cob/userservice/api/config/observability/"
            "ConsultantAgencyFallbackTelemetry.java"
        ).read_text()

        self.assertEqual("userservice.dependency.fallbacks", fallback_metric)
        self.assertIn(f'"{fallback_metric}"', fallback_telemetry)
        self.assertIn('"agency-service"', fallback_telemetry)
        self.assertIn('"consultant-agency-batch"', fallback_telemetry)

    def test_dependency_catalog_declares_enforced_transport_bounds(self):
        catalog = json.loads(DEPENDENCY_CATALOG.read_text())
        policy = catalog["transportPolicies"]["default-http"]

        self.assertEqual(3000, policy["connectTimeoutMs"])
        self.assertEqual(10000, policy["readTimeoutMs"])
        self.assertEqual(0, policy["automaticTransportRetries"])
        self.assertEqual(
            42000,
            catalog["transportPolicies"]["matrix-long-poll"]["readTimeoutMs"],
        )
        timeout_source = (
            JAVA_ROOT
            / "de/caritas/cob/userservice/api/config/RestTemplateTimeouts.java"
        ).read_text()
        self.assertIn("CONNECT_TIMEOUT = Duration.ofSeconds(3)", timeout_source)
        self.assertIn("READ_TIMEOUT = Duration.ofSeconds(10)", timeout_source)
        self.assertIn(
            "MATRIX_LONG_POLL_READ_TIMEOUT = Duration.ofSeconds(42)",
            timeout_source,
        )

    def test_technical_identity_grant_amortization_is_documented_and_bounded(self):
        catalog = json.loads(DEPENDENCY_CATALOG.read_text())
        reduction = next(
            entry
            for entry in catalog["chattyCallReductions"]
            if entry["id"] == "agency-matrix-technical-identity-grant"
        )

        self.assertEqual(1, reduction["maxGrantsPerReplicaPerTokenLifetime"])
        self.assertEqual(1, reduction["maxAuthRefreshRetriesPerCall"])
        self.assertEqual(2, reduction["maxAgencyCredentialAttemptsPerSession"])
        self.assertTrue(reduction["invalidateEveryUnauthorizedGrant"])
        self.assertEqual(64, reduction["parallelFullPathSessions"])
        provider = (ROOT / reduction["implementation"]).read_text()
        client = (ROOT / reduction["consumer"]).read_text()
        full_path_contract = (ROOT / reduction["fullPathContract"]).read_text()
        self.assertIn("synchronized String getAccessToken()", provider)
        self.assertIn("void invalidate(", provider)
        self.assertIn('"matrix-credentials-auth-refresh"', client)
        self.assertIn("tokenProvider.invalidate(accessToken)", client)
        self.assertIn("CreateSessionFacade", full_path_contract)
        self.assertIn("KeycloakAuthClient", full_path_contract)
        self.assertIn("parallelSessionCreationUsesOneIdentityGrant", full_path_contract)
        self.assertIn("parallelStaleGrantRefreshIsShared", full_path_contract)


if __name__ == "__main__":
    unittest.main()
