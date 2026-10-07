package de.caritas.cob.userservice.api.config.auth;

/** Actual automatic responsibilities; ordinary public settings need no machine identity. */
public enum TaskIdentity {
  CONFIG_WIZARD("config-wizard"),
  INVITE_RESERVATIONS("invite-reservations"),
  NOTIFICATION_DISPATCH("notification-dispatch"),
  SYSTEM_EMAIL_DELIVERY("system-email-delivery"),
  ACCOUNT_PROVISIONING("account-provisioning"),
  ACCOUNT_MAINTENANCE("account-maintenance"),
  OTP("otp"),
  SESSION_EXCHANGE("session-exchange"),
  APPOINTMENT_SYNC("appointment-sync"),
  APPOINTMENT_CLEANUP("appointment-cleanup"),
  MATRIX_AGENCY("matrix-agency"),
  RUNTIME_POLICY("runtime-policy");

  private final String key;

  TaskIdentity(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  public java.util.Set<String> roles() {
    return switch (this) {
      case CONFIG_WIZARD -> java.util.Set.of("config-wizard");
      case INVITE_RESERVATIONS -> java.util.Set.of("invitation-reservations");
      case NOTIFICATION_DISPATCH ->
          java.util.Set.of("notification-dispatch", "notifications-technical");
      case SYSTEM_EMAIL_DELIVERY -> java.util.Set.of("system-email-delivery");
      case RUNTIME_POLICY -> java.util.Set.of("runtime-policy");
      case MATRIX_AGENCY -> java.util.Set.of("matrix-agency", "matrix-agency-provision");
      case APPOINTMENT_SYNC -> java.util.Set.of("appointment-sync");
      case APPOINTMENT_CLEANUP -> java.util.Set.of("appointment-participant-cleanup");
      case ACCOUNT_PROVISIONING -> java.util.Set.of("account-provisioning", "account-read");
      case ACCOUNT_MAINTENANCE -> java.util.Set.of("account-maintenance", "account-read");
      case OTP -> java.util.Set.of("otp-config-admin");
      case SESSION_EXCHANGE -> java.util.Set.of("session-exchange");
    };
  }

  public java.util.Set<String> audiences() {
    return switch (this) {
      case CONFIG_WIZARD ->
          java.util.Set.of("tenantservice", "agencyservice", "consultingtypeservice");
      case INVITE_RESERVATIONS -> java.util.Set.of("tenantservice", "agencyservice");
      case NOTIFICATION_DISPATCH ->
          java.util.Set.of("tenantservice", "agencyservice", "userservice");
      case SYSTEM_EMAIL_DELIVERY -> java.util.Set.of("consultingtypeservice");
      case RUNTIME_POLICY -> java.util.Set.of("tenantservice");
      case MATRIX_AGENCY -> java.util.Set.of("agencyservice");
      case APPOINTMENT_SYNC -> java.util.Set.of("appointmentservice");
      case APPOINTMENT_CLEANUP -> java.util.Set.of("appointmentservice");
      case ACCOUNT_PROVISIONING -> java.util.Set.of("oriso-task-commands");
      case ACCOUNT_MAINTENANCE -> java.util.Set.of("oriso-task-commands");
      case OTP -> java.util.Set.of("oriso-task-commands");
      case SESSION_EXCHANGE -> java.util.Set.of();
    };
  }
}
