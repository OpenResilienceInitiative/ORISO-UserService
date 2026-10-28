package de.caritas.cob.userservice.api.port.out;

/** An authorized invitation preflight returns no identity projection or ownership grant. */
public interface InvitationRecipientAvailability {
  enum Kind {
    CONSULTANT,
    AGENCY_ADMIN,
    CONSULTANT_AGENCY_ADMIN,
    TENANT_ADMIN,
    PLATFORM_ADMIN
  }

  enum TenantMode {
    EXISTING,
    RESERVED_NEW,
    UNASSIGNED
  }

  record Target(Kind kind, Long tenantId, TenantMode tenantMode) {}

  /**
   * Dispatch data from the existing fresh-human boundary; native authorization remains required.
   */
  record Human(String subject, String issuer, String client, String authority, String rawBearer) {
    @Override
    public String toString() {
      return "InvitationHuman[redacted]";
    }
  }

  boolean available(String normalizedEmail, Target target);
}
