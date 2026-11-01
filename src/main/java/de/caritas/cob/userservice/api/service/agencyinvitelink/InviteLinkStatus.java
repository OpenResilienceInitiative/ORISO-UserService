package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** Lifecycle state of an invite link. */
public enum InviteLinkStatus {
  /** Valid and available to be redeemed. */
  ACTIVE,

  /** Already redeemed — {@code usedAt} and {@code usedBySessionId} are populated. */
  USED,

  /** {@code expiresAt} passed before the link was redeemed. */
  EXPIRED,

  /** Switched off by its owner; can be switched on again (direct links, #1475). */
  DISABLED
}
