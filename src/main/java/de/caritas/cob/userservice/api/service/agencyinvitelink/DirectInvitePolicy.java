package de.caritas.cob.userservice.api.service.agencyinvitelink;

/**
 * Whether "Direkteinladung erlauben" (ORISO-Admin#1242) is effective for a Beratungsstelle and
 * medium. Checked at link creation, when a link is switched on, and at redemption (ADR-029 §6).
 */
public interface DirectInvitePolicy {

  boolean allows(Long tenantId, Long agencyId, InviteLinkChatType medium);
}
