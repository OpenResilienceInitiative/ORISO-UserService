package de.caritas.cob.userservice.api.service.accountinvite;

public enum InviteEmailTemplateKind {
  TENANT_INVITE,
  COUNSELLOR_INVITE,
  /** Canonical security mail for an account that was created directly by an administrator. */
  EXISTING_ACCOUNT_SETUP,
  DPA_FORWARD,

  /**
   * Notice to the administrator who forwarded the DPA that the signature has landed
   * (ORISO-UserService#1005, epic ORISO-Admin#722).
   */
  DPA_SIGNED_NOTICE
}
