package de.caritas.cob.userservice.api.workflow.accountinactivity;

import java.util.Collection;
import java.util.Set;

/** Shared conservative distinction between machine access and mixed human privileges. */
public final class AccountInactivityIdentityRoles {
  private static final Set<String> TECHNICAL = Set.of("technical", "notifications-technical");
  private static final Set<String> INFRASTRUCTURE =
      Set.of(
          "offline_access",
          "uma_authorization",
          "manage-account",
          "manage-account-links",
          "view-profile",
          "view-consent",
          "manage-consent",
          "view-applications",
          "delete-account");

  private AccountInactivityIdentityRoles() {}

  public static boolean isPureTechnical(Collection<String> roles) {
    if (roles == null || roles.stream().noneMatch(role -> role != null && TECHNICAL.contains(role)))
      return false;
    return roles.stream()
        .allMatch(
            role ->
                role != null
                    && (TECHNICAL.contains(role)
                        || INFRASTRUCTURE.contains(role)
                        || role.startsWith("default-roles-")));
  }
}
