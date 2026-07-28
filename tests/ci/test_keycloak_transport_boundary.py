from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "src/main/java"
KEYCLOAK_TRANSPORT = (
    JAVA_ROOT
    / "de/caritas/cob/userservice/api/adapters/keycloak/config"
    / "KeycloakAdminClientTransport.java"
)


class KeycloakTransportBoundaryContractTest(unittest.TestCase):
    def test_resteasy_transport_internals_stay_in_the_keycloak_transport_module(self):
        offenders = []

        for source in JAVA_ROOT.rglob("*.java"):
            if source == KEYCLOAK_TRANSPORT:
                continue
            for line in source.read_text().splitlines():
                if line.startswith("import org.jboss.resteasy."):
                    offenders.append(f"{source.relative_to(ROOT)} imports {line}")

        self.assertEqual(
            [],
            offenders,
            "RESTEasy is a Keycloak adapter implementation detail and must remain local "
            "to KeycloakAdminClientTransport:\n" + "\n".join(offenders),
        )


if __name__ == "__main__":
    unittest.main()
