package de.caritas.cob.userservice.api.service.accountinvite;

/** The kind of unit an invite may wait for. */
public enum InviteUnitType {
  /** A Beratungsstelle — its first AGENCY_ADMIN creates it. */
  AGENCY,
  /** A Träger — its first TENANT_ADMIN creates it. */
  TENANT
}
