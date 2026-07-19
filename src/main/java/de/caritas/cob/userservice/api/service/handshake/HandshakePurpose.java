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
  SUPPORT_ACCESS(Set.of("global-support-admin"), Set.of("consultant")),

  /** Four-eyes credential reset for a Global Support Admin (Platform Admin + another GSA). */
  RECOVERY_SUPPORT_ADMIN(
      Set.of("global-support-admin", HandshakePurpose.PLATFORM_ADMIN),
      Set.of("global-support-admin", HandshakePurpose.PLATFORM_ADMIN)),

  /** Four-eyes credential reset for a Berater*in (Beratungsstellen-Admin + GSA). */
  RECOVERY_CONSULTANT(
      Set.of("global-support-admin", "agency-admin"),
      Set.of("global-support-admin", "agency-admin")),

  /** Granting/removing a Fachidentität (GSA initiates, a Beratungsstellen-Admin confirms). */
  IDENTITY_GRANT(Set.of("global-support-admin"), Set.of("agency-admin"));

  /** Marker for "must be a platform admin" (tenant 0 + admin role pair, not a single role). */
  static final String PLATFORM_ADMIN = "#platform-admin";

  private final Set<String> initiatorRoles;
  private final Set<String> counterpartRoles;

  public boolean mayInitiate(AuthenticatedUser user) {
    return matches(user, initiatorRoles);
  }

  public boolean mayConfirm(AuthenticatedUser user) {
    return matches(user, counterpartRoles);
  }

  private boolean matches(AuthenticatedUser user, Set<String> allowed) {
    if (allowed.contains(PLATFORM_ADMIN) && user.isPlatformAdmin()) {
      return true;
    }
    var roles = user.getRoles();
    return roles != null && allowed.stream().anyMatch(roles::contains);
  }
}
