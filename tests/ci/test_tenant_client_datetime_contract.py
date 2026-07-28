from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
POM = ROOT / "pom.xml"
MAVEN = {"m": "http://maven.apache.org/POM/4.0.0"}
TENANT_CLIENT_EXECUTIONS = {
    "tenant-service-client-model",
    "tenant-admin-service-client-model",
}


class TenantClientDateTimeContractTest(unittest.TestCase):
    def test_tenant_clients_match_provider_local_datetime_mapping(self):
        root = ET.parse(POM).getroot()
        executions = {
            execution.findtext("m:id", namespaces=MAVEN): execution
            for execution in root.findall(".//m:execution", MAVEN)
        }

        for execution_id in TENANT_CLIENT_EXECUTIONS:
            with self.subTest(execution=execution_id):
                execution = executions[execution_id]
                type_mappings = {
                    mapping.text
                    for mapping in execution.findall(
                        "m:configuration/m:typeMappings/m:typeMapping", MAVEN
                    )
                }
                import_mappings = {
                    mapping.text
                    for mapping in execution.findall(
                        "m:configuration/m:importMappings/m:importMapping", MAVEN
                    )
                }

                self.assertIn("OffsetDateTime=LocalDateTime", type_mappings)
                self.assertIn(
                    "java.time.OffsetDateTime=java.time.LocalDateTime",
                    import_mappings,
                )


if __name__ == "__main__":
    unittest.main()
