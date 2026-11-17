package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.GuestAccountMode;
import java.util.List;
import java.util.Set;

/**
 * Which guest account modes (#1662) a Beratungsstelle may offer for a medium: the platform cap
 * ({@link PlatformGuestAccountModePolicy}) intersected with the Träger's allowed modes ({@link
 * TraegerGuestAccountModePolicy}). Every caller (link create and update, registration, public
 * context) follows both.
 */
public interface GuestAccountModePolicy {

  Set<GuestAccountMode> allowedModes(Long tenantId, Long agencyId, InviteLinkChatType medium);

  default boolean allows(
      Long tenantId, Long agencyId, InviteLinkChatType medium, GuestAccountMode mode) {
    return allowedModes(tenantId, agencyId, medium).contains(mode);
  }

  /**
   * The mode a link set earlier works with now: a mode that is no longer allowed falls back to the
   * closest mode that still is ({@link #fallbackWithin}).
   */
  default GuestAccountMode cap(
      Long tenantId, Long agencyId, InviteLinkChatType medium, GuestAccountMode mode) {
    return fallbackWithin(allowedModes(tenantId, agencyId, medium), mode);
  }

  /**
   * {@code preferred} while it is allowed. Otherwise {@link GuestAccountMode#PERMANENT}, which
   * never creates an account ORISO deletes; where the Träger offers no permanent guest accounts,
   * the guest's choice, then temporary only. With nothing allowed at all, {@link
   * GuestAccountMode#PERMANENT}.
   */
  static GuestAccountMode fallbackWithin(
      Set<GuestAccountMode> allowed, GuestAccountMode preferred) {
    if (preferred != null && allowed.contains(preferred)) {
      return preferred;
    }
    return FALLBACK_ORDER.stream()
        .filter(allowed::contains)
        .findFirst()
        .orElse(GuestAccountMode.PERMANENT);
  }

  List<GuestAccountMode> FALLBACK_ORDER =
      List.of(
          GuestAccountMode.PERMANENT, GuestAccountMode.GUEST_CHOOSES, GuestAccountMode.TEMPORARY);
}
