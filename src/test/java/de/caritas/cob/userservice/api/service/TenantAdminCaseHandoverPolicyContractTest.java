package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TenantAdminCaseHandoverPolicyContractTest {

  @Test
  @SuppressWarnings("unchecked")
  void tenantAdminClient_shouldMirrorTheExplicitCaseHandoverConsentContract() throws IOException {
    Map<String, Object> specification =
        new Yaml().load(Files.readString(Path.of("services/tenantadminservice.yaml")));
    var components = (Map<String, Object>) specification.get("components");
    var schemas = (Map<String, Object>) components.get("schemas");
    var consentValue = (Map<String, Object>) schemas.get("CaseHandoverConsentValue");
    var consentPolicy = (Map<String, Object>) schemas.get("ConsentPermissionPolicy");
    var reasonPolicy = (Map<String, Object>) schemas.get("CaseHandoverReasonPolicy");
    var reasonRequired = (List<String>) reasonPolicy.get("required");
    var reasonProperties = (Map<String, Object>) reasonPolicy.get("properties");

    assertThat((List<String>) consentValue.get("enum"))
        .containsExactly("OPT_IN", "OPT_OUT", "NONE");
    assertThat((List<String>) consentPolicy.get("required")).containsExactly("value", "mode");
    assertThat(reasonRequired).contains("clientConsent");
    assertThat(reasonRequired).doesNotContain("clientConsentRequired");
    assertThat((Map<String, Object>) reasonProperties.get("clientConsentRequired"))
        .containsEntry("deprecated", true);
  }
}
