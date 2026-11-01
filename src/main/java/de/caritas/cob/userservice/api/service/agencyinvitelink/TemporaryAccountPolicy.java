package de.caritas.cob.userservice.api.service.agencyinvitelink;

/**
 * Whether the platform and the Träger allow temporary guest accounts (ADR-029 §3) for a
 * Beratungsstelle and medium. When they do not, a call share link never creates one (#1487).
 */
public interface TemporaryAccountPolicy {

  boolean allows(Long tenantId, Long agencyId, InviteLinkChatType medium);
}
