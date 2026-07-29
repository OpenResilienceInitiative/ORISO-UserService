from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
TENANT_ADMIN_SPEC = ROOT / "services" / "tenantadminservice.yaml"
LOCAL_TIMESTAMP_PROPERTIES = {
    "termsAndConditionsConfirmation",
    "dataPrivacyConfirmation",
}


class TenantAdminDateTimeContractTest(unittest.TestCase):
    def test_local_timestamp_fields_remain_plain_wire_strings_in_admin_client(self):
        spec = TENANT_ADMIN_SPEC.read_text()
        content_schema = spec.split("\n    Content:", 1)[1].split(
            "\n    Settings:", 1
        )[0]
        content_lines = content_schema.splitlines()

        for property_name in LOCAL_TIMESTAMP_PROPERTIES:
            with self.subTest(property=property_name):
                start = content_lines.index(f"        {property_name}:")
                property_lines = []
                for line in content_lines[start + 1 :]:
                    if line.startswith("        ") and not line.startswith("          "):
                        break
                    property_lines.append(line)
                property_block = "\n".join(property_lines)

                self.assertIn("type: string", property_block)
                self.assertNotIn("format: date-time", property_block)


if __name__ == "__main__":
    unittest.main()
