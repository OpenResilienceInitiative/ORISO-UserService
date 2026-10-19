package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseHandoverConsentMigrationContractTest {

  @Test
  void rollbackRestoresOnlyTheEmergencyPolicyRetiredBy0096() throws IOException {
    String changelog =
        resource("/db/changelog/changeset/0096_case_handover_consent_mode/0096_changeSet.xml");

    assertThat(changelog)
        .contains("name=\"retired_by_0096\"")
        .contains("retired_by_0096 = 1")
        .contains("WHERE retired_by_0096 = 1")
        .contains(
            "<dropColumn tableName=\"case_handover_reason_policy\" columnName=\"retired_by_0096\" />");
  }

  private String resource(String path) throws IOException {
    try (var stream = getClass().getResourceAsStream(path)) {
      assertThat(stream).as("resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
