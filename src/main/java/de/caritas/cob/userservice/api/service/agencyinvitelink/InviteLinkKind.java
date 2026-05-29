package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** Classification of an invite link's intended audience or routing behaviour. */
public enum InviteLinkKind {
  /** General-purpose link for any user belonging to this tenant's topic. */
  TENANT,

  /** Pre-routed link — the resulting session is assigned to a specific counsellor. */
  COUNSELLOR,

  /** Link distributed through an external referral channel or third-party integration. */
  EXTERNAL_INBOUND
}
