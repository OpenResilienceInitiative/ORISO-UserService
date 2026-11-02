package de.caritas.cob.userservice.api.workflow.delete.model;

/** What kind of account or object a deletion log entry is about. */
public enum DeletionAuditTargetType {
  CONSULTANT,
  AGENCY_ADMIN,
  TENANT_ADMIN,
  /** Written by AgencyService/TenantService once they report their deletions (not yet wired). */
  AGENCY,
  TENANT
}
