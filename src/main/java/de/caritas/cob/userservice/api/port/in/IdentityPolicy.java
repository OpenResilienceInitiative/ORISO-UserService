package de.caritas.cob.userservice.api.port.in;

import java.util.Set;

/** Application-owned identity policy decisions needed by inbound adapters. */
public interface IdentityPolicy {

  boolean isTwoFactorAuthenticationAllowed(Set<String> roles);

  boolean isConsultantDisplayNameAllowed();

  boolean isProfileEmailUsableForMagicLink(String email);
}
