package de.caritas.cob.userservice.api.service.accountinvite;

/** Distinguishes ordinary account provisioning from a token bound to an existing account. */
public enum AccountInvitePurpose {
  INVITE,
  EXISTING_ACCOUNT_SETUP
}
