package de.caritas.cob.userservice.api.workflow.delete.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DeletionFailureSummaryTest {

  private static DeletionWorkflowError error(DeletionTargetType target, String reason) {
    return DeletionWorkflowError.builder()
        .deletionSourceType(DeletionSourceType.ASKER)
        .deletionTargetType(target)
        .identifier("a-1")
        .reason(reason)
        .build();
  }

  @Test
  void of_Should_nameEachFailedStoreOnceWithItsReason() {
    var summary =
        DeletionFailureSummary.of(
            List.of(
                error(DeletionTargetType.KEYCLOAK, "Unable to delete keycloak user account"),
                error(DeletionTargetType.USER_CONTENT, "Could not delete draft messages"),
                error(DeletionTargetType.KEYCLOAK, "Unable to delete keycloak user account")));

    assertThat(summary)
        .isEqualTo(
            "KEYCLOAK: Unable to delete keycloak user account; USER_CONTENT: Could not delete"
                + " draft messages");
  }

  @Test
  void of_Should_fitTheColumn() {
    var many =
        java.util.stream.IntStream.range(0, 40)
            .mapToObj(i -> error(DeletionTargetType.DATABASE, "Reason number " + i))
            .toList();

    var summary = DeletionFailureSummary.of(many);

    assertThat(summary).hasSize(DeletionFailureSummary.MAX_LENGTH).endsWith("…");
  }

  @Test
  void of_Should_beNull_When_nothingFailed() {
    assertThat(DeletionFailureSummary.of(List.of())).isNull();
  }

  @Test
  void blockingReference_Should_nameTableAndConstraintOfAMariaDbForeignKey() {
    var failure =
        new DataIntegrityViolationException(
            "could not execute statement",
            new SQLIntegrityConstraintViolationException(
                "(conn=42) Cannot delete or update a parent row: a foreign key constraint fails"
                    + " (`userservice`.`session`, CONSTRAINT `session_ibfk_1` FOREIGN KEY"
                    + " (`user_id`) REFERENCES `user` (`user_id`))"));

    assertThat(DeletionFailureSummary.blockingReference(failure))
        .contains("still referenced by session (session_ibfk_1)");
  }

  @Test
  void blockingReference_Should_nameTableAndConstraintOfAnH2ForeignKey() {
    var failure =
        new DataIntegrityViolationException(
            "could not execute statement",
            new SQLIntegrityConstraintViolationException(
                "Referential integrity constraint violation: \"FK_SESSION_USER: PUBLIC.SESSION"
                    + " FOREIGN KEY(USER_ID) REFERENCES PUBLIC.\"USER\"(USER_ID)"
                    + " ('a-1')\"; SQL statement: delete from \"user\" where user_id=?"));

    assertThat(DeletionFailureSummary.blockingReference(failure))
        .contains("still referenced by session (FK_SESSION_USER)");
  }

  @Test
  void blockingReference_Should_beEmpty_When_notAForeignKeyFailure() {
    assertThat(DeletionFailureSummary.blockingReference(new IllegalStateException("boom")))
        .isEmpty();
  }
}
