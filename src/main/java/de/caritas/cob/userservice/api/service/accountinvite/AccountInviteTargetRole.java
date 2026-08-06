package de.caritas.cob.userservice.api.service.accountinvite;

/** Target account type for an invite. Platform/app access is still decided separately. */
public enum AccountInviteTargetRole {
  TENANT_ADMIN,
  AGENCY_ADMIN,
  COUNSELLOR,
  PLATFORM_ADMIN,
  ADVICE_SEEKER
}
