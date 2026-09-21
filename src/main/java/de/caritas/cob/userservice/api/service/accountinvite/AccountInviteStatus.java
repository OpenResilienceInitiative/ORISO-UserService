package de.caritas.cob.userservice.api.service.accountinvite;

/** Lifecycle state of an account invite. This is not the technical provisioning status. */
public enum AccountInviteStatus {
  /**
   * ORISO-Admin#1026 slice 5: the invite targets a Beratungsstelle / Träger that does not exist
   * yet, and it is not that unit's admin invite. It is stored but not sent; it is released (sent,
   * or turned into a DRAFT) when the unit's first admin finishes onboarding. No link exists yet.
   */
  WAITING_FOR_UNIT,
  DRAFT,
  EMAIL_SENT,
  ACCEPTED,
  EXPIRED,
  REVOKED,
  SUPERSEDED
}
