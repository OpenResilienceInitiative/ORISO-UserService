package de.caritas.cob.userservice.api.config.auth;

import java.util.Set;

/** Five credentials; an operation purpose is not another identity. */
public enum TaskActor {
  SETUP(
      Set.of("config-wizard", "invitation-reservations", "consultant-import"),
      Set.of("tenantservice", "agencyservice", "consultingtypeservice", "userservice")),
  ACCOUNT_CREATE(Set.of("account-provisioning", "account-read"), Set.of()),
  ACCOUNT_ADMIN(
      Set.of("account-maintenance", "account-read", "otp-config-admin", "session-exchange"),
      Set.of()),
  MAIL(
      Set.of(
          "smtp-sync", "system-email-delivery", "notification-dispatch", "notifications-technical"),
      Set.of("tenantservice", "agencyservice", "consultingtypeservice", "userservice")),
  RUNTIME(
      Set.of(
          "runtime-policy",
          "matrix-agency",
          "matrix-agency-provision",
          "appointment-sync",
          "appointment-participant-cleanup",
          "deletion-audit-read"),
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

  /** Account audiences remain empty until their native contracts are pinned. */
  public Set<String> audiences() {
    return audiences;
  }
}
