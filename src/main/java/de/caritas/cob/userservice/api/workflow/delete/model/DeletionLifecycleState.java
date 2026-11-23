package de.caritas.cob.userservice.api.workflow.delete.model;

/** Security-05 lifecycle states for account deletion governance. */
public enum DeletionLifecycleState {
  ACTIVE,
  PENDING_DELETION,
  READ_ONLY_SAFEGUARD,
  REACTIVATION_IN_PROGRESS,
  REACTIVATION_REPAIR_REQUIRED,
  HARD_DELETE_IN_PROGRESS,
  HARD_DELETE_PARTIAL_FAILURE,
  HARD_DELETED
}
