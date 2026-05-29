package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** Lifecycle state of an invite link. */
public enum InviteLinkStatus {
  /** Valid and available to be redeemed. */
  ACTIVE,

  /** Already redeemed — {@code usedAt} and {@code usedBySessionId} are populated. */
  USED,

  /** {@code expiresAt} passed before the link was redeemed. */
  EXPIRED
}
