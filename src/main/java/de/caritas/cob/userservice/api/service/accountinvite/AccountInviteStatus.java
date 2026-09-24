package de.caritas.cob.userservice.api.service.accountinvite;

/** Lifecycle state of an account invite. This is not the technical provisioning status. */
public enum AccountInviteStatus {
  /** Stored unsent, without a link, until the missing unit's first admin finishes onboarding. */
  WAITING_FOR_UNIT,
  DRAFT,
  EMAIL_SENT,
  ACCEPTED,
  EXPIRED,
  REVOKED,
  SUPERSEDED
}
