package de.caritas.cob.userservice.api.service.accountinvite.allocation;

/**
 * Authoritative allocation state of a tenant or agency ID as reported by the owning service
 * (TenantService U1 / AgencyService U2, ORISO-Admin#569).
 */
public enum IdAllocationStatus {
  /** Assignable — no tenant/agency and no open reservation uses this ID. */
  FREE,
  /** Held by an open invite reservation. */
  RESERVED,
  /** Consumed by an existing tenant/agency. */
  ASSIGNED
}
