package de.caritas.cob.userservice.api.service.accountinvite;

/**
 * Where an invite points, resolved from its allocation modes: the unit IDs, whether a new Träger or
 * Beratungsstelle ID has to be reserved, and which not-yet-created unit it waits for (null when it
 * does not wait).
 */
public record InviteTarget(
    AccountInviteTargetRole role,
    Long tenantId,
    Long agencyId,
    Long departmentId,
    InviteUnitType waitsFor,
    Reservation reservation) {

  /** What the ledger has to hold. {@code legacyTenantMode}: an old client sent no mode. */
  public record Reservation(boolean newTenant, boolean newAgency, boolean legacyTenantMode) {

    static final Reservation NONE = new Reservation(false, false, false);
  }

  /** The invite joins a new Beratungsstelle, which has no topics yet. */
  public boolean newAgency() {
    return reservation.newAgency() || waitsFor == InviteUnitType.AGENCY;
  }

  /** The ID the invite waits for: the new agency's or the new Träger's. */
  public Long waitedForUnitId() {
    return waitsFor == InviteUnitType.AGENCY ? agencyId : tenantId;
  }
}
