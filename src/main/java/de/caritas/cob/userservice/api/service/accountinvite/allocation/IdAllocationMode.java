package de.caritas.cob.userservice.api.service.accountinvite.allocation;

/**
 * How an ID field of the invite composer is allocated (TEN-INV, ORISO-Admin#569).
 *
 * <p>{@code AUTO} — the owning service assigns the smallest currently free ID atomically; the
 * request must not pin an ID. {@code MANUAL} — the admin pinned a specific ID which is reserved or
 * rejected with a conflict.
 */
public enum IdAllocationMode {
  AUTO,
  MANUAL
}
