package de.caritas.cob.userservice.api.workflow.delete.model;

/**
 * Where a deletion stands. {@link #SCHEDULED}: marked and inside the safeguarding window; {@link
 * #EXECUTED}: the account is gone; {@link #CANCELLED}: a scheduled deletion was withdrawn.
 */
public enum DeletionAuditOutcome {
  SCHEDULED,
  EXECUTED,
  CANCELLED
}
