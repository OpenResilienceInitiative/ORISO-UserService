package de.caritas.cob.userservice.api.service.accountinvite;

public enum TwoFactorGateStatus {
  NOT_REQUIRED,
  PENDING_SETUP,
  ACTIVE,
  WAIVED,
  DISABLED_BY_POLICY
}
