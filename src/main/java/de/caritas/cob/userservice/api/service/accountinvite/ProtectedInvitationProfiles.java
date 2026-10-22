package de.caritas.cob.userservice.api.service.accountinvite;

import java.util.Map;

/** Installed profile identifiers are data; only the native human approval grants authority. */
public record ProtectedInvitationProfiles(
    String realmId, Map<AccountInviteTargetRole, String> profileIds) {
  public ProtectedInvitationProfiles {
    profileIds = Map.copyOf(profileIds);
  }
}
