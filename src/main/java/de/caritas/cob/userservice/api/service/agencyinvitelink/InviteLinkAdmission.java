package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** How a person who redeemed a direct link enters (ADR-029 §2). */
public enum InviteLinkAdmission {
  /** Enters the conversation right away (chat). */
  DIRECT,

  /** Waits until the counsellor admits them (audio and video calls). */
  KNOCK
}
