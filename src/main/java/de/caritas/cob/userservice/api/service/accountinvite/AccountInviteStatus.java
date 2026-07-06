package de.caritas.cob.userservice.api.service.accountinvite;

/** Lifecycle state of an account invite. This is not the technical provisioning status. */
public enum AccountInviteStatus {
  DRAFT,
  EMAIL_SENT,
  ACCEPTED,
  EXPIRED,
  REVOKED,
  SUPERSEDED
}
