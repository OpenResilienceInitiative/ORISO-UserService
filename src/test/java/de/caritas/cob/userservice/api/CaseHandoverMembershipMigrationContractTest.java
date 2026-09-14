package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class CaseHandoverMembershipMigrationContractTest {

  @Test
  void migrationTracksOnlyMembershipCreatedByTheHandover() throws IOException {
    String changelog =
        resource("/db/changelog/changeset/0097_case_handover_matrix_membership/0097_changeSet.xml");

    assertThat(changelog)
        .contains("matrix_membership_added")
        .contains("<rollback>")
        .contains("<dropColumn");
  }

  @Test
  void consentDecisionLookupUsesAPessimisticWriteLock() throws NoSuchMethodException {
    var method =
        CaseHandoverRequestRepository.class.getMethod(
            "findByIdAndSessionId", Long.class, Long.class);

    assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
  }

  @Test
  void failedMatrixCompensationHasAnIndependentDurableRetryTable() throws IOException {
    String changelog =
        resource("/db/changelog/changeset/0098_case_handover_matrix_repair/0098_changeSet.xml");
    String migration =
        resource("/db/changelog/changeset/0098_case_handover_matrix_repair/migrate.sql");

    assertThat(changelog).contains("0098-case-handover-matrix-repair").contains("<rollback>");
    assertThat(migration)
        .contains("case_handover_matrix_repair_task")
        .contains("attempt_count")
        .contains("operator_id")
        .contains("session_id")
        .contains("requester_consultant_id");
  }

  private String resource(String path) throws IOException {
    try (var stream = getClass().getResourceAsStream(path)) {
      assertThat(stream).as("resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
