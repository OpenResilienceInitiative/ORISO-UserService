package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.GuestAccountMode;
import java.util.Set;

/**
 * Which guest account modes (#1662) a Beratungsstelle may offer for a medium. Today only the
 * platform cap ({@link TemporaryAccountPolicy}) applies. The Träger cap ("which modes a Träger
 * allows", Admin/TenantService checkpoint of #1662) plugs in here: an implementation that also
 * reads the Träger's allowed modes, so every caller (link create and update, registration, public
 * context) follows it without change.
 */
public interface GuestAccountModePolicy {

  Set<GuestAccountMode> allowedModes(Long tenantId, Long agencyId, InviteLinkChatType medium);

  default boolean allows(
      Long tenantId, Long agencyId, InviteLinkChatType medium, GuestAccountMode mode) {
    return allowedModes(tenantId, agencyId, medium).contains(mode);
  }

  /**
   * The mode a link set earlier works with now: a mode that is no longer allowed falls back to
   * {@link GuestAccountMode#PERMANENT}, which never creates an account ORISO deletes.
   */
  default GuestAccountMode cap(
      Long tenantId, Long agencyId, InviteLinkChatType medium, GuestAccountMode mode) {
    return allows(tenantId, agencyId, medium, mode) ? mode : GuestAccountMode.PERMANENT;
  }
}
