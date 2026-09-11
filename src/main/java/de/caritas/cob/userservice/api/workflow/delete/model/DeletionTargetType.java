package de.caritas.cob.userservice.api.workflow.delete.model;

public enum DeletionTargetType {
  KEYCLOAK,
  MATRIX,
  DATABASE,
  ANONYMOUS_REGISTRY_IDS,
  APPOINTMENT_SERVICE,
  /**
   * Unencrypted user content stored server-side — drafts and the notification feed. Kept apart from
   * {@link #DATABASE} so the account row can refuse to disappear while that content is still there:
   * the row is the only handle the next scheduler run has to retry (#983, KDG epic #1010).
   */
  USER_CONTENT,
  ALL;
}
