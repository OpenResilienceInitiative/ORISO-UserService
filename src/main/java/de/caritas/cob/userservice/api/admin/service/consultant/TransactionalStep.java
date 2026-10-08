package de.caritas.cob.userservice.api.admin.service.consultant;

public enum TransactionalStep {
  CREATE_ACCOUNT_IN_KEYCLOAK,
  CREATE_CONSULTANT_IN_MARIADB,

  /**
   * Chat (Matrix) account provisioning. Creation does <em>not</em> roll back on this step; it names
   * the failed step of the repair path.
   */
  CREATE_ACCOUNT_IN_MATRIX,

  CREATE_ACCOUNT_IN_CALCOM_OR_APPOINTMENTSERVICE,

  SAVE_CONSULTANT_IN_MARIADB,
  ROLLBACK_CONSULTANT_IN_MARIADB,

  PATCH_APPOINTMENT_SERVICE_CONSULTANT,
  UPDATE_USER_PASSWORD_IN_KEYCLOAK,
  UPDATE_USER_ROLES_IN_KEYCLOAK,

  ASSIGN_CONSULTANT_ROLE_IN_KEYCLOAK,
  CREATE_CONSULTANT_IN_MARIADB_FOR_EXISTING_USER;
}
