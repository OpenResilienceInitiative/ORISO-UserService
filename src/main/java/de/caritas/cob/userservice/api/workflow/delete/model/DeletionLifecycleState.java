package de.caritas.cob.userservice.api.workflow.delete.model;

/** Security-05 lifecycle states for account deletion governance. */
public enum DeletionLifecycleState {
  ACTIVE,
  PENDING_DELETION,
  READ_ONLY_SAFEGUARD,
  HARD_DELETED
}
