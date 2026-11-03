package de.caritas.cob.userservice.api.config.auth;

/** The operation remains explicit even when several operations use one actor. */
public enum TaskPurpose {
  CONFIG_WIZARD(TaskActor.SETUP),
  INVITE_RESERVATIONS(TaskActor.SETUP),
  CONSULTANT_IMPORT(TaskActor.SETUP),
  ACCOUNT_PROVISIONING(TaskActor.ACCOUNT_CREATE),
  ACCOUNT_MAINTENANCE(TaskActor.ACCOUNT_ADMIN),
  OTP(TaskActor.ACCOUNT_ADMIN),
  SESSION_EXCHANGE(TaskActor.ACCOUNT_ADMIN),
  SMTP_SYNC(TaskActor.MAIL),
  SYSTEM_EMAIL_DELIVERY(TaskActor.MAIL),
  NOTIFICATION_DISPATCH(TaskActor.MAIL),
  RUNTIME_POLICY(TaskActor.RUNTIME),
  MATRIX_AGENCY(TaskActor.RUNTIME),
  APPOINTMENT_SYNC(TaskActor.RUNTIME),
  APPOINTMENT_CLEANUP(TaskActor.RUNTIME),
  /** Pulls finished agency purges from AgencyService into the deletion log (OBP 2.1 #130). */
  DELETION_AUDIT_PULL(TaskActor.RUNTIME);

  private final TaskActor actor;

  TaskPurpose(TaskActor actor) {
    this.actor = actor;
  }

  public TaskActor actor() {
    return actor;
  }
}
