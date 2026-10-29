package de.caritas.cob.userservice.api.config.auth;

import java.util.Set;

/** Five credentials; an operation purpose is not another identity. */
public enum TaskActor {
  SETUP(
      Set.of("config-wizard", "invitation-reservations", "consultant-import", "smtp-sync"),
      Set.of(
          "tenantservice",
          "agencyservice",
          "consultingtypeservice",
          "userservice",
          "oriso-task-commands")),
  ACCOUNT_CREATE(
      Set.of("account-provisioning", "account-read"),
      Set.of("userservice", "oriso-account-lifecycle")),
  ACCOUNT_ADMIN(
      Set.of("account-maintenance", "account-read", "otp-config-admin", "session-exchange"),
      Set.of("userservice", "oriso-account-lifecycle", "oriso-otp")),
  MAIL(
      Set.of("system-email-delivery", "notification-dispatch", "notifications-technical"),
      Set.of("tenantservice", "agencyservice", "consultingtypeservice", "userservice")),
  RUNTIME(
      Set.of(
          "runtime-policy",
          "matrix-agency",
          "matrix-agency-provision",
          "appointment-sync",
          "appointment-participant-cleanup"),
      Set.of("tenantservice", "agencyservice", "appointmentservice"));

  private final Set<String> roles;
  private final Set<String> audiences;

  TaskActor(Set<String> roles, Set<String> audiences) {
    this.roles = roles;
    this.audiences = audiences;
  }

  public Set<String> roles() {
    return roles;
  }

  /** Canonical audiences never authorize a generic native-account token grant. */
  public Set<String> audiences() {
    return audiences;
  }
}
