package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** #1536: the legacy reason-policy table must stop serving health-revealing reason codes. */
class CaseHandoverNeutralReasonsMigrationContractTest {

  private static final String DIR =
      "/db/changelog/changeset/20260923_case_handover_neutral_reasons/";

  @Test
  void masterChangelogIncludesTheNeutralReasonsChangeset() throws IOException {
    assertThat(resource("/db/changelog/userservice-master.xml"))
        .contains("changeset/20260923_case_handover_neutral_reasons/changeSet.xml");
  }

  @Test
  void seedsTheFourNeutralCodesAndRetiresTheOldOnesWithoutDeletingThem() throws IOException {
    String changelog = resource(DIR + "changeSet.xml");

    assertThat(changelog)
        .contains("'ADVICE_REQUESTED'")
        .contains("'PLANNED_ABSENCE'")
        .contains("'UNPLANNED_ABSENCE'")
        .contains("'ASSIGNMENT_ENDED'")
        .contains("retired_by_neutral_reasons = 1")
        .doesNotContainIgnoringCase("DELETE FROM case_handover_request")
        .doesNotContainIgnoringCase("UPDATE case_handover_request");
  }

  @Test
  void newMarkerColumnHasADefaultSoExistingInsertsKeepWorking() throws IOException {
    assertThat(resource(DIR + "changeSet.xml"))
        .containsPattern(
            "name=\"retired_by_neutral_reasons\"\\s+type=\"BOOLEAN\"\\s+defaultValueBoolean=\"false\"");
  }

  @Test
  void rollbackRestoresOnlyTheRowsThisChangesetRetired() throws IOException {
    assertThat(resource(DIR + "changeSet.xml"))
        .contains("<rollback>")
        .contains("WHERE retired_by_neutral_reasons = 1")
        .contains(
            "<dropColumn tableName=\"case_handover_reason_policy\" columnName=\"retired_by_neutral_reasons\" />");
  }

  private String resource(String path) throws IOException {
    try (var stream = getClass().getResourceAsStream(path)) {
      assertThat(stream).as("resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
