package de.caritas.cob.userservice.api.workflow.delete.model;

/**
 * Where a deletion stands. {@link #SCHEDULED}: marked and inside the safeguarding window; {@link
 * #EXECUTED}: the account is gone; {@link #PARTIAL_FAILURE}: the database row is gone, but an
 * earlier step (for example Keycloak or Matrix) failed, so the login or chat account may still
 * exist; {@link #CANCELLED}: a scheduled deletion was withdrawn.
 */
public enum DeletionAuditOutcome {
  SCHEDULED,
  EXECUTED,
  PARTIAL_FAILURE,
  CANCELLED
}
