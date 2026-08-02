package de.caritas.cob.userservice.api.service.handshake;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.util.Set;
import lombok.AllArgsConstructor;

/**
 * Purposes of a live handshake (ADR-018). Each purpose declares which roles may initiate and which
 * may confirm; a handshake never executes with fewer than two distinct authorized humans.
 *
 * <p>Role names are Keycloak realm-role values. {@code global-support-admin} is introduced by the
 * SUPPORT-01 epic (US#496); referencing it here is forward-compatible — a user simply cannot hold
 * it before that role exists.
 */
@AllArgsConstructor
public enum HandshakePurpose {
  /** A Global Support Admin asks a Berater*in to open a fresh 1:1 support room. */
  SUPPORT_ACCESS(Set.of("global-support-admin"), Set.of("consultant"), true);

  private final Set<String> initiatorRoles;
  private final Set<String> counterpartRoles;

  /**
   * Release 1 offers SUPPORT_ACCESS only. Recovery and identity grants reuse this core later; until
   * their participant and tenant rules are defined they must not inherit the support policy by
   * accident, so they stay unreachable rather than merely undocumented.
   */
  private final boolean publiclyOffered;

  public boolean isPubliclyOffered() {
    return publiclyOffered;
  }

  public boolean mayInitiate(AuthenticatedUser user) {
    return matches(user, initiatorRoles);
  }

  public boolean mayConfirm(AuthenticatedUser user) {
    return matches(user, counterpartRoles);
  }

  private boolean matches(AuthenticatedUser user, Set<String> allowed) {
    var roles = user.getRoles();
    return roles != null && allowed.stream().anyMatch(roles::contains);
  }
}
