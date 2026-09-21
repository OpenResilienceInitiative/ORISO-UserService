package de.caritas.cob.userservice.api.service.accountinvite.allocation;

/**
 * How an ID field of the invite composer is allocated (TEN-INV, ORISO-Admin#569).
 *
 * <p>{@code AUTO} — the owning service assigns the smallest currently free ID atomically; the
 * request must not pin an ID. {@code MANUAL} — the admin pinned a specific ID which is reserved or
 * rejected with a conflict. {@code EXISTING} (ORISO-Admin#1026) — the ID names a unit that already
 * exists; nothing is reserved, the unit is validated instead (exists, not deleted, inside the
 * caller's scope). Supported for {@code agencyIdAllocationMode} (slice 2) and for {@code
 * tenantIdAllocationMode} (slice 4, invite into an existing Träger).
 */
public enum IdAllocationMode {
  AUTO,
  MANUAL,
  EXISTING;

  /** Whether the mode reserves an ID in the owning service (AUTO, MANUAL) — EXISTING does not. */
  public static boolean reservesAnId(IdAllocationMode mode) {
    return mode == AUTO || mode == MANUAL;
  }
}
