package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** A kept counsellor names the reference that blocked the delete (ORISO-Admin#1523). */
class DeleteDatabaseConsultantActionBlockingReferenceTest {

  @Test
  void aMariaDbForeignKeyFailure_Should_NameTheConstraint() {
    var failure =
        new DataIntegrityViolationException(
            "could not execute statement",
            new SQLIntegrityConstraintViolationException(
                "Cannot delete or update a parent row: a foreign key constraint fails"
                    + " (`userservice`.`session`, CONSTRAINT `session_ibfk_2` FOREIGN KEY"
                    + " (`consultant_id`) REFERENCES `consultant` (`consultant_id`))"));

    assertThat(DeleteDatabaseConsultantAction.blockingReference(failure))
        .isEqualTo(": blocked by session_ibfk_2");
  }

  @Test
  void anH2ForeignKeyFailure_Should_NameTheConstraint() {
    var failure =
        new DataIntegrityViolationException(
            "Referential integrity constraint violation: \"LANGUAGE_ID_CONSULTANT_CONSTRAINT:"
                + " PUBLIC.LANGUAGE FOREIGN KEY(CONSULTANT_ID)\"");

    assertThat(DeleteDatabaseConsultantAction.blockingReference(failure))
        .isEqualTo(": blocked by language_id_consultant_constraint");
  }

  @Test
  void anyOtherFailure_Should_AddNothing() {
    assertThat(DeleteDatabaseConsultantAction.blockingReference(new IllegalStateException("x")))
        .isEmpty();
    assertThat(DeleteDatabaseConsultantAction.blockingReference(new IllegalStateException()))
        .isEmpty();
  }
}
