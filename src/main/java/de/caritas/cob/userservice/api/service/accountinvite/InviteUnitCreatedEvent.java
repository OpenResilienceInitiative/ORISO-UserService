package de.caritas.cob.userservice.api.service.accountinvite;

/**
 * Waiting invites are released only after the publishing transaction commits. For a tenant, {@code
 * tenantId} equals {@code unitId} and the proof identifies the reservation that was assigned.
 */
public record InviteUnitCreatedEvent(
    InviteUnitType unitType, Long unitId, Long tenantId, String reservationProof) {

  public InviteUnitCreatedEvent(InviteUnitType unitType, Long unitId, Long tenantId) {
    this(unitType, unitId, tenantId, null);
  }

  @Override
  public String toString() {
    return "InviteUnitCreatedEvent[unitType="
        + unitType
        + ", unitId="
        + unitId
        + ", tenantId="
        + tenantId
        + ", reservationProof=<redacted>]";
  }
}
