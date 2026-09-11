package de.caritas.cob.userservice.api.port.in;

import java.util.Set;

/**
 * Application-owned identity policy decisions needed by inbound adapters.
 *
 * <p>Inbound adapters ask this port a question ("may this user use 2FA?") instead of reading the
 * outbound {@code IdentityClientConfig} and drawing the conclusion themselves. Keeping the rule on
 * this side of the boundary is what stops provider configuration — role flags, the dummy-email
 * suffix — from leaking into controllers, where it would have to be re-derived by every caller.
 */
public interface IdentityPolicy {

  boolean isTwoFactorAuthenticationAllowed(Set<String> roles);

  boolean isConsultantDisplayNameAllowed();

  /**
   * Whether a profile email can receive a Magic Link. Accounts created without an address carry a
   * generated dummy one, which is deliverable to nobody.
   */
  boolean isProfileEmailUsableForMagicLink(String email);
}
