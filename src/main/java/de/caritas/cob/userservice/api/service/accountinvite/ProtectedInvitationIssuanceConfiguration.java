package de.caritas.cob.userservice.api.service.accountinvite;

import org.springframework.core.env.Environment;

/** Inert configuration receiving seam; strict installed metadata has its own assertion gate. */
public class ProtectedInvitationIssuanceConfiguration {
  public ProtectedInvitationIssuanceConfiguration(Environment environment) {}

  public ProtectedInvitationProfiles protectedInvitationProfiles() {
    return null;
  }
}
