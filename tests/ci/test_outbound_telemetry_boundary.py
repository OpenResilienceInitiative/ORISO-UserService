from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "src/main/java"
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


if __name__ == "__main__":
    unittest.main()
