package de.caritas.cob.userservice.api.service.accountinvite;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Installed profile identifiers are data; only the native human approval grants authority. */
public record ProtectedInvitationProfiles(
    String realmId, Map<AccountInviteTargetRole, String> profileIds) {
  public ProtectedInvitationProfiles {
    if (!binding(realmId) || profileIds == null
        || !profileIds.keySet().equals(Set.of(
            AccountInviteTargetRole.TENANT_ADMIN, AccountInviteTargetRole.PLATFORM_ADMIN))
        || profileIds.values().stream().anyMatch(value -> !binding(value))
        || new HashSet<>(profileIds.values()).size() != 2) {
      throw new IllegalArgumentException("Configure the reviewed protected invitation profile bindings");
    }
    profileIds = Map.copyOf(profileIds);
  }

  private static boolean binding(String value) {
    return value != null && !value.isBlank() && value.length() <= 255
        && value.equals(value.trim()) && !value.contains("${")
        && value.codePoints().noneMatch(Character::isISOControl);
  }
}
