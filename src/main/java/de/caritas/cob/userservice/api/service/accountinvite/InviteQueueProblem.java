package de.caritas.cob.userservice.api.service.accountinvite;

/**
 * Why a {@code WAITING_FOR_UNIT} invite cannot move on by itself (ORISO-Admin#1026, slice 5).
 * Derived on read, so it disappears on its own once the cause is gone.
 */
public enum InviteQueueProblem {
  /**
   * No pending admin invite exists for the unit any more (revoked, expired, or — in a CSV import —
   * not arrived yet). Inviting a new admin for the same ID resolves it.
   */
  NO_UNIT_ADMIN
}
